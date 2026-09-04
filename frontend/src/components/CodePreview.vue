<script setup lang="ts">
import { computed } from 'vue';
import hljs from 'highlight.js/lib/common';
import dos from 'highlight.js/lib/languages/dos';
import EmptyState from '@/components/EmptyState.vue';
import type { CodeChunk } from '@/types/api';

const chunkTypeLabels: Record<CodeChunk['chunkType'], string> = {
  FILE: 'FILE（文件）',
  SYMBOL: 'SYMBOL（符号）',
  DOC_SECTION: 'DOC_SECTION（文档段落）',
  TEST_CASE: 'TEST_CASE（测试用例）',
  CONFIG: 'CONFIG（配置）',
  KNOWLEDGE_CARD: '知识卡片',
};

const languageAliases: Record<string, string> = {
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  vue: 'xml',
  html: 'xml',
  htm: 'xml',
  yml: 'yaml',
  shell: 'bash',
  sh: 'bash',
  bat: 'dos',
  cmd: 'dos',
  batch: 'dos',
};

hljs.registerLanguage('dos', dos);

const props = defineProps<{
  chunk: CodeChunk | null;
}>();

const highlightedLines = computed(() => {
  if (!props.chunk) return [];

  const requestedLanguage = props.chunk.language?.toLowerCase();
  const extension = props.chunk.filePath.split('.').pop()?.toLowerCase();
  const language = languageAliases[requestedLanguage ?? '']
    ?? requestedLanguage
    ?? languageAliases[extension ?? '']
    ?? extension;
  const highlighted = language && hljs.getLanguage(language)
    ? hljs.highlight(props.chunk.content, { language }).value
    : hljs.highlightAuto(props.chunk.content).value;
  const firstLine = props.chunk.startLine ?? 1;

  return highlighted.split('\n').map((html, index) => ({
    number: firstLine + index,
    html: html || '&nbsp;',
  }));
});

function fileName(filePath: string) {
  return filePath.split(/[\\/]/).pop() ?? filePath;
}
</script>

<template>
  <section class="code-preview">
    <div v-if="chunk" class="code-preview-header">
      <div class="file-heading">
        <h2>{{ fileName(chunk.filePath) }}</h2>
        <p class="mono">{{ chunk.filePath }}</p>
      </div>
      <div class="file-metadata">
        <span>语言：{{ chunk.language ?? '文本' }}</span>
        <span>类型：{{ chunkTypeLabels[chunk.chunkType] }}</span>
        <span>行号：{{ chunk.startLine ?? 1 }}–{{ chunk.endLine ?? 1 }}</span>
        <span class="mono">内容哈希：{{ chunk.contentHash.slice(0, 12) }}</span>
      </div>
    </div>
    <div v-if="chunk" class="code-block" role="region" aria-label="带行号的高亮代码">
      <div class="code-lines">
        <div v-for="line in highlightedLines" :key="line.number" class="code-line">
          <span class="line-number" aria-hidden="true">{{ line.number }}</span>
          <!-- highlight.js 会先转义代码文本，再生成受控的高亮标签。 -->
          <code class="line-content" v-html="line.html"></code>
        </div>
      </div>
    </div>
    <EmptyState v-else title="未选择代码片段" />
  </section>
</template>

<style scoped>
.line-content :deep(.hljs-comment),
.line-content :deep(.hljs-quote) {
  color: #7f8c98;
  font-style: italic;
}

.line-content :deep(.hljs-keyword),
.line-content :deep(.hljs-selector-tag),
.line-content :deep(.hljs-type) {
  color: #ff7ab2;
}

.line-content :deep(.hljs-string),
.line-content :deep(.hljs-attribute),
.line-content :deep(.hljs-template-tag),
.line-content :deep(.hljs-template-variable) {
  color: #a8cc8c;
}

.line-content :deep(.hljs-number),
.line-content :deep(.hljs-literal),
.line-content :deep(.hljs-variable),
.line-content :deep(.hljs-regexp) {
  color: #d2a8ff;
}

.line-content :deep(.hljs-title),
.line-content :deep(.hljs-title.class_),
.line-content :deep(.hljs-title.function_) {
  color: #82aaff;
}

.line-content :deep(.hljs-built_in),
.line-content :deep(.hljs-symbol),
.line-content :deep(.hljs-meta) {
  color: #f7c66f;
}

.line-content :deep(.hljs-params),
.line-content :deep(.hljs-property),
.line-content :deep(.hljs-attr) {
  color: #c9d1d9;
}
</style>
