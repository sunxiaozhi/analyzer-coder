<script setup lang="ts">
import { computed, shallowRef, useTemplateRef, watch } from 'vue';
import DOMPurify from 'dompurify';

const props = defineProps<{ svg: string }>();
const visible = defineModel<boolean>({ required: true });
const viewport = useTemplateRef<HTMLElement>('viewport');
const scale = shallowRef(1);
const x = shallowRef(0);
const y = shallowRef(0);
const dragging = shallowRef(false);
let pointer: { id: number; x: number; y: number } | null = null;
const diagram = computed(() => {
  const safe = DOMPurify.sanitize(props.svg, { USE_PROFILES: { svg: true, svgFilters: true } });
  const svg = new DOMParser().parseFromString(safe, 'image/svg+xml').documentElement;
  const viewBox = svg.getAttribute('viewBox')?.trim().split(/[\s,]+/).map(Number);
  const width = viewBox?.[2] || parseFloat(svg.getAttribute('width') ?? '') || 1000;
  const height = viewBox?.[3] || parseFloat(svg.getAttribute('height') ?? '') || 600;
  // Give the image an intrinsic size; Mermaid normally uses width="100%".
  svg.setAttribute('width', String(width));
  svg.setAttribute('height', String(height));
  svg.removeAttribute('style');
  return { width, height, url: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(new XMLSerializer().serializeToString(svg))}` };
});
const imageStyle = computed(() => ({ width: `${diagram.value.width}px`, height: `${diagram.value.height}px`, transform: `translate(${x.value}px, ${y.value}px) scale(${scale.value})` }));

function stopDrag() { pointer = null; dragging.value = false; }
function reset() {
  stopDrag();
  x.value = 0; y.value = 0;
  const box = viewport.value?.getBoundingClientRect();
  scale.value = box?.width && box.height
    ? Math.max(0.01, Math.min((box.width - 40) / diagram.value.width, (box.height - 40) / diagram.value.height, 1)) : 1;
}
function zoom(factor: number, anchorX = 0, anchorY = 0) {
  const next = Math.min(8, Math.max(0.01, scale.value * factor));
  const ratio = next / scale.value;
  x.value = anchorX - (anchorX - x.value) * ratio;
  y.value = anchorY - (anchorY - y.value) * ratio;
  scale.value = next;
}
function onWheel(event: WheelEvent) {
  const box = viewport.value!.getBoundingClientRect();
  zoom(Math.exp(-Math.sign(event.deltaY) * 0.15), event.clientX - box.left - box.width / 2, event.clientY - box.top - box.height / 2);
}
function startDrag(event: PointerEvent) {
  if (event.button !== 0 || !viewport.value) return;
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY };
  dragging.value = true;
  viewport.value.setPointerCapture?.(event.pointerId);
}
function moveDrag(event: PointerEvent) {
  if (!pointer || pointer.id !== event.pointerId) return;
  x.value += event.clientX - pointer.x; y.value += event.clientY - pointer.y;
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY };
}
watch(() => [visible.value, props.svg], () => { stopDrag(); });
</script>

<template>
  <el-dialog v-model="visible" title="图表放大查看" width="min(1280px, 96vw)" top="4vh" append-to-body destroy-on-close :close-on-click-modal="false" @opened="reset" @closed="stopDrag">
    <div class="diagram-toolbar" role="group" aria-label="图表缩放">
      <el-button aria-label="缩小图表" @click="zoom(1 / 1.2)">缩小</el-button>
      <output aria-live="polite">{{ Math.round(scale * 100) }}%</output>
      <el-button aria-label="放大图表" @click="zoom(1.2)">放大</el-button>
      <el-button @click="reset">复位</el-button>
      <span>滚轮缩放 · 拖拽移动</span>
    </div>
    <div ref="viewport" class="diagram-viewport" :class="{ dragging }" @wheel.prevent="onWheel" @pointerdown.prevent="startDrag" @pointermove="moveDrag" @pointerup="stopDrag" @pointercancel="stopDrag" @lostpointercapture="stopDrag">
      <img :src="diagram.url" :style="imageStyle" alt="放大的 Mermaid 图表" draggable="false" />
    </div>
  </el-dialog>
</template>

<style scoped>
.diagram-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.diagram-toolbar .el-button + .el-button { margin-left: 0; }
.diagram-toolbar output { min-width: 52px; text-align: center; font-variant-numeric: tabular-nums; }
.diagram-toolbar span { color: var(--el-text-color-secondary); font-size: 13px; margin-left: auto; }
.diagram-viewport { height: max(160px, calc(84dvh - 120px)); position: relative; display: grid; place-items: center; overflow: hidden; background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; cursor: grab; touch-action: none; user-select: none; }
.diagram-viewport.dragging { cursor: grabbing; }
.diagram-viewport img { position: absolute; max-width: none; max-height: none; transform-origin: center; pointer-events: none; }
</style>
