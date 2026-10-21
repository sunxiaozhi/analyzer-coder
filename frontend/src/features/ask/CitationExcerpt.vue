<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import type { Citation } from '@/api/intelligence';
import MarkdownPreview from '@/components/MarkdownPreview.vue';

const props = defineProps<{ citation: Citation }>();
const full = shallowRef(false);
watch(() => props.citation.id, () => { full.value = false; });
const hasExcerpt = computed(() => props.citation.excerpt != null);
const canExpand = computed(() => hasExcerpt.value && props.citation.excerpt !== props.citation.content);
const text = computed(() => full.value || !hasExcerpt.value ? props.citation.content : props.citation.excerpt!);
const markdown = computed(() => {
  if (props.citation.sourceType === 'KNOWLEDGE' || /\.(md|markdown)$/i.test(props.citation.filePath)) return text.value;
  const extension = props.citation.filePath.split('.').pop()?.toLowerCase() ?? '';
  const language = ({ ts: 'typescript', js: 'javascript', py: 'python', sh: 'bash', yml: 'yaml' } as Record<string, string>)[extension] ?? extension;
  const fence = '`'.repeat([...text.value.matchAll(/`+/g)].reduce((size, match) => Math.max(size, match[0].length + 1), 3));
  return `${fence}${/^[a-z0-9]+$/.test(language) ? language : ''}\n${text.value}\n${fence}`;
});
</script>

<template>
  <div class="citation-excerpt">
    <small class="excerpt-label">
      {{ !hasExcerpt ? '历史原文快照' : full ? '引用时的完整原文' : '本轮证据原文' }}
      <template v-if="hasExcerpt && !full"> · L{{ citation.excerptStartLine }}–{{ citation.excerptEndLine }}</template>
      <template v-if="citation.knowledgeRevision"> · 修订 {{ citation.knowledgeRevision }}</template>
    </small>
    <MarkdownPreview :content="markdown" :repository-id="citation.repositoryId" embedded />
    <button v-if="canExpand" class="expand-original" type="button" :aria-expanded="full" @click="full = !full">
      {{ full ? '收起完整原文' : '展开完整原文' }}
    </button>
  </div>
</template>

<style scoped>
.citation-excerpt { min-width:0; padding:12px; border-left:3px solid var(--app-border-strong, #c9dbe9); background:var(--app-surface, #fff); }
.excerpt-label { display:block; margin-bottom:10px; color:var(--app-text-muted); font-size:12px; }
.expand-original { margin-top:12px; padding:4px 0; border:0; background:transparent; color:var(--app-color-action); cursor:pointer; }
.expand-original:focus-visible { outline:2px solid var(--app-color-action); outline-offset:3px; }
</style>
