<script setup lang="ts">
import { computed } from 'vue'
import { Connection, Files, Share } from '@element-plus/icons-vue'
import { ElEmpty, ElIcon, ElTag } from 'element-plus'
import type { GraphChainDetail, GraphChainEdge, GraphChainNode } from '../../types'

interface PositionedNode extends GraphChainNode {
  x: number
  y: number
  width: number
  height: number
  layer: number
}

interface PositionedEdge extends GraphChainEdge {
  path: string
  labelX: number
  labelY: number
}

const props = defineProps<{
  busy: boolean
  detail: GraphChainDetail | null
}>()

const NODE_WIDTH = 220
const NODE_HEIGHT = 74
const LAYER_GAP = 286
const ROW_GAP = 26
const STAGE_PADDING = 36

const layout = computed(() => {
  const nodes = props.detail?.nodes ?? []
  const edges = props.detail?.edges ?? []
  const layerGroups = new Map<number, GraphChainNode[]>()
  nodes.forEach((node) => {
    const layer = resolveLayer(node)
    const group = layerGroups.get(layer) ?? []
    group.push(node)
    layerGroups.set(layer, group)
  })

  const positionedNodes: PositionedNode[] = []
  const sortedLayers = Array.from(layerGroups.keys()).sort((left, right) => left - right)
  let maxRows = 1
  sortedLayers.forEach((layer) => {
    const group = layerGroups.get(layer) ?? []
    maxRows = Math.max(maxRows, group.length)
    const groupHeight = group.length * NODE_HEIGHT + Math.max(0, group.length - 1) * ROW_GAP
    const yStart = STAGE_PADDING + Math.max(0, (canvasHeight(maxRows) - groupHeight - STAGE_PADDING * 2) / 2)
    group.forEach((node, index) => {
      positionedNodes.push({
        ...node,
        layer,
        x: STAGE_PADDING + layer * LAYER_GAP,
        y: yStart + index * (NODE_HEIGHT + ROW_GAP),
        width: NODE_WIDTH,
        height: NODE_HEIGHT
      })
    })
  })

  const nodeMap = new Map(positionedNodes.map((node) => [node.id, node]))
  const width = Math.max(760, STAGE_PADDING * 2 + NODE_WIDTH + Math.max(0, sortedLayers.length - 1) * LAYER_GAP)
  const height = canvasHeight(maxRows)
  const positionedEdges = edges
    .map((edge) => positionEdge(edge, nodeMap))
    .filter((edge): edge is PositionedEdge => Boolean(edge))

  return {
    nodes: positionedNodes,
    edges: positionedEdges,
    width,
    height
  }
})

const selectedChain = computed(() => props.detail?.chain ?? null)

function canvasHeight(maxRows: number) {
  return Math.max(420, STAGE_PADDING * 2 + maxRows * NODE_HEIGHT + Math.max(0, maxRows - 1) * ROW_GAP)
}

function resolveLayer(node: GraphChainNode) {
  if (node.role === 'project' || node.type === 'project' || node.type === 'knowledge_asset') return 0
  if (node.role === 'file' || node.type === 'file') return 1
  if (node.role === 'endpoint' || node.type === 'endpoint') return 2
  if (node.role === 'method' || node.type === 'method') return 3
  if (['call', 'sql', 'return', 'control', 'new', 'throw'].includes(node.type)) return 4
  return node.role === 'record' ? 2 : 4
}

function positionEdge(edge: GraphChainEdge, nodeMap: Map<string, PositionedNode>): PositionedEdge | null {
  const source = nodeMap.get(edge.source)
  const target = nodeMap.get(edge.target)
  if (!source || !target) return null
  const x1 = source.x + source.width
  const y1 = source.y + source.height / 2
  const x2 = target.x
  const y2 = target.y + target.height / 2
  const bend = Math.max(70, Math.abs(x2 - x1) / 2)
  return {
    ...edge,
    path: `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`,
    labelX: (x1 + x2) / 2,
    labelY: (y1 + y2) / 2 - 8
  }
}

function nodeStyle(node: PositionedNode) {
  return {
    height: `${node.height}px`,
    left: `${node.x}px`,
    top: `${node.y}px`,
    width: `${node.width}px`
  }
}

function nodeTypeLabel(node: GraphChainNode) {
  if (node.type === 'project') return '项目'
  if (node.type === 'knowledge_asset') return '知识资产'
  if (node.type === 'endpoint') return '接口'
  if (node.type === 'method') return '方法'
  if (node.type === 'file') return '文件'
  if (node.type === 'record') return '记录'
  if (node.type === 'sql') return 'SQL'
  if (node.type === 'call') return '调用'
  if (node.type === 'return') return '返回'
  return node.type || '节点'
}

function lineLabel(node: GraphChainNode) {
  if (node.startLine && node.endLine && node.endLine !== node.startLine) return `L${node.startLine}-${node.endLine}`
  if (node.startLine) return `L${node.startLine}`
  return ''
}
</script>

<template>
  <section class="canvas-panel">
    <header class="canvas-header">
      <div class="canvas-title">
        <ElIcon><Share /></ElIcon>
        <div>
          <h3>{{ selectedChain?.title || '暂无可展示链路' }}</h3>
          <span>{{ selectedChain?.subtitle || '筛选条件下没有可展示的图谱链路。' }}</span>
        </div>
      </div>
    </header>

    <div v-if="detail && detail.nodes.length" class="canvas-body">
      <div class="stage-wrap">
        <div class="graph-stage" :style="{ width: `${layout.width}px`, height: `${layout.height}px` }">
          <svg class="edge-layer" :viewBox="`0 0 ${layout.width} ${layout.height}`" aria-hidden="true">
            <defs>
              <marker id="graph-arrow" markerHeight="8" markerWidth="8" orient="auto" refX="7" refY="4">
                <path d="M 0 0 L 8 4 L 0 8 z" />
              </marker>
            </defs>
            <g>
              <path
                v-for="edge in layout.edges"
                :key="edge.id"
                class="edge-path"
                :class="edge.type.toLowerCase()"
                :d="edge.path"
                marker-end="url(#graph-arrow)"
              />
            </g>
            <g>
              <text
                v-for="edge in layout.edges"
                :key="`${edge.id}-label`"
                class="edge-label"
                :x="edge.labelX"
                :y="edge.labelY"
                text-anchor="middle"
              >
                {{ edge.type }}
              </text>
            </g>
          </svg>

          <article
            v-for="node in layout.nodes"
            :key="node.id"
            class="graph-node"
            :class="[node.type, node.role]"
            :style="nodeStyle(node)"
          >
            <header>
              <span class="node-icon">
                <ElIcon v-if="node.type === 'file'"><Files /></ElIcon>
                <ElIcon v-else-if="node.type === 'endpoint'"><Share /></ElIcon>
                <ElIcon v-else><Connection /></ElIcon>
              </span>
              <strong>{{ node.label || node.id }}</strong>
            </header>
            <p>{{ node.subtitle || node.filePath || node.id }}</p>
            <footer>
              <ElTag size="small" effect="plain">{{ nodeTypeLabel(node) }}</ElTag>
              <span v-if="lineLabel(node)">{{ lineLabel(node) }}</span>
            </footer>
          </article>
        </div>
      </div>
    </div>

    <ElEmpty v-else class="canvas-empty" :description="busy ? '图谱加载中。' : '暂无可展示链路。'" />
  </section>
</template>

<style scoped>
.canvas-panel {
  background: #f7faf9;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
}

.canvas-header {
  align-items: center;
  background: #ffffff;
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 14px 18px;
}

.canvas-title {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.canvas-title > .el-icon {
  align-items: center;
  background: #eef5fb;
  border-radius: 8px;
  color: #315f8a;
  display: inline-flex;
  flex: 0 0 36px;
  height: 36px;
  justify-content: center;
  width: 36px;
}

.canvas-title h3 {
  color: var(--text);
  font-size: 0.95rem;
  line-height: 1.2;
  margin: 0;
  max-width: 720px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.canvas-title span {
  color: var(--text-faint);
  display: block;
  font-size: 0.72rem;
  margin-top: 2px;
  max-width: 780px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.canvas-body {
  min-height: 0;
  overflow: hidden;
  padding: 18px;
}

.stage-wrap {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  height: 100%;
  min-height: 0;
  overflow: auto;
}

.graph-stage {
  background-image:
    linear-gradient(#edf2f0 1px, transparent 1px),
    linear-gradient(90deg, #edf2f0 1px, transparent 1px);
  background-size: 28px 28px;
  position: relative;
}

.edge-layer {
  height: 100%;
  left: 0;
  overflow: visible;
  position: absolute;
  top: 0;
  width: 100%;
}

.edge-layer marker path {
  fill: #55707c;
}

.edge-path {
  fill: none;
  stroke: #55707c;
  stroke-linecap: round;
  stroke-width: 2;
}

.edge-path.handles {
  stroke: #08745f;
}

.edge-path.owns_behavior {
  stroke: #8a6400;
}

.edge-label {
  fill: #48606b;
  font-size: 11px;
  font-weight: 700;
  paint-order: stroke;
  stroke: #ffffff;
  stroke-width: 4px;
}

.graph-node {
  background: #ffffff;
  border: 1px solid #cfdbe0;
  border-radius: 8px;
  box-shadow: 0 8px 20px rgba(35, 49, 55, 0.08);
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  overflow: hidden;
  padding: 9px;
  position: absolute;
}

.graph-node.endpoint {
  border-color: #81b9aa;
}

.graph-node.method {
  border-color: #91acd1;
}

.graph-node.file {
  border-color: #d8b970;
}

.graph-node header {
  align-items: center;
  display: flex;
  gap: 7px;
  min-width: 0;
}

.node-icon {
  align-items: center;
  background: #eef5fb;
  border-radius: 7px;
  color: #315f8a;
  display: inline-flex;
  flex: 0 0 24px;
  height: 24px;
  justify-content: center;
  width: 24px;
}

.graph-node.endpoint .node-icon {
  background: #e8f5f1;
  color: #08745f;
}

.graph-node.file .node-icon {
  background: #fff5e5;
  color: #9a5f00;
}

.graph-node strong {
  color: var(--text);
  font-size: 0.78rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.graph-node p {
  color: var(--text-faint);
  font-size: 0.68rem;
  line-height: 1.35;
  margin: 5px 0 0;
  min-width: 0;
  overflow: hidden;
}

.graph-node footer {
  align-items: center;
  color: #52616b;
  display: flex;
  font-size: 0.68rem;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
}

.canvas-empty {
  align-self: start;
  padding-top: 88px;
}

@media (max-width: 760px) {
  .canvas-header {
    align-items: stretch;
    flex-direction: column;
  }

  .canvas-body {
    padding: 12px;
  }
}
</style>
