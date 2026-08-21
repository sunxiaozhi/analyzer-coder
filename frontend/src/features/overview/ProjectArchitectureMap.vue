<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { ArrowUpRight, Database, GitBranch, Network, ShieldAlert } from 'lucide-vue-next';
import ArchitectureSelectionPanel from './ArchitectureSelectionPanel.vue';
import type {
  ProjectArchitectureEdge,
  ProjectArchitectureMap as ArchitectureMap,
  ProjectArchitectureNode,
} from '@/api/repositories';

interface Props {
  map: ArchitectureMap | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  openModule: [path: string];
  openSymbols: [module: string];
  openFile: [path: string];
  openGraph: [];
}>();

const selectedId = shallowRef('$project');
const focusId = shallowRef<string | null>(null);
const moduleNodes = computed(() => props.map?.nodes.filter(node => node.kind === 'MODULE') ?? []);
const resourceNodes = computed(() => props.map?.nodes.filter(node => node.kind === 'RESOURCE') ?? []);
const dependencyEdges = computed(
  () => props.map?.edges.filter(edge => edge.relation === 'DEPENDS_ON') ?? [],
);
const relationshipEdges = computed(
  () => props.map?.edges.filter(edge => edge.relation !== 'CONTAINS') ?? [],
);
const displayNodes = computed(() => {
  if (!focusId.value) return props.map?.nodes ?? [];
  const visibleIds = new Set([focusId.value]);
  for (const edge of relationshipEdges.value) {
    if (edge.source === focusId.value) visibleIds.add(edge.target);
    if (edge.target === focusId.value) visibleIds.add(edge.source);
  }
  return props.map?.nodes.filter(node => visibleIds.has(node.id)) ?? [];
});
const displayModuleNodes = computed(
  () => displayNodes.value.filter(node => node.kind === 'MODULE'),
);
const displayResourceNodes = computed(
  () => displayNodes.value.filter(node => node.kind === 'RESOURCE'),
);
const groups = computed(() => {
  const grouped = new Map<string, ProjectArchitectureNode[]>();
  for (const node of displayModuleNodes.value) {
    const group = node.id.includes('/') ? node.id.split('/')[0] : node.id;
    const items = grouped.get(group) ?? [];
    items.push(node);
    grouped.set(group, items);
  }
  return [...grouped.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, nodes]) => ({
      name,
      nodes: nodes.sort((left, right) => left.id.localeCompare(right.id)),
    }));
});
const canvasWidth = computed(() =>
  Math.max(920, 190 + (groups.value.length + (displayResourceNodes.value.length ? 1 : 0)) * 220),
);
const canvasHeight = computed(() => {
  const largestGroup = Math.max(
    1,
    displayResourceNodes.value.length,
    ...groups.value.map(group => group.nodes.length),
  );
  return Math.max(330, 88 + largestGroup * 70);
});
const positions = computed(() => {
  const result = new Map<string, { x: number; y: number }>();
  result.set('$project', { x: 22, y: Math.round(canvasHeight.value / 2 - 27) });
  groups.value.forEach((group, groupIndex) => {
    group.nodes.forEach((node, nodeIndex) => {
      result.set(node.id, {
        x: 190 + groupIndex * 220,
        y: 54 + nodeIndex * 70,
      });
    });
  });
  const resourceX = 190 + groups.value.length * 220;
  displayResourceNodes.value.forEach((node, nodeIndex) => {
    result.set(node.id, {
      x: resourceX,
      y: 54 + nodeIndex * 70,
    });
  });
  return result;
});
const visibleEdges = computed(() => props.map?.edges.filter(edge => {
  if (!positions.value.has(edge.source) || !positions.value.has(edge.target)) return false;
  if (!focusId.value) return true;
  return edge.relation !== 'CONTAINS'
    && (edge.source === focusId.value || edge.target === focusId.value);
}) ?? []);
const selectedNode = computed(
  () => props.map?.nodes.find(node => node.id === selectedId.value) ?? props.map?.nodes[0] ?? null,
);
const selectedEdges = computed(() =>
  relationshipEdges.value.filter(
    edge => edge.source === selectedId.value || edge.target === selectedId.value,
  ),
);
const selectedRisks = computed(() =>
  props.map?.risks.filter(risk => risk.modules.includes(selectedId.value)) ?? [],
);
const riskByModule = computed(() => {
  const result = new Map<string, 'HIGH' | 'MEDIUM' | 'LOW'>();
  for (const risk of props.map?.risks ?? []) {
    for (const module of risk.modules) {
      const current = result.get(module);
      if (!current || current === 'LOW' || (current === 'MEDIUM' && risk.severity === 'HIGH')) {
        result.set(module, risk.severity);
      }
    }
  }
  return result;
});

function nodeStyle(nodeId: string) {
  const position = positions.value.get(nodeId) ?? { x: 0, y: 0 };
  return { left: `${position.x}px`, top: `${position.y}px` };
}

function edgePath(edge: ProjectArchitectureEdge) {
  const source = positions.value.get(edge.source);
  const target = positions.value.get(edge.target);
  if (!source || !target) return '';
  const startX = source.x + 154;
  const startY = source.y + 27;
  const endX = target.x - 4;
  const endY = target.y + 27;
  const bend = Math.max(38, Math.abs(endX - startX) * 0.45);
  return `M ${startX} ${startY} C ${startX + bend} ${startY}, ${endX - bend} ${endY}, ${endX} ${endY}`;
}

function edgeClass(edge: ProjectArchitectureEdge) {
  return {
    contains: edge.relation === 'CONTAINS',
    dependency: edge.relation === 'DEPENDS_ON',
    runtime: edge.relation === 'CONNECTS_TO',
    active: edge.source === selectedId.value || edge.target === selectedId.value,
  };
}
function nodeDetail(node: ProjectArchitectureNode) {
  if (node.kind === 'RESOURCE') return node.resourceType ?? '外部依赖';
  return `${node.codeFileCount} 个代码文件`;
}

function selectNode(nodeId: string) {
  selectedId.value = nodeId;
}

function focusNode(nodeId: string) {
  focusId.value = nodeId;
  selectedId.value = nodeId;
}

function resetFocus() {
  focusId.value = null;
}

watch(
  () => props.map?.snapshotId,
  () => {
    selectedId.value = '$project';
    focusId.value = null;
  },
  { immediate: true },
);
</script>

<template>
  <section class="architecture-panel">
    <header class="panel-head">
      <div>
        <span class="eyebrow">ARCHITECTURE MAP</span>
        <h2>
          模块依赖地图
          <small v-if="focusId">聚焦 · {{ focusId }}</small>
        </h2>
      </div>
      <div v-if="map" class="map-summary">
        <span><Network :size="13" />{{ moduleNodes.length }} 模块</span>
        <span><GitBranch :size="13" />{{ dependencyEdges.length }} 依赖</span>
        <span><Database :size="13" />{{ resourceNodes.length }} 运行依赖</span>
        <span :class="{ warning: map.risks.length > 0 }">
          <ShieldAlert :size="13" />{{ map.risks.length }} 提醒
        </span>
        <button type="button" @click="emit('openGraph')">
          符号图谱 <ArrowUpRight :size="12" />
        </button>
      </div>
    </header>

    <div v-if="map && moduleNodes.length" class="map-body">
      <div class="canvas-scroll">
        <div
          class="map-canvas"
          :style="{ width: `${canvasWidth}px`, height: `${canvasHeight}px` }"
        >
          <svg
            class="edge-layer"
            :viewBox="`0 0 ${canvasWidth} ${canvasHeight}`"
            aria-hidden="true"
          >
            <defs>
              <marker id="architecture-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M0,0 L8,4 L0,8 Z" />
              </marker>
            </defs>
            <path
              v-for="edge in visibleEdges"
              :key="`${edge.relation}:${edge.source}:${edge.target}`"
              :d="edgePath(edge)"
              :class="edgeClass(edge)"
              marker-end="url(#architecture-arrow)"
            />
          </svg>

          <button
            v-for="node in displayNodes"
            :key="node.id"
            type="button"
            class="map-node"
            :class="{
              project: node.kind === 'PROJECT',
              resource: node.kind === 'RESOURCE',
              selected: node.id === selectedId,
            }"
            :data-risk="riskByModule.get(node.id)"
            :style="nodeStyle(node.id)"
            @click="selectNode(node.id)"
          >
            <span class="node-icon">
              <Network v-if="node.kind === 'PROJECT'" :size="16" />
              <Database v-else-if="node.kind === 'RESOURCE'" :size="15" />
              <GitBranch v-else :size="15" />
            </span>
            <span class="node-copy">
              <strong>{{ node.label }}</strong>
              <small>{{ nodeDetail(node) }}</small>
            </span>
            <i v-if="riskByModule.has(node.id)" :title="`${riskByModule.get(node.id)} risk`"></i>
          </button>

          <span
            v-for="group in groups"
            :key="group.name"
            class="group-label"
            :style="{ left: `${(positions.get(group.nodes[0]?.id ?? '')?.x ?? 0)}px` }"
          >
            {{ group.name }}
          </span>
          <span
            v-if="displayResourceNodes.length"
            class="group-label resource-label"
            :style="{ left: `${190 + groups.length * 220}px` }"
          >
            runtime
          </span>
        </div>
      </div>

      <ArchitectureSelectionPanel
        :node="selectedNode"
        :edges="selectedEdges"
        :risks="selectedRisks"
        :focused="focusId !== null"
        @select-node="selectNode"
        @focus-node="focusNode"
        @reset-focus="resetFocus"
        @open-module="emit('openModule', $event)"
        @open-symbols="emit('openSymbols', $event)"
        @open-file="emit('openFile', $event)"
      />
    </div>

    <el-empty v-else :image-size="54" description="完成代码快照后将自动生成模块依赖地图" />

    <footer v-if="map" class="coverage">
      <span>
        已分析 {{ map.coverage.analyzedFiles }}/{{ map.coverage.totalCodeFiles }} 个代码文件
      </span>
      <em v-if="map.coverage.partial">部分覆盖</em>
      <small>{{ map.coverage.notes[0] }}</small>
    </footer>
  </section>
</template>

<style scoped>
.architecture-panel { min-width: 0; overflow: hidden; border: 1px solid #d7e0e6; border-radius: 8px; background: #fff; }
.panel-head { display: flex; min-height: 70px; align-items: center; justify-content: space-between; gap: 18px; padding: 13px 16px; border-bottom: 1px solid #e5eaef; }
.eyebrow { color: #315f7c; font: 700 9px/1.2 Consolas, monospace; letter-spacing: .13em; }
.panel-head h2 { margin: 5px 0 0; color: #23313d; font-size: 15px; }
.map-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; color: #657580; font-size: 9px; }
.panel-head h2 small { margin-left: 8px; color: #08795f; font: 600 9px Consolas, monospace; }
.map-summary span { display: flex; align-items: center; gap: 4px; }
.map-summary .warning { color: #a34d2e; }
.map-summary button { display: flex; align-items: center; gap: 4px; padding: 6px 8px; color: #315f7c; border: 1px solid #cad9e3; border-radius: 4px; background: #f5f9fb; font-size: 9px; }
.map-body { display: grid; grid-template-columns: minmax(0, 1fr) 250px; min-height: 390px; }
.canvas-scroll { overflow: auto; border-right: 1px solid #e4e9ed; background: #f7f9fa; }
.map-canvas { position: relative; min-width: 100%; background-image: linear-gradient(#e6ecef 1px, transparent 1px), linear-gradient(90deg, #e6ecef 1px, transparent 1px); background-size: 24px 24px; }
.edge-layer { position: absolute; inset: 0; width: 100%; height: 100%; overflow: visible; pointer-events: none; }
.edge-layer path { fill: none; stroke: #aab7c0; stroke-width: 1; opacity: .65; }
.edge-layer path.contains { stroke-dasharray: 3 5; }
.edge-layer path.dependency { stroke: #5f8298; stroke-width: 1.35; }
.edge-layer path.runtime { stroke: #9a733f; stroke-width: 1.45; stroke-dasharray: 6 3; }
.edge-layer path.runtime.active { stroke: #08795f; stroke-dasharray: none; }
.edge-layer path.active { stroke: #08795f; stroke-width: 2.2; opacity: 1; }
.edge-layer marker path { fill: #78909f; stroke: none; }
.map-node { position: absolute; z-index: 2; display: grid; grid-template-columns: 30px minmax(0, 1fr) 7px; width: 154px; min-height: 54px; align-items: center; gap: 7px; padding: 7px 9px; text-align: left; border: 1px solid #cad5dc; border-radius: 6px; background: rgb(255 255 255 / 96%); box-shadow: 0 5px 13px rgb(40 61 75 / 7%); }
.map-node:hover, .map-node:focus-visible { border-color: #6f9cb8; outline: none; box-shadow: 0 0 0 3px rgb(70 132 168 / 12%); }
.map-node.selected { border-color: #08795f; box-shadow: 0 0 0 3px rgb(8 121 95 / 12%); }
.map-node.project { color: #fff; border-color: #274b60; background: #294b5c; }
.map-node.resource { border-color: #d6c29e; background: #fffaf0; }
.map-node.resource.selected { border-color: #08795f; }
.map-node[data-risk='HIGH'] { border-right: 4px solid #bd452f; }
.map-node[data-risk='MEDIUM'] { border-right: 4px solid #c4852d; }
.node-icon { display: grid; width: 28px; height: 28px; place-items: center; color: #315f7c; border-radius: 4px; background: #e7f0f5; }
.project .node-icon { color: #fff; background: rgb(255 255 255 / 13%); }
.resource .node-icon { color: #8a632d; background: #f3e7d2; }
.node-copy { display: grid; min-width: 0; gap: 3px; }
.node-copy strong { overflow: hidden; color: #344854; font: 600 10px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.project .node-copy strong { color: #fff; }
.node-copy small { overflow: hidden; color: #84919a; font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.project .node-copy small { color: #bed0da; }
.map-node i { width: 6px; height: 6px; border-radius: 50%; background: #bd452f; }
.group-label { position: absolute; top: 19px; color: #77858f; font: 700 9px Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
.resource-label { color: #8a632d; }
.selection-panel { display: flex; min-width: 0; flex-direction: column; background: #fff; }
.selection-title { display: grid; gap: 4px; padding: 16px; border-bottom: 1px solid #e6ebef; }
.selection-title span { color: #7d8991; font-size: 8px; }
.selection-title strong { overflow-wrap: anywhere; color: #2d414e; font: 600 12px Consolas, monospace; }
.selection-title small { color: #8b969d; font-size: 8px; }
.selection-section { padding: 13px 16px; border-bottom: 1px solid #e6ebef; }
.selection-section h3 { margin: 0 0 8px; color: #5a6973; font-size: 9px; }
.connection-list { display: grid; gap: 5px; }
.connection-list article { display: grid; grid-template-columns: 34px minmax(0, 1fr) 20px; gap: 5px; align-items: center; min-height: 28px; padding: 5px 6px; background: #f5f8fa; }
.connection-list span { color: #7b8992; font-size: 8px; }
.connection-list strong { overflow: hidden; color: #405560; font: 500 9px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.connection-list b { color: #08795f; font: 700 9px Consolas, monospace; text-align: right; }
.risks { display: grid; gap: 6px; }
.risks h3 { margin-bottom: 2px; }
.risks article { display: grid; grid-template-columns: 16px 1fr; gap: 6px; padding: 7px; color: #8d4b31; border-left: 2px solid #c4852d; background: #fbf5ec; }
.risks article[data-severity='HIGH'] { color: #9b3f31; border-color: #bd452f; background: #fbefed; }
.risks article div { display: grid; gap: 3px; }
.risks strong { font-size: 9px; }
.risks small { color: #796d68; font-size: 8px; line-height: 1.45; }
.empty-note { color: #949da3; font-size: 8px; line-height: 1.5; }
.open-module { display: flex; align-items: center; justify-content: center; gap: 5px; margin: auto 16px 16px; padding: 7px; color: #315f7c; border: 1px solid #cbd9e2; border-radius: 4px; background: #f6f9fb; font-size: 9px; }
.coverage { display: flex; align-items: center; gap: 10px; min-height: 34px; padding: 7px 16px; color: #76838c; border-top: 1px solid #e6ebef; background: #fafbfc; font-size: 8px; }
.coverage em { padding: 2px 5px; color: #8f5c26; border-radius: 3px; background: #f5e8d8; font-style: normal; }
.coverage small { margin-left: auto; color: #929ba1; font-size: 8px; }
@media (max-width: 900px) {
  .panel-head { align-items: flex-start; flex-direction: column; }
  .map-body { grid-template-columns: 1fr; }
  .canvas-scroll { border-right: 0; border-bottom: 1px solid #e4e9ed; }
  .selection-panel { min-height: 230px; }
  .coverage { align-items: flex-start; flex-direction: column; }
  .coverage small { margin-left: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .map-node { transition: none; }
}
</style>
