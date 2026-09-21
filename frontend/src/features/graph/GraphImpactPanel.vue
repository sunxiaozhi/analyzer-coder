<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue';
import {
  AlertTriangle, CheckCircle2, RefreshCw,
} from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import {
  intelligenceApi,
  type CodeGraphArtifact,
  type GraphResult,
} from '@/api/intelligence';
import { getIndexJob } from '@/api/repositories';
import type { IndexJob } from '@/types/api';


interface Props {
  repositoryId: string | null;
  filePath: string | null;
  initialSymbol: string | null;
  contentVersion: string | null;
  contextId?: string | null;
  initialDepth?: number;
  autoAnalyze?: boolean;
  canBuildGraph?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  initialDepth: 3, autoAnalyze: false, canBuildGraph: false,
});
const emit = defineEmits<{
  close: [];
  openFile: [path: string, startLine: number | null, endLine: number | null];
}>();

const artifact = shallowRef<CodeGraphArtifact | null>(null);
const relation = shallowRef<GraphResult | null>(null);
const symbol = shallowRef('');
const depth = shallowRef(3);
const loading = shallowRef(false);
const analyzing = shallowRef(false);
const building = shallowRef(false);
const buildJob = shallowRef<IndexJob | null>(null);
const error = shallowRef<string | null>(null);
const relationError = shallowRef<string | null>(null);
let contextVersion = 0;
let relationVersion = 0;
let buildVersion = 0;
let disposed = false;

const nodeById = computed(() => new Map((relation.value?.nodes ?? []).map(node => [node.id, node])));
const edgeById = computed(() => new Map((relation.value?.edges ?? []).map(edge => [edge.id, edge])));
const impactedNodes = computed(() => (relation.value?.nodes ?? [])
  .filter(node => !node.focus)
  .sort((left, right) => left.depth - right.depth || left.symbol.localeCompare(right.symbol)));

function nodeLabel(nodeId: string) {
  return nodeById.value.get(nodeId)?.symbol ?? nodeId;
}

function relationLabel(edgeId: string | undefined) {
  return edgeId ? (edgeById.value.get(edgeId)?.relation ?? '依赖') : '依赖';
}

function relationArrow(edgeId: string | undefined, from: string, to: string) {
  const edge = edgeId ? edgeById.value.get(edgeId) : null;
  if (edge?.source === from && edge.target === to) return '→';
  if (edge?.source === to && edge.target === from) return '←';
  return '·';
}

function buildJobLabel(job: IndexJob | null) {
  if (!job) return '正在提交图谱任务';
  if (job.status === 'QUEUED') return '图谱任务排队中';
  if (job.status === 'RUNNING') return job.currentStep || '正在构建代码图谱';
  if (job.status === 'CANCEL_REQUESTED') return '正在取消图谱任务';
  if (job.status === 'SUCCEEDED') return '图谱构建完成';
  if (job.status === 'CANCELED') return '图谱任务已取消';
  return '图谱构建失败';
}

function graphLimitationLabel(value: string) {
  if (value === 'CODEGRAPH_STATIC_ANALYSIS_ONLY') return '静态分析无法确认反射、运行时分派和数据驱动调用。';
  if (value.startsWith('CODEGRAPH_AFFECTED_NODE_UNMAPPED:')) return '部分 CLI 影响记录无法映射到可定位节点。';
  if (value.startsWith('CODEGRAPH_NODE_COUNT_MISMATCH:')) return 'CLI 节点数与可展示节点数不一致。';
  if (value.startsWith('CODEGRAPH_EDGE_COUNT_MISMATCH:')) return 'CLI 边数与可展示真实边数不一致，页面没有补造连线。';
  return '存在其他无法由静态分析覆盖的情况。';
}

async function load(resetSymbol = true, rerunRelation = false) {
  const version = ++contextVersion;
  relationVersion++;
  analyzing.value = false;
  loading.value = false;
  artifact.value = null;
  relation.value = null;
  error.value = null;
  relationError.value = null;
  if (resetSymbol) {
    symbol.value = props.initialSymbol ?? '';
    depth.value = Math.max(1, Math.min(props.initialDepth, 5));
  }
  if (!props.repositoryId || !props.filePath) return;
  loading.value = true;
  try {
    const graphRequest = props.contextId
      ? intelligenceApi.latestGraph(props.repositoryId, props.contextId)
      : intelligenceApi.latestGraph(props.repositoryId);
    const graphArtifact = await graphRequest;
    if (version !== contextVersion) return;
    artifact.value = graphArtifact?.contentVersion === props.contentVersion ? graphArtifact : null;
    if ((props.autoAnalyze || rerunRelation) && symbol.value && artifact.value) {
      await analyze();
    }
  } catch (exception) {
    if (version === contextVersion) {
      error.value = exception instanceof Error ? exception.message : '图谱加载失败';
    }
  } finally {
    if (version === contextVersion) loading.value = false;
  }
}

async function refresh() {
  await load(false, Boolean(relation.value));
}

async function analyze() {
  const version = ++relationVersion;
  const context = contextVersion;
  relation.value = null;
  analyzing.value = false;
  if (!props.repositoryId || !symbol.value.trim()) {
    relationError.value = '请输入明确的类、函数、方法或路由符号。';
    return;
  }
  if (!artifact.value) {
    relationError.value = '当前内容版本尚未发布代码图谱，不能生成真实关系路径。';
    return;
  }
  analyzing.value = true;
  relationError.value = null;
  try {
    const result = await intelligenceApi.graph(
      props.repositoryId,
      symbol.value.trim(),
      depth.value,
      'BOTH',
      props.contextId,
    );
    if (version !== relationVersion || context !== contextVersion) return;
    if (result.contentVersion !== props.contentVersion) {
      relationError.value = '代码内容版本已更新，请刷新文件和图谱后重新查询。';
      return;
    }
    relation.value = result;
  } catch (exception) {
    if (version !== relationVersion || context !== contextVersion) return;
    relation.value = null;
    relationError.value = exception instanceof Error ? exception.message : '关系查询失败';
  } finally {
    if (version === relationVersion && context === contextVersion) analyzing.value = false;
  }
}

async function buildGraph() {
  if (!props.repositoryId || !props.canBuildGraph || building.value) return;
  const repositoryId = props.repositoryId;
  const contentVersion = props.contentVersion;
  const version = ++buildVersion;
  building.value = true;
  buildJob.value = null;
  relationError.value = null;
  try {
    let task = await intelligenceApi.buildGraph(repositoryId, props.contextId);
    if (version !== buildVersion || disposed) return;
    buildJob.value = task;
    for (let attempt = 0; attempt < 240 && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status); attempt += 1) {
      await new Promise(resolve => window.setTimeout(resolve, 1500));
      if (version !== buildVersion || disposed || repositoryId !== props.repositoryId || contentVersion !== props.contentVersion) return;
      task = await getIndexJob(task.id);
      buildJob.value = task;
    }
    if (task.status !== 'SUCCEEDED') {
      throw new Error(task.errorMessage || (task.status === 'CANCELED' ? '代码图谱构建已取消' : '代码图谱构建超时或失败'));
    }
    ElMessage.success('代码图谱已发布，正在刷新关系证据');
    await load(false, Boolean(symbol.value.trim()));
  } catch (exception) {
    if (version === buildVersion && !disposed) {
      relationError.value = exception instanceof Error ? exception.message : '代码图谱构建失败';
      ElMessage.error(relationError.value);
    }
  } finally {
    if (version === buildVersion) building.value = false;
  }
}

function openNode(nodeId: string) {
  const node = nodeById.value.get(nodeId);
  if (node) emit('openFile', node.filePath, node.startLine, node.endLine);
}

watch(
  () => [props.repositoryId, props.filePath, props.initialSymbol, props.contentVersion, props.contextId, props.autoAnalyze, props.initialDepth] as const,
  () => void load(true),
  { immediate: true },
);
onScopeDispose(() => {
  disposed = true;
  contextVersion++;
  relationVersion++;
  buildVersion++;
});
</script>
<template>
<section class="evidence-context-panel impact-panel" aria-label="符号影响分析"><header class="impact-heading"><b>影响分析</b><button title="刷新影响分析" @click="refresh">刷新</button><button @click="emit('close')">关闭</button></header>
    <div class="context-body relation-body">
      <p v-if="error" class="context-error">{{ error }}</p>
      <section class="analysis-command">
        <div class="command-copy"><small>关系查询目标</small><b>从明确符号追踪真实依赖路径</b></div>
        <el-input v-model="symbol" placeholder="类、函数、方法或路由符号" @keyup.enter="analyze" />
        <label>深度 <el-input-number v-model="depth" :min="1" :max="5" controls-position="right" /></label>
        <el-button type="primary" :loading="analyzing" :disabled="!artifact" @click="analyze">分析影响</el-button>
      </section>
      <section class="artifact-line" :data-ready="Boolean(artifact)" :data-running="building">
        <span class="artifact-icon"><CheckCircle2 v-if="artifact && !building" :size="17" /><RefreshCw v-else :size="17" :class="{ spinning: building }" /></span>
        <div>
          <b v-if="building">{{ buildJobLabel(buildJob) }}</b>
          <b v-else-if="artifact">当前内容版本图谱已发布</b>
          <b v-else>当前内容版本没有已发布图谱</b>
          <p v-if="building">{{ buildJob?.currentStep || '任务已提交，完成后会自动刷新并执行影响分析。' }}</p>
          <p v-else-if="artifact">CodeGraph {{ artifact.cliVersion }} · {{ artifact.nodeCount }} 节点 · {{ artifact.edgeCount }} 条关系</p>
          <p v-else>构建后才能展示可回溯到源码的调用和依赖路径。</p>
        </div>
        <el-button v-if="canBuildGraph" plain :loading="building" @click="buildGraph">{{ artifact ? '重新构建' : '构建图谱' }}</el-button>
      </section>
      <p v-if="!artifact && !canBuildGraph" class="limitation">当前账号没有构建权限，请联系项目维护者在项目管理中构建代码图谱。</p>
      <div v-if="relationError" class="context-error"><AlertTriangle :size="14" />{{ relationError }}</div>
      <template v-if="relation">
        <div class="relation-summary">
          <span><small>影响节点</small><strong>{{ relation.affectedNodeCount }}</strong></span>
          <span><small>可解释路径</small><strong>{{ relation.paths.length }}</strong></span>
          <span><small>实际深度</small><strong>{{ relation.maxDepthReached }}</strong></span>
          <span><small>映射完整度</small><strong>{{ relation.coverage.representedAffectedRecordCount }}/{{ relation.coverage.affectedRecordCount }}</strong></span>
        </div>
        <p class="coverage" :data-complete="relation.coverage.complete">
          {{ relation.coverage.complete ? 'CLI 返回记录已完整映射到源码节点' : '部分 CLI 返回记录无法映射到源码节点' }}
        </p>
        <div class="relation-workspace">
          <section class="path-column">
            <header><div><small>传播路径</small><b>每条连线都来自当前图谱</b></div><span>{{ relation.paths.length }} 条</span></header>
            <div class="path-list">
              <article v-for="path in relation.paths" :key="path.targetNodeId">
                <header><span>深度 {{ path.depth }}</span><b>{{ nodeLabel(path.targetNodeId) }}</b></header>
                <div class="path-chain">
                  <template v-for="(nodeId, index) in path.nodeIds" :key="`${path.targetNodeId}:${nodeId}:${index}`">
                    <small v-if="index">{{ relationArrow(path.edgeIds[index - 1], path.nodeIds[index - 1], nodeId) }} {{ relationLabel(path.edgeIds[index - 1]) }}</small>
                    <button type="button" @click="openNode(nodeId)">{{ nodeLabel(nodeId) }}<ArrowRight :size="11" /></button>
                  </template>
                </div>
              </article>
              <p v-if="!relation.paths.length" class="context-empty">没有可由真实代码图谱关系解释的传播路径。</p>
            </div>
          </section>
          <section class="node-column">
            <header><div><small>影响清单</small><b>按距离从近到远排列</b></div><span>{{ impactedNodes.length }} 项</span></header>
            <div class="node-list">
              <button v-for="node in impactedNodes" :key="node.id" type="button" @click="openNode(node.id)">
                <i>+{{ node.depth }}</i><span><b>{{ node.symbol }}</b><small class="mono">{{ node.filePath }}:{{ node.startLine ?? 1 }}</small></span><em>{{ node.kind }}</em><ArrowRight :size="12" />
              </button>
              <p v-if="!impactedNodes.length" class="context-empty">当前符号没有其他已映射影响节点。</p>
            </div>
          </section>
        </div>
        <p v-for="item in relation.limitations" :key="item" class="limitation">{{ graphLimitationLabel(item) }}</p>
      </template>
    </div>

</section>
</template>
<style scoped>
.evidence-context-panel.impact-panel { grid-template-rows: auto minmax(0, 1fr); height: 100%; border-left: 1px solid #d8e1e6; }
.impact-heading { display: flex; align-items: center; gap: 12px; padding: 14px; border-bottom: 1px solid #d8e1e6; }
.impact-heading b { margin-right: auto; }
.impact-heading button { cursor: pointer; padding: 5px 10px; background: #fff; border: 1px solid #d8e1e6; border-radius: 4px; }
.evidence-context-panel { --ink: #1f2b35; --muted: #6d7a84; --blue: #1b668f; --blue-soft: #edf5f9; --green: #21745a; --green-soft: #edf7f3; --ochre: #9a6424; --ochre-soft: #fbf5ea; display: grid; grid-template-rows: auto auto minmax(0, 1fr); min-width: 0; min-height: 0; overflow: hidden; border: 1px solid #d8e1e6; border-left: 0; background: #fbfcfd; }
.context-body { min-height: 0; padding: 15px; overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.analysis-command { display: grid; grid-template-columns: minmax(180px, .8fr) minmax(220px, 1.2fr) auto auto; align-items: end; gap: 9px; padding: 12px; border: 1px solid #d6e0e6; background: #fff; }
.command-copy { display: grid; gap: 3px; align-self: center; }
.command-copy small, .relation-workspace > section > header small { color: var(--blue); font: 700 10px/1.2 "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
.command-copy b { color: var(--ink); font-size: 12px; }
.analysis-command label { display: grid; gap: 4px; color: var(--muted); font-size: 10px; }
.analysis-command :deep(.el-input-number) { width: 86px; }
.artifact-line { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 11px; margin: 10px 0; padding: 10px 12px; color: var(--ochre); border-left: 4px solid #b87a2b; background: var(--ochre-soft); }
.artifact-line[data-ready='true'] { color: var(--green); border-color: var(--green); background: var(--green-soft); }
.artifact-line[data-running='true'] { color: var(--blue); border-color: var(--blue); background: var(--blue-soft); }
.artifact-icon { display: grid; width: 31px; height: 31px; place-items: center; border: 1px solid currentColor; border-radius: 50%; }
.artifact-line > div { display: grid; gap: 3px; }
.artifact-line b { color: currentColor; font-size: 12px; }
.artifact-line p { margin: 0; color: #63737c; font-size: 11px; }
.relation-summary { display: grid; grid-template-columns: repeat(4, 1fr); margin-top: 11px; border: 1px solid #dbe4e8; background: #fff; }
.relation-summary span { display: grid; gap: 4px; padding: 9px 11px; border-right: 1px solid #dbe4e8; }
.relation-summary span:last-child { border-right: 0; }
.relation-summary small { color: #74828b; font-size: 10px; }
.relation-summary strong { color: var(--ink); font: 700 17px "SFMono-Regular", Consolas, monospace; }
.coverage { margin: 8px 0; padding: 7px 9px; color: var(--ochre); border-left: 3px solid #b87a2b; background: var(--ochre-soft); font-size: 11px; }
.coverage[data-complete='true'] { color: var(--green); border-color: var(--green); background: var(--green-soft); }
.relation-workspace { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(230px, .8fr); gap: 11px; align-items: start; }
.relation-workspace > section { min-width: 0; border: 1px solid #dbe3e8; background: #fff; }
.relation-workspace > section > header { display: flex; min-height: 46px; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 10px; border-bottom: 1px solid #e2e8ec; background: #f7f9fa; }
.relation-workspace > section > header > div { display: grid; gap: 3px; }
.relation-workspace > section > header b { color: var(--ink); font-size: 11px; }
.relation-workspace > section > header > span { color: #657680; font: 700 10px "SFMono-Regular", Consolas, monospace; }
.path-list { display: grid; }
.path-list article { display: grid; gap: 8px; padding: 10px; border-bottom: 1px solid #e8edf0; }
.path-list article:last-child { border-bottom: 0; }
.path-list article > header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.path-list article > header span { color: var(--blue); font: 700 10px "SFMono-Regular", Consolas, monospace; }
.path-list article > header b { overflow: hidden; color: #566771; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.path-chain { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.path-chain small { color: #87939a; font-size: 10px; }
.path-chain button { display: inline-flex; align-items: center; gap: 4px; padding: 5px 7px; color: #245f80; border: 1px solid #c7d9e3; border-radius: 3px; background: #fafdff; font: 10px "SFMono-Regular", Consolas, monospace; cursor: pointer; }
.context-error, .limitation { margin: 0; color: #7b878f; font-size: 11px; line-height: 1.6; }
.context-error { display: flex; align-items: flex-start; gap: 6px; margin: 8px 0; padding: 8px; color: #a34940; border-left: 3px solid #bd5b50; background: #fff3f1; }
.limitation { margin-top: 8px; padding: 7px 8px; border-left: 3px solid #a8b5bd; background: #f0f4f6; }
.spinning { animation: spin .85s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1080px) {
  .analysis-command { grid-template-columns: minmax(180px, .7fr) minmax(200px, 1fr) auto; }
  .analysis-command > .el-button { grid-column: 3; grid-row: 1 / 3; }
  .relation-workspace { grid-template-columns: 1fr; }
}
@media (max-width: 760px) {
  .context-body { padding: 10px; }
  .analysis-command { grid-template-columns: 1fr; align-items: stretch; }
  .analysis-command > .el-button { grid-column: auto; grid-row: auto; }
  .analysis-command :deep(.el-input-number) { width: 100%; }
  .artifact-line { grid-template-columns: auto minmax(0, 1fr); }
  .artifact-line > .el-button { grid-column: 1 / -1; }
  .relation-summary { grid-template-columns: repeat(2, 1fr); }
  .relation-summary span:nth-child(2) { border-right: 0; }
  .relation-summary span:nth-child(-n+2) { border-bottom: 1px solid #dbe4e8; }
}

</style>
