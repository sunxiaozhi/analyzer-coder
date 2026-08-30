<script setup lang="ts">
import { ArrowUpRight, BookOpen, Code2, GitCommitHorizontal, Network } from 'lucide-vue-next';
import type { Provenance } from '@/types/evidence';

const props = defineProps<{ source: Provenance }>();
const emit = defineEmits<{ open: [source: Provenance] }>();

function label() {
  if (props.source.filePath) {
    const line = props.source.startLine ? `:${props.source.startLine}` : '';
    return `${props.source.filePath}${line}`;
  }
  if (props.source.knowledgeCardId) {
    return `知识卡片 v${props.source.knowledgeRevision ?? '?'}`;
  }
  if (props.source.graphArtifactId) return props.source.graphArtifactId;
  if (props.source.commitSha) return props.source.commitSha.slice(0, 12);
  if (props.source.retrievalChannel) return props.source.retrievalChannel;
  return props.source.findingId ?? '来源说明';
}

function actionable() {
  return Boolean(props.source.filePath || props.source.knowledgeCardId);
}
</script>

<template>
  <button v-if="actionable()" type="button" class="evidence-link" @click="emit('open', source)">
    <Code2 v-if="source.filePath" :size="12" />
    <BookOpen v-else :size="12" />
    <span>{{ label() }}</span><ArrowUpRight :size="11" />
  </button>
  <span v-else class="evidence-link static">
    <Network v-if="source.graphArtifactId" :size="12" />
    <GitCommitHorizontal v-else :size="12" />
    <span>{{ label() }}</span>
  </span>
</template>

<style scoped>
.evidence-link { display: inline-flex; min-width: 0; align-items: center; gap: 4px; padding: 0; overflow: hidden; color: #346b98; border: 0; background: transparent; font: 10px "SFMono-Regular", Consolas, monospace; text-align: left; }
button.evidence-link { cursor: pointer; }
button.evidence-link:hover { color: #174f7d; text-decoration: underline; }
.evidence-link span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.evidence-link.static { color: #67747d; }
</style>
