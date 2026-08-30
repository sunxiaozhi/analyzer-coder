<script setup lang="ts">
import { BookOpen, Code2, ExternalLink, X } from 'lucide-vue-next';
import ProvenanceSummary from '@/components/evidence/ProvenanceSummary.vue';
import type { Provenance } from '@/types/evidence';
import type { ReviewEvidenceSelection } from './types';

const props = defineProps<{ selection: ReviewEvidenceSelection | null }>();
const emit = defineEmits<{
  close: [];
  openCode: [selection: ReviewEvidenceSelection];
  openKnowledge: [selection: ReviewEvidenceSelection];
}>();

function openSource(source: Provenance) {
  if (!props.selection) return;
  if (source.knowledgeCardId) {
    emit('openKnowledge', { ...props.selection, knowledgeId: source.knowledgeCardId });
  } else if (source.filePath) {
    emit('openCode', {
      ...props.selection,
      filePath: source.filePath,
      startLine: source.startLine,
      endLine: source.endLine,
    });
  }
}
</script>

<template>
  <div v-if="!selection" class="drawer-empty">
    <span class="empty-mark">↳</span>
    <strong>选择一条审查证据</strong>
    <p>这里会解释结论来自哪个文件、规则和版本，不用离开页面反复查找。</p>
  </div>

  <div v-else class="drawer-content">
    <header>
      <div><small>{{ selection.eyebrow }}</small><h2>{{ selection.title }}</h2></div>
      <button type="button" title="关闭详情" @click="emit('close')"><X :size="16" /></button>
    </header>

    <span class="status-line" :class="`kind-${selection.kind.toLowerCase()}`">{{ selection.status }}</span>
    <p class="description">{{ selection.description }}</p>

    <dl>
      <template v-for="fact in selection.facts" :key="fact.label">
        <dt>{{ fact.label }}</dt><dd :class="{ mono: fact.mono }">{{ fact.value }}</dd>
      </template>
    </dl>

    <ProvenanceSummary :sources="selection.sources" @open="openSource" />

    <section v-if="selection.evidence.length" class="evidence-list">
      <h3>证据链</h3>
      <article v-for="reason in selection.evidence" :key="`${reason.kind}:${reason.rule}:${reason.target}`">
        <small>{{ reason.kind }} · {{ reason.rule }}</small>
        <strong>{{ reason.target }}</strong>
        <p>{{ reason.evidence.detail }}</p>
        <code>{{ reason.evidence.filePath }}<template v-if="reason.evidence.symbolName"> · {{ reason.evidence.symbolName }}</template></code>
      </article>
    </section>

    <footer>
      <button v-if="selection.filePath" type="button" @click="emit('openCode', selection)">
        <Code2 :size="14" />打开源码<ExternalLink :size="12" />
      </button>
      <button v-if="selection.knowledgeId" type="button" @click="emit('openKnowledge', selection)">
        <BookOpen :size="14" />打开知识卡片<ExternalLink :size="12" />
      </button>
    </footer>
  </div>
</template>
