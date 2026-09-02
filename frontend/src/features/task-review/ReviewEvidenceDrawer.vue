<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue';
import type { ReviewEvidenceSelection } from './types';
import ReviewEvidenceContent from './ReviewEvidenceContent.vue';

const props = defineProps<{
  selection: ReviewEvidenceSelection | null;
}>();
const emit = defineEmits<{
  close: [];
  openCode: [selection: ReviewEvidenceSelection];
  openKnowledge: [selection: ReviewEvidenceSelection];
}>();

const mobile = shallowRef(false);
let media: MediaQueryList | null = null;
const mobileVisible = computed({
  get: () => mobile.value && Boolean(props.selection),
  set: value => {
    if (!value) emit('close');
  },
});

function updateMobile(event?: MediaQueryListEvent) {
  mobile.value = event?.matches ?? media?.matches ?? false;
}

onMounted(() => {
  if (typeof window === 'undefined' || !window.matchMedia) return;
  media = window.matchMedia('(max-width: 980px)');
  updateMobile();
  media.addEventListener('change', updateMobile);
});

onBeforeUnmount(() => media?.removeEventListener('change', updateMobile));
</script>

<template>
  <aside v-if="!mobile" class="evidence-drawer" aria-live="polite">
    <ReviewEvidenceContent
      :selection="selection"
      @close="emit('close')"
      @open-code="emit('openCode', $event)"
      @open-knowledge="emit('openKnowledge', $event)"
    />
  </aside>

  <el-drawer v-else v-model="mobileVisible" direction="btt" size="78%" :with-header="false">
    <ReviewEvidenceContent
      :selection="selection"
      @close="emit('close')"
      @open-code="emit('openCode', $event)"
      @open-knowledge="emit('openKnowledge', $event)"
    />
  </el-drawer>
</template>

<style scoped>
.evidence-drawer { position: sticky; top: 12px; min-height: 360px; max-height: calc(100vh - 152px); overflow: auto; border: 1px solid #d8e0e5; border-radius: 9px; background: #fff; }
:deep(.drawer-empty) { display: grid; place-items: center; align-content: center; min-height: 358px; padding: 30px; color: #7a8790; text-align: center; }
:deep(.drawer-empty .empty-mark) { display: grid; place-items: center; width: 42px; height: 42px; margin-bottom: 12px; color: var(--app-color-action); border: 1px solid #bad0e2; border-radius: 50%; background: #f3f8fc; font: 20px "SFMono-Regular", Consolas, monospace; }
:deep(.drawer-empty strong) { color: #34434d; font-size: 15px; }
:deep(.drawer-empty p) { max-width: 230px; margin: 7px 0 0; font-size: 13px; line-height: 1.55; }
:deep(.drawer-content) { display: grid; gap: 15px; padding: 18px; }
:deep(.drawer-content > header) { display: flex; align-items: start; justify-content: space-between; gap: 12px; padding-bottom: 13px; border-bottom: 1px solid #e6ebee; }
:deep(.drawer-content > header div) { display: grid; min-width: 0; gap: 4px; }
:deep(.drawer-content > header small) { color: #77858e; font: 700 12px "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
:deep(.drawer-content h2) { overflow-wrap: anywhere; margin: 0; color: #1f2a33; font-size: 16px; line-height: 1.35; }
:deep(.drawer-content > header button) { display: grid; flex: none; place-items: center; width: 28px; height: 28px; color: #697780; border: 1px solid #dce3e7; border-radius: 6px; background: #fff; }
:deep(.drawer-content button:hover) { border-color: #8fb5d5; color: var(--app-color-action); }
:deep(.drawer-content button:focus-visible) { outline: 3px solid var(--app-focus-ring); outline-offset: 2px; }
:deep(.status-line) { justify-self: start; padding: 4px 7px; color: var(--app-color-action); border-left: 3px solid currentColor; background: #eef5fa; font-size: 13px; font-weight: 700; }
:deep(.status-line.kind-knowledge) { color: var(--app-color-success); background: #edf7f2; }
:deep(.status-line.kind-obligation), :deep(.status-line.kind-stale) { color: #9b5d18; background: #fff5e8; }
:deep(.status-line.kind-unknown) { color: #667681; background: #f1f3f5; }
:deep(.status-line.kind-model) { color: var(--app-color-model); background: var(--app-color-model-soft); }
:deep(.description) { margin: -4px 0 0; color: #596973; font-size: 14px; line-height: 1.6; }
:deep(dl) { display: grid; grid-template-columns: minmax(74px, auto) minmax(0, 1fr); gap: 0; margin: 0; border: 1px solid #e1e6e9; border-radius: 7px; overflow: hidden; }
:deep(dt), :deep(dd) { margin: 0; padding: 8px 9px; border-bottom: 1px solid #edf0f2; font-size: 13px; }
:deep(dt) { color: #75838c; background: #f7f9fa; }
:deep(dd) { overflow-wrap: anywhere; color: #34434c; }
:deep(dt:nth-last-of-type(1)), :deep(dd:nth-last-of-type(1)) { border-bottom: 0; }
:deep(.mono) { font-family: "SFMono-Regular", Consolas, monospace; }
:deep(.evidence-list) { display: grid; gap: 7px; }
:deep(.evidence-list h3) { margin: 0 0 1px; color: #52616b; font-size: 13px; }
:deep(.evidence-list article) { display: grid; gap: 4px; padding: 9px; border-left: 2px solid #9cbdd6; background: #f7fafc; }
:deep(.evidence-list small) { color: #6f7e88; font: 12px "SFMono-Regular", Consolas, monospace; }
:deep(.evidence-list strong) { color: #2b3942; font-size: 13px; }
:deep(.evidence-list p) { margin: 0; color: #697780; font-size: 13px; line-height: 1.45; }
:deep(.evidence-list code) { overflow-wrap: anywhere; color: #526b7c; font: 12px "SFMono-Regular", Consolas, monospace; }
:deep(.drawer-content > footer) { display: flex; flex-wrap: wrap; gap: 7px; padding-top: 2px; }
:deep(.drawer-content > footer button) { display: inline-flex; align-items: center; gap: 6px; min-height: 33px; padding: 0 10px; color: #34546a; border: 1px solid #cfdde6; border-radius: 6px; background: #f7fafc; font-size: 13px; font-weight: 650; }
@media (max-width: 980px) {
  .evidence-drawer { display: none; }
  :global(.el-drawer__body) { padding: 0; }
}
</style>
