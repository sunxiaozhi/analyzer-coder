<script setup lang="ts">
import { computed } from 'vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import type { Citation } from '@/api/intelligence';
import MarkdownPreview from '@/components/MarkdownPreview.vue';
import { citationMarkdown, isMarkdown } from './evidenceGroups';

const props = defineProps<{ citation: Citation; expanded: boolean }>();
// Legacy records did not save the prompt passage; keep their preview bounded and labelled.
const passage = computed(() => props.citation.excerpt ?? props.citation.content.slice(0, 1200));
const markdown = computed(() => citationMarkdown(props.citation, passage.value));
const preview = computed(() => {
  if (props.citation.sourceType === 'CODE' && !isMarkdown(props.citation.filePath)) {
    return passage.value.replace(/\s+/g, ' ').trim().slice(0, 280) || '暂无文字预览';
  }
  const html = DOMPurify.sanitize(marked.parse(passage.value.slice(0, 1600), { async: false }), { USE_PROFILES: { html: true } });
  const document = new DOMParser().parseFromString(html, 'text/html');
  document.querySelectorAll('pre').forEach(node => node.replaceWith(node.querySelector('.language-mermaid') ? '【图表，展开查看】' : '【代码片段，展开查看】'));
  document.querySelectorAll('img').forEach(node => node.replaceWith(`【图片：${node.getAttribute('alt') || '展开查看'}】`));
  document.querySelectorAll('br, p, li, h1, h2, h3, h4, pre, tr').forEach(node => node.append(' '));
  return (document.body.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 280) || '暂无文字预览';
});
</script>

<template>
  <div class="citation-excerpt">
    <small class="excerpt-label">
      {{ citation.excerpt == null ? '历史原文预览 · 未保存本轮片段' : '本轮相关片段' }}
      <template v-if="citation.excerptStartLine != null"> · L{{ citation.excerptStartLine }}–{{ citation.excerptEndLine }}</template>
      <template v-if="citation.knowledgeRevision"> · 修订 {{ citation.knowledgeRevision }}</template>
    </small>
    <MarkdownPreview v-if="expanded" :content="markdown" :repository-id="citation.repositoryId" embedded />
    <p v-else class="excerpt-preview">{{ preview }}</p>
  </div>
</template>

<style scoped>
.citation-excerpt { min-width:0; }
.excerpt-label { display:block; margin-bottom:8px; color:var(--app-text-muted, #66717c); font-size:12px; }
.excerpt-preview { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:3; overflow:hidden; max-height:4.8em; margin:0; color:var(--app-text-secondary, #4f5e6c); font-size:14px; line-height:1.6; overflow-wrap:anywhere; }
</style>
