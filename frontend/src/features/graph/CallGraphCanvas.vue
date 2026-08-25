<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import { Connection, Rank } from '@element-plus/icons-vue';
import type { GraphResult } from '@/api/intelligence';
import {
  CALL_GRAPH_NODE_HEIGHT,
  CALL_GRAPH_NODE_WIDTH,
  layoutCallGraph,
  type PositionedGraphNode,
} from './callGraphLayout';

const props = defineProps<{ result: GraphResult | null }>();
const activeSymbol = shallowRef<string | null>(null);
const layout = computed(() => layoutCallGraph(props.result?.nodes ?? []));
const nodeBySymbol = computed(
  () => new Map(layout.value.nodes.map((node) => [node.symbol, node])),
);

type RenderedEdge = GraphResult['edges'][number] & {
  key: string;
  path: string;
  labelX: number;
  labelY: number;
};

function edgeGeometry(source: PositionedGraphNode, target: PositionedGraphNode) {
  const sourceCenterY = source.y + CALL_GRAPH_NODE_HEIGHT / 2;
  const targetCenterY = target.y + CALL_GRAPH_NODE_HEIGHT / 2;

  if (Math.abs(source.x - target.x) < 2) {
    const anchorX = source.x + CALL_GRAPH_NODE_WIDTH;
    const curveX = anchorX + 86;
    return {
      path: `M ${anchorX} ${sourceCenterY} C ${curveX} ${sourceCenterY}, ${curveX} ${targetCenterY}, ${anchorX} ${targetCenterY}`,
      labelX: curveX + 4,
      labelY: (sourceCenterY + targetCenterY) / 2 - 6,
    };
  }

  const movesRight = target.x > source.x;
  const sourceX = movesRight ? source.x + CALL_GRAPH_NODE_WIDTH : source.x;
  const targetX = movesRight ? target.x : target.x + CALL_GRAPH_NODE_WIDTH;
  const direction = movesRight ? 1 : -1;
  const curve = Math.max(64, Math.abs(targetX - sourceX) * 0.42);
  return {
    path: `M ${sourceX} ${sourceCenterY} C ${sourceX + direction * curve} ${sourceCenterY}, ${targetX - direction * curve} ${targetCenterY}, ${targetX} ${targetCenterY}`,
    labelX: sourceX + (targetX - sourceX) * 0.68,
    labelY: sourceCenterY + (targetCenterY - sourceCenterY) * 0.68 - 7,
  };
}

const renderedEdges = computed<RenderedEdge[]>(() => {
  if (!props.result) return [];
  return props.result.edges.flatMap((edge, index) => {
    const source = nodeBySymbol.value.get(edge.source);
    const target = nodeBySymbol.value.get(edge.target);
    if (!source || !target) return [];
    return [{
      ...edge,
      ...edgeGeometry(source, target),
      key: `${edge.source}|${edge.target}|${edge.relation}|${index}`,
    }];
  });
});

function isEdgeActive(edge: RenderedEdge) {
  return activeSymbol.value === edge.source || activeSymbol.value === edge.target;
}
</script>

<template>
  <section class="call-graph-shell" aria-label="调用关系图">
    <header class="call-graph-header">
      <div class="call-graph-title">
        <el-icon><Rank /></el-icon>
        <span>调用路径画布</span>
      </div>
      <div v-if="result" class="call-graph-stats">
        <span>{{ layout.nodes.length }} 个节点</span>
        <span>{{ renderedEdges.length }} 条关系</span>
        <span class="scroll-hint">滚动查看完整画布</span>
      </div>
    </header>

    <div v-if="layout.nodes.length" class="call-graph-viewport">
      <div class="call-graph-stage" :style="{ width: `${layout.width}px`, height: `${layout.height}px` }">
        <svg class="call-graph-edges" :viewBox="`0 0 ${layout.width} ${layout.height}`" aria-label="节点连线">
          <defs>
            <marker id="call-graph-arrow" viewBox="0 0 10 10" refX="9" refY="5"
              markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" />
            </marker>
            <marker id="call-graph-arrow-active" viewBox="0 0 10 10" refX="9" refY="5"
              markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" />
            </marker>
          </defs>
          <g v-for="edge in renderedEdges" :key="edge.key"
            :class="['call-edge', { active: isEdgeActive(edge) }]">
            <path :d="edge.path" :marker-end="isEdgeActive(edge)
              ? 'url(#call-graph-arrow-active)'
              : 'url(#call-graph-arrow)'">
              <title>{{ edge.source }} → {{ edge.target }} · {{ edge.relation }}</title>
            </path>
            <text :x="edge.labelX" :y="edge.labelY">{{ edge.relation }}</text>
          </g>
        </svg>

        <button v-for="node in layout.nodes" :key="node.symbol" type="button"
          :class="['call-node', { focus: node.focus, active: activeSymbol === node.symbol }]"
          :style="{ left: `${node.x}px`, top: `${node.y}px` }" :title="node.symbol"
          @mouseenter="activeSymbol = node.symbol" @mouseleave="activeSymbol = null"
          @focus="activeSymbol = node.symbol" @blur="activeSymbol = null">
          <span class="call-node-kind">
            <el-icon><Connection /></el-icon>
            {{ node.focus ? '目标符号' : `深度 ${node.depth}` }}
          </span>
          <strong>{{ node.symbol }}</strong>
        </button>
      </div>
    </div>

    <el-empty v-else class="call-graph-empty" description="构建 CodeGraph 后输入符号进行分析" />
  </section>
</template>

<style scoped>
.call-graph-shell {
  display: grid;
  grid-template-rows: 44px minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid #dedee3;
  border-radius: 7px 0 0 7px;
  background: #fafbfc;
}

.call-graph-header {
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 14px;
  border-bottom: 1px solid #e4e7eb;
  background: rgba(255, 255, 255, 0.96);
}

.call-graph-title,
.call-graph-stats {
  display: flex;
  align-items: center;
}

.call-graph-title {
  gap: 7px;
  color: #26262a;
  font-size: 12px;
  font-weight: 650;
}

.call-graph-title .el-icon { color: #0066cc; font-size: 15px; }
.call-graph-stats { gap: 12px; color: #71717a; font-size: 11px; }
.scroll-hint { padding-left: 12px; border-left: 1px solid #dedee3; color: #0066cc; }

.call-graph-viewport {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.call-graph-stage {
  position: relative;
  min-width: 100%;
  min-height: 100%;
  background-color: #fafbfc;
  background-image: radial-gradient(#d7dde4 1px, transparent 1px);
  background-size: 20px 20px;
}

.call-graph-edges {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
  pointer-events: none;
}

.call-edge { color: #8496a8; transition: color 160ms ease; }
.call-edge path {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.35;
  opacity: 0.72;
  vector-effect: non-scaling-stroke;
}
.call-edge text {
  fill: #667789;
  stroke: #fafbfc;
  stroke-width: 4px;
  paint-order: stroke;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0.02em;
}
.call-edge.active { color: #0066cc; }
.call-edge.active path { stroke-width: 2.25; opacity: 1; }
.call-edge.active text { fill: #005eb8; font-weight: 700; }

.call-node {
  position: absolute;
  z-index: 1;
  display: grid;
  align-content: center;
  gap: 7px;
  width: 244px;
  height: 72px;
  padding: 10px 13px;
  overflow: hidden;
  text-align: left;
  color: #242428;
  border: 1px solid #bfc8d1;
  border-left: 3px solid #8292a3;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(32, 47, 61, 0.08);
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}
.call-node:hover,
.call-node:focus-visible,
.call-node.active {
  z-index: 2;
  border-color: #5796d2;
  outline: none;
  box-shadow: 0 8px 22px rgba(0, 76, 153, 0.16);
  transform: translateY(-2px);
}
.call-node.focus {
  border-color: #0066cc;
  border-left-color: #0066cc;
  background: linear-gradient(135deg, #ffffff 0%, #edf6ff 100%);
  box-shadow: 0 0 0 2px rgba(0, 102, 204, 0.12), 0 7px 20px rgba(0, 77, 153, 0.12);
}
.call-node-kind {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--app-text-muted);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.07em;
  text-transform: uppercase;
}
.call-node.focus .call-node-kind { color: #0066cc; }
.call-node strong {
  display: block;
  overflow: hidden;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.call-graph-empty { min-height: 360px; }

@media (max-width: 760px) {
  .call-graph-shell { height: 620px; border-radius: 7px 7px 0 0; }
  .scroll-hint { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .call-node,
  .call-edge { transition: none; }
}
</style>
