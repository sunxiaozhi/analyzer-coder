<script setup lang="ts">
import { computed } from 'vue';
import { Braces, FileCode2, ScanSearch, ShieldAlert } from 'lucide-vue-next';
import type {
  ProjectArchitectureEdge,
  ProjectArchitectureNode,
  ProjectArchitectureRisk,
} from '@/api/repositories';

interface Props {
  node: ProjectArchitectureNode | null;
  edges: ProjectArchitectureEdge[];
  risks: ProjectArchitectureRisk[];
}

const props = defineProps<Props>();
const emit = defineEmits<{
  openModule: [path: string];
  openSymbols: [module: string];
  openFile: [path: string];
}>();

function evidenceFile(sample: string) {
  const [source] = sample.split(' → ');
  return source?.trim() || sample.trim();
}

const evidencePaths = computed(() =>
  [...new Set(props.edges.flatMap(edge => edge.samples.map(evidenceFile)))].slice(0, 10),
);
const nodeKindLabel = computed(() => props.node?.kind === 'RESOURCE' ? '运行资源' : '当前模块');
const nodeTitle = computed(() => {
  if (!props.node) return '';
  return props.node.kind === 'RESOURCE' ? props.node.label : props.node.id;
});
const nodeDetail = computed(() => {
  if (!props.node) return '';
  if (props.node.kind === 'RESOURCE') return props.node.resourceType ?? '外部依赖';
  return `${props.node.codeFileCount} 个代码文件 · ${props.node.primaryLanguage || '未知语言'}`;
});
</script>

<template>
  <aside class="selection-panel">
    <header v-if="node" class="selection-title">
      <span>{{ nodeKindLabel }}</span>
      <strong>{{ nodeTitle }}</strong>
      <small>{{ nodeDetail }}</small>
    </header>

    <section class="selection-section evidence">
      <div class="section-title">
        <h3>关系证据</h3>
        <span>{{ evidencePaths.length }}</span>
      </div>
      <button
        v-for="path in evidencePaths"
        :key="path"
        type="button"
        :title="path"
        @click="emit('openFile', path)"
      >
        <FileCode2 :size="13" />
        <span>{{ path }}</span>
      </button>
      <p v-if="!evidencePaths.length" class="empty-note">当前关系没有可定位的来源文件。</p>
    </section>

    <section class="selection-section risks">
      <div class="section-title">
        <h3>架构提醒</h3>
        <span>{{ risks.length }}</span>
      </div>
      <article v-for="risk in risks" :key="risk.id" :data-severity="risk.severity">
        <ShieldAlert :size="14" />
        <div><strong>{{ risk.title }}</strong><small>{{ risk.detail }}</small></div>
      </article>
      <p v-if="!risks.length" class="empty-note">当前模块没有边界或循环依赖提醒。</p>
    </section>

    <footer v-if="node?.kind === 'MODULE'" class="selection-actions">
      <button type="button" class="symbol-action" @click="emit('openSymbols', node.id)">
        <Braces :size="14" />浏览代码符号
      </button>
      <button type="button" class="module-action" @click="emit('openModule', node.path)">
        <ScanSearch :size="14" />检索模块资产
      </button>
    </footer>
  </aside>
</template>

<style scoped>
.selection-panel { display: flex; min-width: 0; min-height: 100%; flex-direction: column; background: #fff; }
.selection-title { display: grid; gap: 4px; padding: 15px; border-bottom: 1px solid #ececef; }
.selection-title span { color: var(--app-text-muted); font-size: 13px; }
.selection-title strong { overflow-wrap: anywhere; color: #1d1d1f; font: 600 14px "SFMono-Regular", Consolas, monospace; }
.selection-title small { color: var(--app-text-muted); font-size: 13px; }
.selection-section { padding: 13px 15px; border-bottom: 1px solid #ececef; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.section-title h3 { margin: 0; color: #52525b; font-size: 14px; }
.section-title > span { display: grid; min-width: 20px; height: 20px; place-items: center; color: var(--app-text-muted); border-radius: 999px; background: #f1f1f4; font-size: 13px; }
.evidence { display: grid; gap: 4px; }
.evidence .section-title { margin-bottom: 4px; }
.evidence button { display: grid; grid-template-columns: 16px minmax(0, 1fr); align-items: center; gap: 6px; padding: 6px 4px; overflow: hidden; color: #52525b; text-align: left; border: 0; border-radius: 4px; background: transparent; font: 500 13px "SFMono-Regular", Consolas, monospace; }
.evidence button:hover, .evidence button:focus-visible { color: var(--app-color-action); outline: none; background: #f1f7fd; }
.evidence button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.risks { display: grid; gap: 6px; }
.risks article { display: grid; grid-template-columns: 18px 1fr; gap: 7px; padding: 8px; color: #8d4b31; border-left: 2px solid #c4852d; background: #fbf5ec; }
.risks article[data-severity='HIGH'] { color: #9b3f31; border-color: var(--app-color-danger); background: #fbefed; }
.risks article div { display: grid; gap: 3px; }
.risks strong { font-size: 14px; }
.risks small { color: #6f625e; font-size: 14px; line-height: 1.5; }
.empty-note { margin: 2px 0; color: var(--app-text-muted); font-size: 14px; line-height: 1.55; }
.selection-actions { display: grid; gap: 6px; margin-top: auto; padding: 12px 15px 15px; }
.selection-actions button { display: flex; min-height: 32px; align-items: center; justify-content: center; gap: 5px; border-radius: 4px; font-size: 13px; }
.selection-actions button:focus-visible { outline: 2px solid rgb(0 102 204 / 24%); outline-offset: 2px; }
.symbol-action { color: #fff; border: 1px solid var(--app-color-action); background: var(--app-color-action); }
.module-action { color: var(--app-color-action); border: 1px solid #d5e2ef; background: #f3f8fc; }
@media (max-width: 900px) { .selection-panel { min-height: 260px; } }
</style>
