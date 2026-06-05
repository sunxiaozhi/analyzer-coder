<script setup lang="ts">
import { computed } from 'vue'
import { ElEmpty } from 'element-plus'

type NodeKind = 'endpoint' | 'method' | 'sql' | 'table' | 'default'

interface GraphNode {
  id: string
  label: string
  kind: NodeKind
  layer: number
  x: number
  y: number
}

interface GraphEdge {
  from: string
  to: string
  label: string
}

interface ParsedGraph {
  nodes: GraphNode[]
  edges: GraphEdge[]
  warnings: string[]
}

const props = defineProps<{
  code: string
}>()

const NODE_WIDTH = 220
const NODE_HEIGHT = 58
const LAYER_GAP = 96
const ROW_GAP = 32
const PADDING = 36
const MAX_RENDER_NODES = 260
const MAX_RENDER_EDGES = 420

const kindOrder: Record<NodeKind, number> = {
  endpoint: 0,
  method: 1,
  sql: 2,
  table: 3,
  default: 1
}

const parsedGraph = computed(() => parseMermaidGraph(props.code))

const visibleNodes = computed(() => parsedGraph.value.nodes.slice(0, MAX_RENDER_NODES))
const visibleNodeIds = computed(() => new Set(visibleNodes.value.map((node) => node.id)))
const visibleEdges = computed(() =>
  parsedGraph.value.edges
    .filter((edge) => visibleNodeIds.value.has(edge.from) && visibleNodeIds.value.has(edge.to))
    .slice(0, MAX_RENDER_EDGES)
)

const graphSize = computed(() => {
  const maxX = Math.max(0, ...visibleNodes.value.map((node) => node.x + NODE_WIDTH))
  const maxY = Math.max(0, ...visibleNodes.value.map((node) => node.y + NODE_HEIGHT))
  return {
    width: maxX + PADDING,
    height: maxY + PADDING
  }
})

const omittedLabel = computed(() => {
  const hiddenNodes = parsedGraph.value.nodes.length - visibleNodes.value.length
  const hiddenEdges = parsedGraph.value.edges.length - visibleEdges.value.length
  const parts = []
  if (hiddenNodes > 0) parts.push(`${hiddenNodes} 个节点`)
  if (hiddenEdges > 0) parts.push(`${hiddenEdges} 条边`)
  return parts.length ? `已折叠 ${parts.join('、')}` : ''
})

function edgePath(edge: GraphEdge): string {
  const from = visibleNodes.value.find((node) => node.id === edge.from)
  const to = visibleNodes.value.find((node) => node.id === edge.to)
  if (!from || !to) return ''
  const startX = from.x + NODE_WIDTH
  const startY = from.y + NODE_HEIGHT / 2
  const endX = to.x
  const endY = to.y + NODE_HEIGHT / 2
  const midX = startX + Math.max(32, (endX - startX) / 2)
  return `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}`
}

function edgeLabelPosition(edge: GraphEdge) {
  const from = visibleNodes.value.find((node) => node.id === edge.from)
  const to = visibleNodes.value.find((node) => node.id === edge.to)
  if (!from || !to) return { x: 0, y: 0 }
  return {
    x: (from.x + NODE_WIDTH + to.x) / 2,
    y: (from.y + to.y) / 2 + NODE_HEIGHT / 2 - 8
  }
}

function parseMermaidGraph(code: string): ParsedGraph {
  const nodeMap = new Map<string, Omit<GraphNode, 'layer' | 'x' | 'y'>>()
  const edges: GraphEdge[] = []
  const warnings: string[] = []

  for (const rawLine of code.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('flowchart ') || line.startsWith('classDef ') || line.startsWith('subgraph ') || line === 'end') {
      continue
    }

    const nodeMatch = line.match(/^([A-Za-z0-9_]+)\["((?:\\.|[^"])*)"\](?:::+([A-Za-z0-9_]+))?/)
    if (nodeMatch) {
      const [, id, rawLabel, rawKind] = nodeMatch
      nodeMap.set(id, {
        id,
        label: normalizeLabel(rawLabel),
        kind: normalizeKind(rawKind)
      })
      continue
    }

    const edgeMatch = line.match(/^([A-Za-z0-9_]+)\s+-->(?:\|([^|]+)\|)?\s+([A-Za-z0-9_]+)/)
    if (edgeMatch) {
      const [, from, label = '', to] = edgeMatch
      edges.push({ from, to, label })
    }
  }

  for (const edge of edges) {
    if (!nodeMap.has(edge.from)) {
      nodeMap.set(edge.from, {
        id: edge.from,
        label: edge.from,
        kind: inferKind(edge.from)
      })
    }
    if (!nodeMap.has(edge.to)) {
      nodeMap.set(edge.to, {
        id: edge.to,
        label: edge.to,
        kind: inferKind(edge.to)
      })
    }
  }

  if (!nodeMap.size && code.trim()) {
    warnings.push('未识别到可绘制的调用链节点。')
  }

  return layoutGraph(Array.from(nodeMap.values()), edges, warnings)
}

function layoutGraph(
  rawNodes: Array<Omit<GraphNode, 'layer' | 'x' | 'y'>>,
  edges: GraphEdge[],
  warnings: string[]
): ParsedGraph {
  const incoming = new Map<string, number>()
  const outgoing = new Map<string, string[]>()
  for (const node of rawNodes) {
    incoming.set(node.id, 0)
    outgoing.set(node.id, [])
  }

  for (const edge of edges) {
    outgoing.get(edge.from)?.push(edge.to)
    incoming.set(edge.to, (incoming.get(edge.to) ?? 0) + 1)
  }

  const layerById = new Map<string, number>()
  for (const node of rawNodes) {
    layerById.set(node.id, kindOrder[node.kind])
  }

  const queue = rawNodes
    .filter((node) => (incoming.get(node.id) ?? 0) === 0)
    .sort((a, b) => kindOrder[a.kind] - kindOrder[b.kind] || a.label.localeCompare(b.label))

  for (const start of queue) {
    layerById.set(start.id, Math.min(layerById.get(start.id) ?? 0, kindOrder[start.kind]))
  }

  const visitedCount = new Map<string, number>()
  while (queue.length) {
    const current = queue.shift()
    if (!current) break
    const nextLayer = (layerById.get(current.id) ?? 0) + 1
    for (const nextId of outgoing.get(current.id) ?? []) {
      const nextNode = rawNodes.find((node) => node.id === nextId)
      const preferredLayer = nextNode ? kindOrder[nextNode.kind] : nextLayer
      layerById.set(nextId, Math.max(layerById.get(nextId) ?? 0, Math.max(nextLayer, preferredLayer)))
      const count = (visitedCount.get(nextId) ?? 0) + 1
      visitedCount.set(nextId, count)
      if (count <= (incoming.get(nextId) ?? 0)) {
        const nextRaw = rawNodes.find((node) => node.id === nextId)
        if (nextRaw) queue.push(nextRaw)
      }
    }
  }

  const groups = new Map<number, Array<Omit<GraphNode, 'layer' | 'x' | 'y'>>>()
  for (const node of rawNodes) {
    const layer = layerById.get(node.id) ?? kindOrder[node.kind]
    if (!groups.has(layer)) groups.set(layer, [])
    groups.get(layer)?.push(node)
  }

  const nodes: GraphNode[] = []
  for (const [layer, items] of groups) {
    items.sort((a, b) => kindOrder[a.kind] - kindOrder[b.kind] || a.label.localeCompare(b.label))
    items.forEach((node, index) => {
      nodes.push({
        ...node,
        layer,
        x: PADDING + layer * (NODE_WIDTH + LAYER_GAP),
        y: PADDING + index * (NODE_HEIGHT + ROW_GAP)
      })
    })
  }

  nodes.sort((a, b) => a.layer - b.layer || a.y - b.y)
  return { nodes, edges, warnings }
}

function normalizeLabel(label: string): string {
  return label
    .replace(/\\n/g, '\n')
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, '\\')
}

function normalizeKind(kind?: string): NodeKind {
  if (kind === 'endpoint' || kind === 'method' || kind === 'sql' || kind === 'table') return kind
  return 'default'
}

function inferKind(id: string): NodeKind {
  if (id.startsWith('endpoint_')) return 'endpoint'
  if (id.startsWith('method_')) return 'method'
  if (id.startsWith('sql_')) return 'sql'
  if (id.startsWith('table_')) return 'table'
  return 'default'
}

function shortLabel(label: string): string {
  const compact = label.replace(/\s+/g, ' ').trim()
  if (compact.length <= 48) return compact
  return `${compact.slice(0, 45)}...`
}

function kindLabel(kind: NodeKind): string {
  return {
    endpoint: '接口',
    method: '方法',
    sql: 'SQL',
    table: '数据表',
    default: '节点'
  }[kind]
}
</script>

<template>
  <div class="call-chain-graph">
    <ElEmpty v-if="!parsedGraph.nodes.length" :description="parsedGraph.warnings[0] || '暂无可展示的调用链。'" />
    <template v-else>
      <div class="graph-toolbar">
        <span>节点 {{ parsedGraph.nodes.length }}</span>
        <span>连线 {{ parsedGraph.edges.length }}</span>
        <span v-if="omittedLabel">{{ omittedLabel }}</span>
      </div>
      <svg
        class="graph-canvas"
        :width="graphSize.width"
        :height="graphSize.height"
        :viewBox="`0 0 ${graphSize.width} ${graphSize.height}`"
        role="img"
        aria-label="调用链图"
      >
        <defs>
          <marker id="call-chain-arrow" markerHeight="8" markerWidth="8" orient="auto" refX="7" refY="4">
            <path d="M 0 0 L 8 4 L 0 8 z" class="edge-arrow" />
          </marker>
        </defs>

        <g class="graph-edges">
          <g v-for="edge in visibleEdges" :key="`${edge.from}-${edge.label}-${edge.to}`">
            <path class="edge-path" :d="edgePath(edge)" marker-end="url(#call-chain-arrow)" />
            <text v-if="edge.label" class="edge-label" :x="edgeLabelPosition(edge).x" :y="edgeLabelPosition(edge).y">
              {{ edge.label }}
            </text>
          </g>
        </g>

        <g class="graph-nodes">
          <g
            v-for="node in visibleNodes"
            :key="node.id"
            class="graph-node"
            :class="`graph-node-${node.kind}`"
            :transform="`translate(${node.x}, ${node.y})`"
          >
            <title>{{ node.label }}</title>
            <rect class="node-box" :width="NODE_WIDTH" :height="NODE_HEIGHT" rx="8" />
            <text class="node-kind" x="12" y="19">{{ kindLabel(node.kind) }}</text>
            <text class="node-label" x="12" y="40">{{ shortLabel(node.label) }}</text>
          </g>
        </g>
      </svg>
    </template>
  </div>
</template>

<style scoped>
.call-chain-graph {
  background: #ffffff;
  min-height: 100%;
  min-width: 100%;
  overflow: auto;
}

.graph-toolbar {
  align-items: center;
  background: #f7fafc;
  border-bottom: 1px solid #d8e0e8;
  color: #5f7180;
  display: flex;
  flex-wrap: wrap;
  font-size: 0.78rem;
  gap: 12px;
  padding: 9px 12px;
  position: sticky;
  top: 0;
  z-index: 1;
}

.graph-canvas {
  display: block;
  min-height: 100%;
}

.edge-path {
  fill: none;
  stroke: #9aa9b5;
  stroke-width: 1.4;
}

.edge-arrow {
  fill: #9aa9b5;
}

.edge-label {
  fill: #70808d;
  font-size: 11px;
  paint-order: stroke;
  stroke: #ffffff;
  stroke-width: 4px;
  text-anchor: middle;
}

.node-box {
  fill: #f8fafc;
  stroke: #cfd9e2;
  stroke-width: 1.2;
}

.node-kind {
  fill: #758494;
  font-size: 10px;
  font-weight: 760;
  text-transform: uppercase;
}

.node-label {
  fill: #20313f;
  font-size: 12px;
  font-weight: 700;
}

.graph-node-endpoint .node-box {
  fill: #e8f3ff;
  stroke: #2f73b7;
}

.graph-node-method .node-box {
  fill: #f2f0ff;
  stroke: #6d5bd0;
}

.graph-node-sql .node-box {
  fill: #fff4df;
  stroke: #b7791f;
}

.graph-node-table .node-box {
  fill: #f7ecff;
  stroke: #8b5bb7;
}
</style>
