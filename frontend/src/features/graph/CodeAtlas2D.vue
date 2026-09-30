<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount, useId } from 'vue';
import { atlasEdgeKey, type AtlasNode, type AtlasEdge } from '@/api/codeAtlas';
import { atlasFileType } from './atlasFileType';
import { CARD_WIDTH, CARD_HEIGHT, layoutAtlasCards, projectAtlasCards, type AtlasCard, type AtlasCardLevel } from './atlasCardLayout';
const props = withDefaults(defineProps<{ nodes: AtlasNode[]; edges: AtlasEdge[]; selectedId?: string; connected: Set<string>; highlighted: Set<string>; level?: AtlasCardLevel; scopeKey?: string }>(), { level: 'symbols', scopeKey: '' });
const emit = defineEmits<{ select: [node: AtlasNode]; expand: [node: AtlasNode]; edge: [edge: AtlasEdge]; file: [path: string]; module: [name: string] }>();
const uid = useId().replace(/:/g, ''), host = ref<SVGSVGElement>();
const zoom = ref(1), pan = ref({ x: 0, y: 0 }), viewport = ref({ width: 1200, height: 800 });
const hovered = ref(''), isDragging = ref(false);
const projected = computed(() => projectAtlasCards(props.nodes, props.edges, props.level));
const layout = computed(() => layoutAtlasCards(projected.value.cards, projected.value.edges));
const byId = computed(() => new Map(layout.value.cards.map(card => [card.id, card])));
const focus = computed(() => props.selectedId || hovered.value);
const neighbors = computed(() => {
  const ids = new Set(focus.value ? [focus.value] : []);
  for (const edge of projected.value.edges) {
    if (edge.source === focus.value) ids.add(edge.target);
    if (edge.target === focus.value) ids.add(edge.source);
  }
  for (const id of props.connected) ids.add(id);
  return ids;
});
const edges = computed(() => projected.value.edges.flatMap(edge => {
  const a = byId.value.get(edge.source), b = byId.value.get(edge.target); if (!a || !b) return [];
  const ax = a.x + CARD_WIDTH, bx = b.x > a.x ? b.x : b.x + CARD_WIDTH;
  const ay = a.y + (b.x > a.x ? 56 : 38), by = b.y + (b.x > a.x ? 56 : 78);
  const bend = Math.max(32, (bx - ax) / 2), lane = Math.max(ax, bx) + 26 + (edge.kind.length % 3) * 9;
  const path = b.x > a.x
    ? 'M'+ax+','+ay+' C'+(ax+bend)+','+ay+' '+(bx-bend)+','+by+' '+bx+','+by
    : 'M'+ax+','+ay+' C'+lane+','+ay+' '+lane+','+by+' '+bx+','+by;
  const traced = edge.originals.some(original => props.highlighted.has(atlasEdgeKey(original)));
  return [{ ...edge, path, mx: b.x > a.x ? (ax+bx)/2 : lane, my: (ay+by)/2-9, traced,
    active: traced || edge.source === focus.value || edge.target === focus.value }];
}));
const shorten = (text: string, length: number) => text.length > length ? text.slice(0, length - 1) + '…' : text;
function subtitle(card: AtlasCard) {
  if (card.kind === 'MODULE') return '模块 · 双击进入';
  const path = card.filePath.replace(/\\/g, '/');
  return card.aggregated ? path.split('/').slice(0, -1).join('/') || '项目根目录' : path.split('/').pop() + ' : ' + (card.members[0].startLine || '—');
}
function selectCard(card: AtlasCard) { if (card.aggregated) emit('file', card.filePath); else emit('select', card.members[0]); }
function expandCard(card: AtlasCard) { if (card.aggregated) emit('file', card.filePath); else emit('expand', card.members[0]); }
function home() {
  const { width, height } = layout.value;
  zoom.value = Math.min(1.1, Math.max(.04, Math.min((viewport.value.width - 100) / Math.max(1, width), (viewport.value.height - 120) / Math.max(1, height))));
  pan.value = { x: (viewport.value.width - width * zoom.value) / 2, y: (viewport.value.height - height * zoom.value) / 2 };
}
function zoomBy(factor: number, x = viewport.value.width / 2, y = viewport.value.height / 2) {
  const next = Math.min(2.5, Math.max(.04, zoom.value / factor)), ratio = next / zoom.value;
  pan.value = { x: x - (x - pan.value.x) * ratio, y: y - (y - pan.value.y) * ratio }; zoom.value = next;
}
function wheel(event: WheelEvent) {
  const rect = host.value!.getBoundingClientRect(); zoomBy(event.deltaY > 0 ? 1.1 : 1 / 1.1, event.clientX - rect.left, event.clientY - rect.top);
}
let drag: { x: number; y: number; px: number; py: number } | null = null;
function pointerDown(event: PointerEvent) {
  if (event.button !== 0 || (event.target as Element).closest('[data-node], [data-edge], [data-module]')) return;
  host.value?.setPointerCapture(event.pointerId); isDragging.value = true;
  drag = { x: event.clientX, y: event.clientY, px: pan.value.x, py: pan.value.y };
}
function pointerMove(event: PointerEvent) { if (drag) pan.value = { x: drag.px + event.clientX - drag.x, y: drag.py + event.clientY - drag.y }; }
function stopDrag() { drag = null; isDragging.value = false; }
function focusNode(id?: string) {
  const node = id && byId.value.get(id); if (!node) return;
  const x = pan.value.x + node.x * zoom.value, y = pan.value.y + node.y * zoom.value;
  if (zoom.value >= .75 && x > 25 && y > 25 && x + CARD_WIDTH * zoom.value < viewport.value.width - 25 && y + CARD_HEIGHT * zoom.value < viewport.value.height - 150) return;
  zoom.value = Math.max(.9, zoom.value);
  pan.value = { x: viewport.value.width / 2 - (node.x + CARD_WIDTH / 2) * zoom.value, y: viewport.value.height / 2 - (node.y + CARD_HEIGHT / 2) * zoom.value - 45 };
}
let observer: ResizeObserver | undefined;
onMounted(() => {
  if (typeof ResizeObserver === 'undefined') return;
  observer = new ResizeObserver(() => {
    const rect = host.value?.getBoundingClientRect(); if (!rect?.width || !rect.height) return;
    viewport.value = { width: rect.width, height: rect.height }; home(); focusNode(props.selectedId);
  });
  if (host.value) observer.observe(host.value);
});
onBeforeUnmount(() => observer?.disconnect());
let previousPositions = new Map<string, { x: number; y: number }>();
watch([() => props.nodes, () => props.edges, () => props.level, () => props.scopeKey], (_, previous) => {
  const before = props.selectedId && previousPositions.get(props.selectedId);
  const after = props.selectedId && byId.value.get(props.selectedId);
  if (before && after && previous[2] === props.level && previous[3] === props.scopeKey) {
    pan.value = { x: pan.value.x + (before.x - after.x) * zoom.value, y: pan.value.y + (before.y - after.y) * zoom.value };
  } else { home(); focusNode(props.selectedId); }
  if (!byId.value.has(hovered.value)) hovered.value = '';
  previousPositions = new Map(layout.value.cards.map(card => [card.id, { x: card.x, y: card.y }]));
}, { immediate: true, flush: 'post' });
watch(() => props.selectedId, focusNode, { flush: 'post' });
defineExpose({ home, zoomBy, zoom });
</script>
<template>
  <svg ref="host" class="atlas-svg" :class="{ dragging: isDragging }" :viewBox="'0 0 '+viewport.width+' '+viewport.height"
    aria-label="代码依赖画布：点击文件进入符号，选择符号查看上下游，双击展开关联" @wheel.prevent="wheel"
    @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="stopDrag" @pointercancel="stopDrag" @lostpointercapture="stopDrag">
    <defs>
      <pattern :id="uid+'-grid'" width="20" height="20" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r=".8" fill="#d9e0eb"/></pattern>
      <marker :id="uid+'-arrow'" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M1 1 9 5 1 9" fill="none" stroke="context-stroke" stroke-width="1.5"/></marker>
      <filter :id="uid+'-shadow'" x="-10%" y="-15%" width="120%" height="145%"><feDropShadow dx="0" dy="3" stdDeviation="3" flood-color="#283c64" flood-opacity=".055"/></filter>
    </defs>
    <rect width="100%" height="100%" :fill="'url(#'+uid+'-grid)'"/>
    <g :transform="'translate('+pan.x+','+pan.y+') scale('+zoom+')'">
      <g v-for="group in layout.groups" :key="group.key" class="module-region">
        <rect :x="group.x" :y="group.y" :width="group.width" :height="group.height" rx="14"/>
        <g data-module tabindex="0" role="button" :aria-label="'进入模块 '+(group.key || '根目录')" class="module-heading" @click="emit('module', group.key)" @keydown.enter.prevent="emit('module', group.key)">
          <path :d="'M'+(group.x+24)+','+(group.y+23)+'h7l3,4h11v13h-21z'"/>
          <text :x="group.x+55" :y="group.y+36">{{ shorten(group.key || '根目录', Math.max(18, Math.floor((group.width-130)/7))) }}</text>
          <text class="group-count" :x="group.x+group.width-24" :y="group.y+35" text-anchor="end">{{ group.count }}</text>
        </g>
      </g>
      <g v-for="edge in edges" :key="edge.id" data-edge class="atlas-link" :class="{ active: edge.active, dim: focus && !edge.active, traced: edge.traced }"
        tabindex="0" role="button" :aria-label="byId.get(edge.source)?.label+' '+edge.kind+' '+byId.get(edge.target)?.label"
        @click.stop="emit('edge', edge.originals[0])" @keydown.enter.prevent="emit('edge', edge.originals[0])" @keydown.space.prevent="emit('edge', edge.originals[0])">
        <path :d="edge.path" class="edge-hit"/><path :d="edge.path" class="edge-line" :marker-end="'url(#'+uid+'-arrow)'" :stroke-dasharray="/inherit|extend|implement/i.test(edge.kind) ? '5 5' : undefined"/>
        <text v-if="edge.active && zoom > .55" :x="edge.mx" :y="edge.my" text-anchor="middle" class="edge-label">{{ edge.kind }}{{ edge.count > 1 ? ' · '+edge.count : '' }}</text>
        <title>{{ edge.kind }} · {{ edge.count }} 处{{ edge.originals.length > 1 ? '，点击查看其中一处关系' : '' }}</title>
      </g>
      <g v-for="card in layout.cards" :key="card.id" data-node :data-card-kind="card.kind" class="atlas-node"
        :class="{ selected: selectedId === card.id, neighbor: focus && neighbors.has(card.id), dim: focus && !neighbors.has(card.id) }"
        :transform="'translate('+card.x+','+card.y+')'" tabindex="0" role="button" :aria-label="card.label+' · '+card.kind+' · '+card.filePath"
        @mouseenter="hovered = card.id" @mouseleave="hovered = ''" @click.stop="selectCard(card)" @dblclick.stop="expandCard(card)"
        @keydown.enter.prevent="selectCard(card)" @keydown.space.prevent="expandCard(card)">
        <title>{{ card.aggregated ? card.filePath : card.members[0].qualifiedName || card.label }}</title>
        <rect class="card-surface" :width="CARD_WIDTH" :height="CARD_HEIGHT" rx="9" :filter="'url(#'+uid+'-shadow)'"/>
        <rect class="type-tile" x="15" y="12" width="28" height="25" rx="6"/>
        <text class="type-glyph" x="29" y="29" text-anchor="middle" :fill="atlasFileType(card).color">{{ card.kind === 'MODULE' ? '▦' : card.aggregated ? atlasFileType(card).badge : /class|interface/i.test(card.kind) ? '{ }' : 'ƒ' }}</text>
        <text class="card-kind" x="53" y="28">{{ card.aggregated ? atlasFileType(card).label : card.kind === 'MODULE' ? 'MODULE' : card.kind.toUpperCase() }}</text>
        <text class="card-title" x="16" y="56">{{ shorten(card.label, 29) }}</text>
        <text class="card-path" x="16" y="73">{{ shorten(subtitle(card), 35) }}</text>
        <path class="card-divider" :d="'M0,83 H'+CARD_WIDTH"/>
        <text class="card-meta" x="16" y="102">{{ card.aggregated ? card.members.length+' 个已加载符号' : card.kind === 'MODULE' ? card.members[0].count+' 个符号' : 'L'+(card.members[0].startLine || '—')+'–'+(card.members[0].endLine || '—') }}</text>
        <text class="card-meta" :x="CARD_WIDTH-16" y="102" text-anchor="end">{{ card.aggregated || card.kind === 'MODULE' ? '进入 →' : '↓ '+card.incoming+'  ↑ '+card.outgoing }}</text>
        <circle class="card-port" cx="0" cy="56" r="3.5"/><circle class="card-port" :cx="CARD_WIDTH" cy="56" r="3.5"/>
      </g>
    </g>
  </svg>
</template>
<style scoped>
.atlas-svg{width:100%;height:100%;display:block;touch-action:none;user-select:none;cursor:grab}.atlas-svg.dragging{cursor:grabbing}
.module-region>rect{fill:#f8fafde8;stroke:#dfe5ef;stroke-width:1}.module-heading{cursor:pointer}.module-heading path{fill:none;stroke:#74849c;stroke-width:1.4;stroke-linejoin:round}.module-heading text{fill:#42516a;font:600 13px "Segoe UI","Microsoft YaHei",sans-serif}.module-heading .group-count{fill:#8b97a9;font:12px Consolas,monospace}.module-heading:hover text{fill:#2563eb}
.atlas-link{cursor:pointer}.edge-line{fill:none;stroke:#acb8cb;stroke-width:1.35}.edge-hit{fill:none;stroke:transparent;stroke-width:14}.atlas-link.active .edge-line{stroke:#3776e8;stroke-width:2}.atlas-link.traced .edge-line{stroke:#168879;stroke-width:2.5}.atlas-link.dim{opacity:.18}.edge-label{fill:#3567b4;stroke:#f6f8fb;stroke-width:5;paint-order:stroke;font:12px Consolas,monospace}
.atlas-node{cursor:pointer;transition:opacity .16s}.card-surface{fill:#fff;stroke:#dbe2ed;stroke-width:1.2}.type-tile{fill:#f0f4fa}.type-glyph{font:600 12px Consolas,monospace}.card-kind{font:12px "Segoe UI",sans-serif;letter-spacing:.9px;fill:#8090a6}.card-title{font:600 13px Consolas,"Microsoft YaHei",monospace;fill:#23344f}.card-path{font:12px Consolas,"Microsoft YaHei",monospace;fill:#718198}.card-divider{stroke:#edf0f5}.card-meta{font:12px "Segoe UI","Microsoft YaHei",sans-serif;fill:#718198}.card-port{fill:#fff;stroke:#bbc8da;stroke-width:1.2}.atlas-node:hover .card-surface,.atlas-node.neighbor .card-surface{stroke:#93b4ef}.atlas-node.selected .card-surface{stroke:#3474e6;stroke-width:2;fill:#fcfdff}.atlas-node.selected .type-tile{fill:#eaf1ff}.atlas-node.selected .card-title{fill:#205dcc}.atlas-node.selected .card-port{fill:#3474e6;stroke:#3474e6}.atlas-node.dim{opacity:.3}.atlas-node:focus-visible .card-surface{stroke:#2563eb;stroke-width:3}.atlas-node:focus-visible,.atlas-link:focus-visible,.module-heading:focus-visible{outline:2px solid #2563eb;outline-offset:5px}
@media(prefers-reduced-motion:reduce){.atlas-node{transition:none}}
</style>
