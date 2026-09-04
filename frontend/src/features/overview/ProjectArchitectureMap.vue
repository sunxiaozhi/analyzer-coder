<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import {
  ArrowRight,
  ArrowUpRight,
  Database,
  GitBranch,
  Grid3X3,
  LayoutList,
  Network,
  Search,
  ShieldAlert,
} from 'lucide-vue-next';
import ArchitectureSelectionPanel from './ArchitectureSelectionPanel.vue';
import type {
  ProjectArchitectureEdge,
  ProjectArchitectureMap as ArchitectureMap,
  ProjectArchitectureNode,
} from '@/api/repositories';

type ViewMode = 'browser' | 'matrix';

interface Props { map: ArchitectureMap | null }

const props = defineProps<Props>();
const emit = defineEmits<{
  openModule: [path: string];
  openSymbols: [module: string];
  openFile: [path: string];
  openGraph: [];
}>();

const viewMode = shallowRef<ViewMode>('browser');
const selectedId = shallowRef('');
const moduleQuery = shallowRef('');
const moduleNodes = computed(() =>
  (props.map?.nodes.filter(node => node.kind === 'MODULE') ?? [])
    .slice()
    .sort((left, right) => left.id.localeCompare(right.id)),
);
const resourceNodes = computed(() =>
  (props.map?.nodes.filter(node => node.kind === 'RESOURCE') ?? [])
    .slice()
    .sort((left, right) => left.label.localeCompare(right.label)),
);
const dependencyEdges = computed(() => props.map?.edges.filter(edge => edge.relation === 'DEPENDS_ON') ?? []);
const relationshipEdges = computed(() => props.map?.edges.filter(edge => edge.relation !== 'CONTAINS') ?? []);
const nodeById = computed(() => new Map((props.map?.nodes ?? []).map(node => [node.id, node])));
const selectedNode = computed(() => nodeById.value.get(selectedId.value) ?? moduleNodes.value[0] ?? null);
const selectedEdges = computed(() => relationshipEdges.value.filter(
  edge => edge.source === selectedNode.value?.id || edge.target === selectedNode.value?.id,
));
const incomingEdges = computed(() => selectedEdges.value.filter(edge => edge.target === selectedNode.value?.id));
const outgoingEdges = computed(() => selectedEdges.value.filter(edge => edge.source === selectedNode.value?.id));
const selectedRisks = computed(() => props.map?.risks.filter(
  risk => risk.modules.includes(selectedNode.value?.id ?? ''),
) ?? []);
const filteredModules = computed(() => {
  const query = moduleQuery.value.trim().toLowerCase();
  if (!query) return moduleNodes.value;
  return moduleNodes.value.filter(node => `${node.id} ${node.label} ${node.primaryLanguage}`.toLowerCase().includes(query));
});
const moduleGroups = computed(() => {
  const groups = new Map<string, ProjectArchitectureNode[]>();
  for (const node of filteredModules.value) {
    const group = node.id.includes('/') ? node.id.split('/')[0] : '根目录';
    groups.set(group, [...(groups.get(group) ?? []), node]);
  }
  return [...groups.entries()].map(([name, nodes]) => ({ name, nodes }));
});
const dependencyByPair = computed(() => new Map(
  dependencyEdges.value.map(edge => [`${edge.source}\u0000${edge.target}`, edge]),
));
const maxDependencyWeight = computed(() => Math.max(1, ...dependencyEdges.value.map(edge => edge.weight)));

function edgeCounterparty(edge: ProjectArchitectureEdge) {
  if (!selectedNode.value) return null;
  const targetId = edge.source === selectedNode.value.id ? edge.target : edge.source;
  return nodeById.value.get(targetId) ?? null;
}

function relationLabel(edge: ProjectArchitectureEdge) {
  return edge.relation === 'CONNECTS_TO' ? '运行连接' : '模块依赖';
}

function resourceTypeLabel(value: string | null) {
  if (!value) return '运行资源';
  return ({
    DATABASE: '数据库', CACHE: '缓存', QUEUE: '消息队列', TOPIC: '消息主题',
    STORAGE: '对象存储', EXTERNAL_SERVICE: '外部服务', SERVICE: '服务',
  } as Record<string, string>)[value] ?? '运行资源';
}

function moduleStats(nodeId: string) {
  return {
    incoming: relationshipEdges.value.filter(edge => edge.target === nodeId).length,
    outgoing: relationshipEdges.value.filter(edge => edge.source === nodeId).length,
  };
}

function riskSeverity(nodeId: string) {
  const risks = props.map?.risks.filter(risk => risk.modules.includes(nodeId)) ?? [];
  if (risks.some(risk => risk.severity === 'HIGH')) return 'HIGH';
  if (risks.some(risk => risk.severity === 'MEDIUM')) return 'MEDIUM';
  return null;
}

function selectNode(nodeId: string) {
  if (!nodeById.value.has(nodeId)) return;
  selectedId.value = nodeId;
}

function initialModuleId() {
  const riskModule = props.map?.risks
    .flatMap(risk => risk.modules)
    .find(id => nodeById.value.get(id)?.kind === 'MODULE');
  if (riskModule) return riskModule;
  return moduleNodes.value
    .slice()
    .sort((left, right) => {
      const rightDegree = moduleStats(right.id).incoming + moduleStats(right.id).outgoing;
      const leftDegree = moduleStats(left.id).incoming + moduleStats(left.id).outgoing;
      return rightDegree - leftDegree || left.id.localeCompare(right.id);
    })[0]?.id ?? '';
}

function matrixEdge(source: string, target: string) {
  return dependencyByPair.value.get(`${source}\u0000${target}`) ?? null;
}

function matrixPairHasRisk(source: string, target: string) {
  const edge = matrixEdge(source, target);
  return edge ? edgeHasRisk(edge) : false;
}

function inspectMatrixPair(source: string, target: string) {
  const edge = matrixEdge(source, target);
  if (edge) inspectMatrixEdge(edge);
}

function matrixCellStyle(edge: ProjectArchitectureEdge | null) {
  if (!edge) return undefined;
  const intensity = edge.weight / maxDependencyWeight.value;
  return { backgroundColor: `rgba(0, 102, 204, ${0.12 + intensity * 0.5})` };
}

function edgeHasRisk(edge: ProjectArchitectureEdge) {
  return props.map?.risks.some(risk =>
    risk.modules.includes(edge.source) && risk.modules.includes(edge.target),
  ) ?? false;
}

function inspectMatrixEdge(edge: ProjectArchitectureEdge) {
  selectedId.value = edge.source;
  viewMode.value = 'browser';
}

function resourceInboundCount(resourceId: string) {
  return relationshipEdges.value.filter(edge => edge.target === resourceId).length;
}

watch(
  () => props.map?.snapshotId,
  () => {
    viewMode.value = 'browser';
    moduleQuery.value = '';
    selectedId.value = initialModuleId();
  },
  { immediate: true, flush: 'post' },
);
</script>

<template>
  <section class="architecture-panel">
    <header class="panel-head">
      <div>
        <span class="eyebrow">关系视图</span>
        <h2>模块依赖</h2>
      </div>
      <div v-if="map" class="map-summary">
        <span><Network :size="13" />{{ moduleNodes.length }} 模块</span>
        <span><GitBranch :size="13" />{{ dependencyEdges.length }} 依赖</span>
        <span><Database :size="13" />{{ resourceNodes.length }} 运行资源</span>
        <span :class="{ warning: map.risks.length > 0 }"><ShieldAlert :size="13" />{{ map.risks.length }} 提醒</span>
      </div>
      <div v-if="map" class="head-actions">
        <div class="view-switch" role="tablist" aria-label="关系展示方式">
          <button type="button" role="tab" :aria-selected="viewMode === 'browser'" :class="{ active: viewMode === 'browser' }" @click="viewMode = 'browser'">
            <LayoutList :size="13" />模块浏览
          </button>
          <button type="button" role="tab" :aria-selected="viewMode === 'matrix'" :class="{ active: viewMode === 'matrix' }" @click="viewMode = 'matrix'">
            <Grid3X3 :size="13" />依赖矩阵
          </button>
        </div>
        <button type="button" class="graph-link" @click="emit('openGraph')">符号图谱 <ArrowUpRight :size="12" /></button>
      </div>
    </header>

    <div v-if="map && moduleNodes.length && viewMode === 'browser'" class="browser-layout">
      <aside class="module-index">
        <header><strong>项目模块</strong><span>{{ filteredModules.length }}/{{ moduleNodes.length }}</span></header>
        <el-input v-model="moduleQuery" :prefix-icon="Search" clearable placeholder="筛选模块" aria-label="筛选架构模块" />
        <div class="module-groups">
          <section v-for="group in moduleGroups" :key="group.name">
            <h3>{{ group.name }}</h3>
            <button
              v-for="node in group.nodes"
              :key="node.id"
              type="button"
              :class="{ active: selectedNode?.id === node.id }"
              :data-risk="riskSeverity(node.id)"
              @click="selectNode(node.id)"
            >
              <span><strong>{{ node.label }}</strong><small>{{ node.primaryLanguage }} · {{ node.codeFileCount }} 文件</small></span>
              <em>{{ moduleStats(node.id).incoming }} 入 / {{ moduleStats(node.id).outgoing }} 出</em>
            </button>
          </section>
          <p v-if="!filteredModules.length" class="empty-note">没有匹配的模块。</p>
        </div>
      </aside>

      <main v-if="selectedNode" class="relation-workspace">
        <header class="workspace-title">
          <div>
            <span>{{ selectedNode.kind === 'RESOURCE' ? '运行资源' : '选中模块' }}</span>
            <h3>{{ selectedNode.kind === 'RESOURCE' ? selectedNode.label : selectedNode.id }}</h3>
            <p>{{ selectedNode.kind === 'RESOURCE' ? resourceTypeLabel(selectedNode.resourceType) : `${selectedNode.primaryLanguage} · ${selectedNode.codeFileCount} 个代码文件` }}</p>
          </div>
          <span v-if="riskSeverity(selectedNode.id)" class="risk-badge" :data-severity="riskSeverity(selectedNode.id)">
            <ShieldAlert :size="13" />{{ riskSeverity(selectedNode.id) === 'HIGH' ? '高风险提醒' : '架构提醒' }}
          </span>
        </header>

        <div class="relation-flow">
          <section class="relation-column incoming">
            <header><span>依赖当前节点</span><strong>{{ incomingEdges.length }}</strong></header>
            <div class="relation-list">
              <button v-for="edge in incomingEdges" :key="`${edge.relation}:${edge.source}:${edge.target}`" type="button" @click="selectNode(edge.source)">
                <span class="node-icon"><Database v-if="edgeCounterparty(edge)?.kind === 'RESOURCE'" :size="14" /><GitBranch v-else :size="14" /></span>
                <span><strong>{{ edgeCounterparty(edge)?.label ?? edge.source }}</strong><small>{{ relationLabel(edge) }} · {{ edge.weight }} 条证据</small></span>
                <ArrowRight :size="13" />
              </button>
              <p v-if="!incomingEdges.length" class="empty-note">未发现其他模块依赖当前节点。</p>
            </div>
          </section>

          <div class="current-node" :class="{ resource: selectedNode.kind === 'RESOURCE' }" :data-risk="riskSeverity(selectedNode.id)">
            <span><Database v-if="selectedNode.kind === 'RESOURCE'" :size="20" /><Network v-else :size="20" /></span>
            <strong>{{ selectedNode.label }}</strong>
            <small>{{ selectedNode.kind === 'RESOURCE' ? resourceTypeLabel(selectedNode.resourceType) : selectedNode.id }}</small>
          </div>

          <section class="relation-column outgoing">
            <header><span>当前节点依赖</span><strong>{{ outgoingEdges.length }}</strong></header>
            <div class="relation-list">
              <button v-for="edge in outgoingEdges" :key="`${edge.relation}:${edge.source}:${edge.target}`" type="button" @click="selectNode(edge.target)">
                <ArrowRight :size="13" />
                <span class="node-icon"><Database v-if="edgeCounterparty(edge)?.kind === 'RESOURCE'" :size="14" /><GitBranch v-else :size="14" /></span>
                <span><strong>{{ edgeCounterparty(edge)?.label ?? edge.target }}</strong><small>{{ relationLabel(edge) }} · {{ edge.weight }} 条证据</small></span>
              </button>
              <p v-if="!outgoingEdges.length" class="empty-note">未发现当前节点的跨模块依赖。</p>
            </div>
          </section>
        </div>
      </main>

      <ArchitectureSelectionPanel
        :node="selectedNode"
        :edges="selectedEdges"
        :risks="selectedRisks"
        @open-module="emit('openModule', $event)"
        @open-symbols="emit('openSymbols', $event)"
        @open-file="emit('openFile', $event)"
      />
    </div>

    <div v-else-if="map && moduleNodes.length" class="matrix-view">
      <header class="matrix-intro">
        <div><strong>全局模块依赖矩阵</strong><span>纵轴模块依赖横轴模块；颜色越深表示静态引用越多。</span></div>
        <span><i></i>有依赖 <i class="risk"></i>含架构提醒</span>
      </header>
      <div class="matrix-scroll">
        <table>
          <thead>
            <tr>
              <th class="matrix-corner">来源 \ 目标</th>
              <th v-for="target in moduleNodes" :key="target.id" :title="target.id">
                <span>{{ target.id.split('/')[0] }}</span><strong>{{ target.label }}</strong>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="source in moduleNodes" :key="source.id">
              <th :title="source.id"><span>{{ source.id.split('/')[0] }}</span><strong>{{ source.label }}</strong></th>
              <td v-for="target in moduleNodes" :key="target.id">
                <span v-if="source.id === target.id" class="matrix-diagonal">—</span>
                <button
                  v-else-if="matrixEdge(source.id, target.id)"
                  type="button"
                  class="matrix-cell"
                  :class="{ risk: matrixPairHasRisk(source.id, target.id) }"
                  :style="matrixCellStyle(matrixEdge(source.id, target.id))"
                  :title="`${source.id} → ${target.id}：${matrixEdge(source.id, target.id)?.weight} 条引用`"
                  @click="inspectMatrixPair(source.id, target.id)"
                >
                  {{ matrixEdge(source.id, target.id)?.weight }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <section v-if="resourceNodes.length" class="runtime-strip">
        <header><strong>运行资源</strong><span>从代码和配置中识别的外部连接</span></header>
        <div>
          <button v-for="resource in resourceNodes" :key="resource.id" type="button" @click="selectNode(resource.id); viewMode = 'browser'">
            <Database :size="15" />
            <span><strong>{{ resource.label }}</strong><small>{{ resourceTypeLabel(resource.resourceType) }}</small></span>
            <em>{{ resourceInboundCount(resource.id) }} 个连接</em>
          </button>
        </div>
      </section>
    </div>

    <el-empty v-else :image-size="54" description="完成代码快照后将自动生成模块依赖关系" />

    <footer v-if="map" class="coverage">
      <span>已分析 {{ map.coverage.analyzedFiles }}/{{ map.coverage.totalCodeFiles }} 个代码文件</span>
      <em v-if="map.coverage.partial">部分覆盖</em>
      <small>{{ map.coverage.notes[0] }}</small>
    </footer>
  </section>
</template>

<style scoped>
.architecture-panel { min-width: 0; overflow: hidden; background: #fff; }
.panel-head { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; min-height: 62px; align-items: center; gap: 18px; padding: 10px 16px; border-bottom: 1px solid #ececef; }
.eyebrow { color: var(--app-text-muted); font-size: 13px; font-weight: 650; letter-spacing: .04em; }
.panel-head h2 { margin: 4px 0 0; color: #1d1d1f; font-size: 15px; }
.map-summary { display: flex; align-items: center; flex-wrap: wrap; justify-content: center; gap: 12px; color: #71717a; font-size: 13px; }
.map-summary span { display: flex; align-items: center; gap: 4px; }
.map-summary .warning { color: #a34d2e; }
.head-actions { display: flex; align-items: center; gap: 7px; }
.view-switch { display: flex; gap: 2px; padding: 3px; border-radius: 6px; background: #f1f1f4; }
.view-switch button { display: flex; min-height: 28px; align-items: center; gap: 4px; padding: 4px 7px; color: #626269; border: 0; border-radius: 4px; background: transparent; font-size: 13px; }
.view-switch button.active { color: var(--app-color-action); background: #fff; box-shadow: 0 1px 3px rgb(24 39 58 / 12%); }
.view-switch button:focus-visible, .graph-link:focus-visible { outline: 2px solid rgb(0 102 204 / 22%); outline-offset: 1px; }
.graph-link { display: flex; align-items: center; gap: 4px; padding: 6px 8px; color: var(--app-color-action); border: 1px solid #d5e2ef; border-radius: 4px; background: #f3f8fc; font-size: 13px; }
.browser-layout { display: grid; grid-template-columns: 220px minmax(420px, 1fr) 280px; min-height: 480px; background: #f7f8fa; }
.module-index { display: grid; grid-template-rows: auto auto minmax(0, 1fr); min-width: 0; border-right: 1px solid #dedee3; background: #fff; }
.module-index > header { display: flex; min-height: 44px; align-items: center; justify-content: space-between; padding: 8px 12px; }
.module-index > header strong { font-size: 13px; }
.module-index > header span { color: var(--app-text-muted); font-size: 13px; }
.module-index :deep(.el-input) { padding: 0 10px 9px; }
.module-index :deep(.el-input__wrapper) { min-height: 30px; border-radius: 6px !important; background: #fafbfc; box-shadow: 0 0 0 1px #d8dce2 inset; }
.module-groups { min-height: 0; padding: 2px 8px 10px; overflow: auto; }
.module-groups section { display: grid; gap: 3px; margin-bottom: 10px; }
.module-groups h3 { margin: 5px 7px 3px; color: var(--app-text-muted); font-size: 13px; font-weight: 600; }
.module-groups button { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px; width: 100%; min-height: 44px; align-items: center; padding: 7px 8px; color: #3f3f45; text-align: left; border: 0; border-radius: 5px; background: transparent; }
.module-groups button:hover, .module-groups button:focus-visible { outline: none; background: #f5f7fa; }
.module-groups button.active { color: #005eb8; background: var(--app-color-action-soft); box-shadow: inset 3px 0 var(--app-color-action); }
.module-groups button[data-risk='HIGH']::after, .module-groups button[data-risk='MEDIUM']::after { position: absolute; top: 7px; right: 7px; width: 6px; height: 6px; border-radius: 50%; background: var(--app-color-danger); content: ''; }
.module-groups button[data-risk='MEDIUM']::after { background: #c4852d; }
.module-groups button > span { display: grid; min-width: 0; gap: 2px; }
.module-groups button strong { overflow: hidden; font: 600 14px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.module-groups button small { color: var(--app-text-muted); font-size: 13px; }
.module-groups button em { margin-top: 13px; color: var(--app-text-muted); font-size: 13px; font-style: normal; white-space: nowrap; }
.relation-workspace { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; padding: 16px; overflow: hidden; }
.workspace-title { display: flex; min-height: 66px; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 14px; border-bottom: 1px solid #dedee3; }
.workspace-title > div { display: grid; gap: 3px; }
.workspace-title span { color: var(--app-text-muted); font-size: 13px; }
.workspace-title h3 { margin: 0; color: #1d1d1f; font: 650 15px "SFMono-Regular", Consolas, monospace; }
.workspace-title p { margin: 0; color: var(--app-text-muted); font-size: 14px; }
.risk-badge { display: flex; align-items: center; gap: 4px; padding: 5px 7px; color: #9b3f31 !important; border-radius: 4px; background: #fbefed; }
.risk-badge[data-severity='MEDIUM'] { color: #8d5c24 !important; background: #fbf5ec; }
.relation-flow { display: grid; grid-template-columns: minmax(150px, 1fr) 156px minmax(150px, 1fr); align-items: center; gap: 20px; min-height: 360px; }
.relation-column { align-self: stretch; display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; padding-top: 18px; }
.relation-column > header { display: flex; align-items: center; justify-content: space-between; padding: 0 2px 8px; color: #71717a; border-bottom: 1px solid #dedee3; font-size: 13px; }
.relation-column > header strong { color: #1d1d1f; }
.relation-list { display: grid; align-content: center; gap: 7px; min-width: 0; padding: 12px 0; overflow: auto; }
.relation-list button { display: grid; grid-template-columns: 30px minmax(0, 1fr) 16px; align-items: center; gap: 7px; min-height: 48px; padding: 7px 8px; color: #52525b; text-align: left; border: 1px solid #dedee3; border-radius: 6px; background: #fff; }
.outgoing .relation-list button { grid-template-columns: 16px 30px minmax(0, 1fr); }
.relation-list button:hover, .relation-list button:focus-visible { color: var(--app-color-action); border-color: #90bde5; outline: none; box-shadow: 0 0 0 3px rgb(0 102 204 / 8%); }
.relation-list .node-icon { display: grid; width: 28px; height: 28px; place-items: center; color: var(--app-color-action); border-radius: 5px; background: var(--app-color-action-soft); }
.relation-list button > span:nth-child(2), .outgoing .relation-list button > span:nth-child(3) { display: grid; min-width: 0; gap: 3px; }
.relation-list strong { overflow: hidden; color: #303036; font: 600 14px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.relation-list small { color: var(--app-text-muted); font-size: 13px; }
.current-node { position: relative; display: grid; justify-items: center; gap: 7px; padding: 20px 12px; color: #fff; border: 1px solid #005eb8; border-radius: 8px; background: var(--app-color-action); box-shadow: 0 8px 20px rgb(0 102 204 / 15%); }
.current-node::before, .current-node::after { position: absolute; top: 50%; width: 20px; height: 1px; background: #90bde5; content: ''; }
.current-node::before { right: 100%; }
.current-node::after { left: 100%; }
.current-node.resource { color: #72501f; border-color: #d6c29e; background: var(--app-color-warning-soft); box-shadow: none; }
.current-node[data-risk='HIGH'] { box-shadow: 0 0 0 3px rgb(189 69 47 / 16%), 0 8px 20px rgb(0 102 204 / 15%); }
.current-node > span { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 7px; background: rgb(255 255 255 / 14%); }
.current-node.resource > span { background: #f3e7d2; }
.current-node strong { max-width: 132px; overflow: hidden; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.current-node small { max-width: 132px; overflow: hidden; color: rgb(255 255 255 / 92%); font: 13px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.current-node.resource small { color: #8a632d; }
.browser-layout > .selection-panel { border-left: 1px solid #dedee3; }
.empty-note { margin: 8px 3px; color: var(--app-text-muted); font-size: 13px; line-height: 1.5; }
.matrix-view { min-width: 0; background: #fff; }
.matrix-intro { display: flex; min-height: 52px; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 14px; border-bottom: 1px solid #ececef; }
.matrix-intro > div { display: grid; gap: 3px; }
.matrix-intro strong { font-size: 13px; }
.matrix-intro span { color: var(--app-text-muted); font-size: 13px; }
.matrix-intro > span { display: flex; align-items: center; gap: 5px; }
.matrix-intro i { width: 11px; height: 11px; margin-left: 5px; background: rgb(0 102 204 / 36%); }
.matrix-intro i.risk { border: 2px solid var(--app-color-danger); background: rgb(0 102 204 / 22%); }
.matrix-scroll { max-height: 580px; overflow: auto; }
.matrix-scroll table { min-width: 100%; border-spacing: 0; border-collapse: separate; font-size: 13px; }
.matrix-scroll th, .matrix-scroll td { width: 58px; min-width: 58px; height: 46px; padding: 0; text-align: center; border-right: 1px solid #ececef; border-bottom: 1px solid #ececef; }
.matrix-scroll thead th { position: sticky; top: 0; z-index: 2; color: #52525b; background: #fafafa; }
.matrix-scroll tbody th { position: sticky; left: 0; z-index: 1; width: 130px; min-width: 130px; padding: 0 9px; text-align: left; background: #fafafa; }
.matrix-scroll .matrix-corner { left: 0; z-index: 3; width: 130px; min-width: 130px; color: var(--app-text-muted); }
.matrix-scroll th span { display: block; color: var(--app-text-muted); font-size: 13px; font-weight: 400; }
.matrix-scroll th strong { display: block; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.matrix-cell { width: 34px; height: 28px; padding: 0; color: #174f80; border: 0; border-radius: 4px; font-size: 13px; font-weight: 700; }
.matrix-cell.risk { outline: 2px solid var(--app-color-danger); outline-offset: -2px; }
.matrix-cell:hover, .matrix-cell:focus-visible { color: #fff; outline: 2px solid var(--app-color-action); outline-offset: 1px; background: var(--app-color-action) !important; }
.matrix-diagonal { color: #c2c2c7; }
.runtime-strip { padding: 13px 14px 15px; border-top: 1px solid #dedee3; background: #fafafa; }
.runtime-strip > header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 9px; }
.runtime-strip > header strong { font-size: 13px; }
.runtime-strip > header span { color: var(--app-text-muted); font-size: 13px; }
.runtime-strip > div { display: flex; flex-wrap: wrap; gap: 7px; }
.runtime-strip button { display: grid; grid-template-columns: 28px minmax(100px, 1fr) auto; align-items: center; gap: 7px; min-width: 220px; padding: 8px 9px; color: #72501f; text-align: left; border: 1px solid #e2d3ba; border-radius: 6px; background: var(--app-color-warning-soft); }
.runtime-strip button:hover, .runtime-strip button:focus-visible { border-color: #c9a96f; outline: none; }
.runtime-strip button > span { display: grid; gap: 2px; }
.runtime-strip button strong { font-size: 14px; }
.runtime-strip button small { color: #72501f; font-size: 13px; }
.runtime-strip button em { color: #8a632d; font-size: 13px; font-style: normal; }
.coverage { display: flex; align-items: center; gap: 10px; min-height: 34px; padding: 7px 16px; color: var(--app-text-muted); border-top: 1px solid #ececef; background: #fafafa; font-size: 13px; }
.coverage em { padding: 2px 5px; color: #8f5c26; border-radius: 3px; background: #f5e8d8; font-style: normal; }
.coverage small { margin-left: auto; color: var(--app-text-muted); font-size: 13px; }
@media (max-width: 1180px) {
  .panel-head { grid-template-columns: auto 1fr; }
  .map-summary { justify-content: flex-start; }
  .head-actions { grid-column: 1 / -1; }
  .browser-layout { grid-template-columns: 210px minmax(380px, 1fr); }
  .browser-layout > .selection-panel { grid-column: 1 / -1; min-height: 240px; border-top: 1px solid #dedee3; border-left: 0; }
}
@media (max-width: 760px) {
  .panel-head { grid-template-columns: 1fr; align-items: flex-start; }
  .map-summary { display: grid; grid-template-columns: repeat(2, 1fr); }
  .head-actions { grid-column: auto; flex-wrap: wrap; }
  .browser-layout { grid-template-columns: 1fr; }
  .module-index { max-height: 280px; border-right: 0; border-bottom: 1px solid #dedee3; }
  .relation-workspace { padding: 12px; }
  .relation-flow { grid-template-columns: 1fr; gap: 8px; }
  .current-node { grid-row: 1; }
  .current-node::before, .current-node::after { display: none; }
  .relation-column { padding-top: 8px; }
  .matrix-intro, .coverage { align-items: flex-start; flex-direction: column; }
  .coverage small { margin-left: 0; }
}
</style>
