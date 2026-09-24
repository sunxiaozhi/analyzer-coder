<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue';
import type { AtlasNode, AtlasEdge } from '@/api/codeAtlas';
import { atlasEdgeKey } from '@/api/codeAtlas';
import { atlasRegions, layoutAtlas } from './atlasLayout';
const props = defineProps<{ nodes: AtlasNode[]; edges: AtlasEdge[]; selectedId?: string; connected: Set<string>; highlighted: Set<string> }>();
const emit = defineEmits<{ select: [node: AtlasNode]; expand: [node: AtlasNode]; edge: [edge: AtlasEdge] }>();
const zoom = ref(1), pan = ref({ x: 0, y: 0 });
const host = ref<SVGSVGElement>();
const viewport = ref({ width: 1200, height: 800 });
let observer: ResizeObserver | undefined;
onMounted(() => {
  if (typeof ResizeObserver === 'undefined') return;
  observer = new ResizeObserver(() => { const rect = host.value?.getBoundingClientRect(); if (rect?.width && rect.height) { viewport.value = { width: rect.width, height: rect.height }; if (!props.selectedId) home(); } });
  if (host.value) observer.observe(host.value);
});
onBeforeUnmount(() => observer?.disconnect());
const nodes = computed(() => layoutAtlas(props.nodes, props.edges));
const modules = computed(() => atlasRegions(nodes.value));
const files = computed(() => atlasRegions(nodes.value, true));
const byId = computed(() => new Map(nodes.value.map(n => [n.id, n])));
const edges = computed(() => props.edges.flatMap(edge => {
  const a = byId.value.get(edge.source), b = byId.value.get(edge.target); if (!a || !b) return [];
  const dx = b.x - a.x, dy = b.y - a.y, length = Math.hypot(dx, dy) || 1;
  const bend = 20 + (edge.kind.length % 4) * 9;
  const mx = (a.x + b.x) / 2 - dy / length * bend, my = (a.y + b.y) / 2 + dx / length * bend;
  const path = a.id === b.id ? `M${a.x},${a.y - a.radius} C${a.x + 70},${a.y - 75} ${a.x + 80},${a.y + 60} ${a.x + a.radius},${a.y}`
    : `M${a.x + dx / length * a.radius},${a.y + dy / length * a.radius} Q${mx},${my} ${b.x - dx / length * (b.radius + 7)},${b.y - dy / length * (b.radius + 7)}`;
  return [{ ...edge, key: atlasEdgeKey(edge), path, mx, my,
    active: props.highlighted.has(atlasEdgeKey(edge)) || edge.source === props.selectedId || edge.target === props.selectedId }];
}));
let dragging: { x: number; y: number; px: number; py: number } | null = null;
function home() {
  const extent = nodes.value.reduce((max, n) => Math.max(max, (Math.abs(n.x - 600) + 150) / Math.max(120, viewport.value.width / 2 - 35), (Math.abs(n.y - 400) + 120) / Math.max(100, viewport.value.height / 2 - 45)), 1);
  zoom.value = Math.max(.03, 1 / extent); pan.value = { x: 0, y: 0 };
}
function zoomBy(factor: number) { zoom.value = Math.min(4, Math.max(.03, zoom.value / factor)); }
function pointerDown(event: PointerEvent) {
  if (event.button !== 0 || (event.target as Element).closest('[data-node], [data-edge], .screen-label')) return;
  (event.currentTarget as SVGSVGElement).setPointerCapture(event.pointerId);
  dragging = { x: event.clientX, y: event.clientY, px: pan.value.x, py: pan.value.y };
}
function pointerMove(event: PointerEvent) {
  if (!dragging) return;
  const rect = (event.currentTarget as SVGSVGElement).getBoundingClientRect();
  const factor = Math.max(viewport.value.width / rect.width, viewport.value.height / rect.height);
  pan.value = { x: dragging.px + (event.clientX - dragging.x) * factor, y: dragging.py + (event.clientY - dragging.y) * factor };
}
const screenLabels = computed(() => {
  const used: { x: number; y: number }[] = [];
  const priority = (id: string) => id === props.selectedId ? 2 : props.connected.has(id) ? 1 : 0;
  return [...nodes.value].sort((a, b) => priority(b.id) - priority(a.id)).flatMap(node => {
    if (used.length >= 80 || (props.selectedId && !props.connected.has(node.id))) return [];
    const x = viewport.value.width / 2 + pan.value.x + (node.x - 600) * zoom.value;
    const baseY = viewport.value.height / 2 + pan.value.y + (node.y - 400) * zoom.value;
    for (const y of [baseY + Math.max(8, node.radius * zoom.value) + 8, baseY - 52]) {
      if (x < 90 || x > viewport.value.width - 90 || y < 8 || y + 38 > viewport.value.height - (props.selectedId ? 140 : 20)) continue;
      if (used.some(box => Math.abs(box.x - x) < 180 && Math.abs(box.y - y) < 40)) continue;
      used.push({ x, y }); return [{ node, x, y }];
    }
    return [];
  });
});
watch(() => props.nodes, (next, previous) => {
  if (!previous?.length) { home(); return; }
  const old = layoutAtlas(previous, props.edges).find(n => n.id === props.selectedId);
  const current = nodes.value.find(n => n.id === props.selectedId);
  if (old && current) pan.value = { x: pan.value.x + (old.x - current.x) * zoom.value, y: pan.value.y + (old.y - current.y) * zoom.value };
}, { immediate: true });
watch(() => props.selectedId, id => {
  const node = id && byId.value.get(id); if (!node) return;
  zoom.value = Math.max(.85, zoom.value);
  pan.value = { x: (600 - node.x) * zoom.value, y: (360 - node.y) * zoom.value };
});
defineExpose({ home, zoomBy });
</script>
<template>
  <svg ref="host" class="atlas-svg" :viewBox="`0 0 ${viewport.width} ${viewport.height}`" aria-label="代码关系图：模块、文件与符号；点击节点展开关联，点击连线查看出处" @wheel.prevent="zoomBy($event.deltaY > 0 ? 1.12 : .88)" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="dragging = null" @pointercancel="dragging = null">
    <defs>
      <radialGradient id="atlas-sphere" cx="28%" cy="22%" r="80%"><stop offset="0" stop-color="#fff" stop-opacity=".85"/><stop offset=".5" stop-color="#fff" stop-opacity=".08"/><stop offset="1" stop-color="#17324d" stop-opacity=".35"/></radialGradient>
      <marker id="atlas-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0 0 10 5 0 10Z" fill="context-stroke"/></marker>
    </defs>
    <g :transform="`translate(${viewport.width / 2 + pan.x},${viewport.height / 2 + pan.y}) scale(${zoom}) translate(-600,-400)`">
      <g v-for="group in modules" :key="group.key" class="module-region"><rect :x="group.x - 10" :y="group.y" :width="group.width + 20" :height="group.height" rx="20"/><text :x="group.x + 10" :y="group.y + 27">{{ group.label }}</text></g>
      <g v-for="file in files" :key="file.key" class="file-region"><rect :x="file.x" :y="file.y" :width="file.width" :height="file.height" rx="10"/><text v-if="zoom > .27" :x="file.x + 13" :y="file.y + 21">{{ file.label }}<title>{{ file.key }}</title></text></g>
      <g v-for="edge in edges" :key="edge.key" data-edge class="atlas-link" :class="{ active: edge.active, dim: selectedId && !edge.active, traced: highlighted.has(edge.key) }" tabindex="0" role="button" :aria-label="`${byId.get(edge.source)?.label} ${edge.kind} ${byId.get(edge.target)?.label}`" @click.stop="emit('edge', edge)" @keydown.enter.prevent="emit('edge', edge)">
        <path :d="edge.path" class="edge-hit"/><path :d="edge.path" class="edge-line" marker-end="url(#atlas-arrow)" :stroke-dasharray="/inherit|extend|implement/i.test(edge.kind) ? '8 5' : undefined"/>
        <text v-if="edge.active && zoom > .45" :x="edge.mx" :y="edge.my - 7" text-anchor="middle" class="edge-label">{{ edge.kind }}{{ edge.count > 1 ? ` ×${edge.count}` : '' }}</text>
        <title>{{ edge.kind }} · {{ edge.count }} 处{{ edge.sourceLines?.length ? ` · L${edge.sourceLines.join(', L')}` : '' }}</title>
      </g>
      <g v-for="node in nodes" :key="node.id" data-node class="atlas-node" :class="{ selected: selectedId === node.id, dim: selectedId && !connected.has(node.id) }" :transform="`translate(${node.x},${node.y})`" :style="{ color: node.color }" tabindex="0" role="button" :aria-label="`${node.label} · ${node.kind} · ${node.filePath}`" @click="emit('select', node)" @dblclick="emit('expand', node)" @keydown.enter.prevent="emit('select', node)" @keydown.space.prevent="emit('expand', node)">
        <title>{{ node.qualifiedName || node.label }} · {{ node.kind }} · {{ node.filePath }}:{{ node.startLine }}</title>
        <circle :r="node.radius + 10" class="node-halo"/><circle :r="node.radius" fill="currentColor"/><circle :r="node.radius" fill="url(#atlas-sphere)"/>
        <circle v-if="selectedId === node.id" :r="node.radius + 7" class="focus-ring"/>

      </g>
    </g>
    <g v-for="label in screenLabels" :key="label.node.id" class="screen-label" :class="{ selected: selectedId === label.node.id }" :transform="`translate(${label.x},${label.y})`" @click="emit('select', label.node)" @dblclick="emit('expand', label.node)">
      <rect x="-87" y="0" width="174" height="36" rx="5"/><text y="15" text-anchor="middle">{{ label.node.label.length > 23 ? label.node.label.slice(0, 22) + '…' : label.node.label }}</text><text y="29" class="label-kind" text-anchor="middle">{{ label.node.kind }} · L{{ label.node.startLine || '—' }}</text>
    </g>
  </svg>
</template>
<style scoped>
.screen-label{cursor:pointer}.screen-label rect{fill:#ffffffef;stroke:#d8e2e8}.screen-label text{fill:#213c51;font:12px Consolas,"Microsoft YaHei",monospace}.screen-label .label-kind{fill:#72899b;font-size:10px}.screen-label.selected rect{fill:#edf5fe;stroke:#2f7fd3}.atlas-svg{width:100%;height:100%;display:block;touch-action:none;user-select:none}.module-region rect{fill:#eaf0f633;stroke:#bacbd9;stroke-width:1}.module-region text{fill:#526c80;font:600 16px "Segoe UI","Microsoft YaHei",sans-serif}.file-region rect{fill:#fff8;stroke:#d7e2ea;stroke-width:1}.file-region text{fill:#72889a;font:12px Consolas,monospace}.atlas-link{cursor:pointer}.edge-line{fill:none;stroke:#829bb2;stroke-width:1.25;opacity:.55}.edge-hit{fill:none;stroke:transparent;stroke-width:12}.atlas-link.active .edge-line{stroke:#2f7fd3;stroke-width:2;opacity:1}.atlas-link.traced .edge-line{stroke:#168fa3;stroke-width:3}.atlas-link.dim .edge-line{opacity:.16}.edge-label{fill:#285775;stroke:#f4f7f9;stroke-width:4;paint-order:stroke;font:12px Consolas,monospace}.atlas-node{cursor:pointer}.atlas-node.dim{opacity:.4}.node-halo{fill:currentColor;opacity:.06}.focus-ring{fill:none;stroke:#2f7fd3;stroke-width:2}.node-caption rect{fill:#ffffffed;stroke:#d7e2ea}.node-caption text{fill:#213c51;font:13px Consolas,"Microsoft YaHei",monospace}.node-caption .node-kind{fill:#647e90;font-size:11px}.selected .node-caption rect{stroke:#2f7fd3;fill:#edf5fe}.atlas-node:focus-visible,.atlas-link:focus-visible{outline:2px solid #2f7fd3;outline-offset:4px}
</style>