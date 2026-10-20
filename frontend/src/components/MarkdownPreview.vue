<script setup lang="ts">
import { computed, shallowRef, useTemplateRef, watch, watchPostEffect } from 'vue';
import DOMPurify from 'dompurify';
import { renderMarkdown } from '@/features/knowledge/markdown';
import { renderMermaidBlocks } from '@/features/knowledge/mermaid';
import MermaidViewerDialog from './MermaidViewerDialog.vue';

const props = defineProps<{
  content: string;
  sourcePath?: string;
  repositoryId?: string;
  embedded?: boolean;
}>();
const emit = defineEmits<{ openPath: [path: string] }>();
const markdownBody = useTemplateRef<HTMLElement>('markdownBody');
const activeDiagram = shallowRef('');
const diagramVisible = shallowRef(false);
watch(() => [props.content, props.repositoryId, props.sourcePath], () => { diagramVisible.value = false; activeDiagram.value = ''; });

watchPostEffect(onCleanup => {
  // Track the rendered document as well as its mounted element.
  void renderedMarkdown.value;
  const root = markdownBody.value;
  let current = true;
  onCleanup(() => { current = false; });
  if (root) void renderMermaidBlocks(root, () => current);
});

function repositoryPath(sourcePath: string, reference: string) {
  if (!reference || /^(?:[a-z]+:|\/|#)/i.test(reference)) return null;
  const encodedReference = reference.split(/[?#]/, 1)[0];
  let cleanReference = encodedReference;
  try {
    cleanReference = decodeURIComponent(encodedReference);
  } catch {
    return null;
  }
  const sourceParts = sourcePath.replace(/\\/g, '/').split('/');
  sourceParts.pop();
  for (const part of cleanReference.replace(/\\/g, '/').split('/')) {
    if (!part || part === '.') continue;
    if (part === '..') sourceParts.pop();
    else sourceParts.push(part);
  }
  return sourceParts.join('/');
}

const renderedMarkdown = computed(() => {
  const sanitized = renderMarkdown(props.content, props.repositoryId);
  const parsed = new DOMParser().parseFromString(sanitized, 'text/html');
  parsed.querySelectorAll('table').forEach(table => {
    const scroll = parsed.createElement('div');
    scroll.className = 'markdown-table-scroll';
    table.replaceWith(scroll);
    scroll.appendChild(table);
  });
  parsed.querySelectorAll<HTMLAnchorElement>('a[href]').forEach(anchor => {
    const path = props.sourcePath ? repositoryPath(props.sourcePath, anchor.getAttribute('href') ?? '') : null;
    if (path) {
      anchor.dataset.repositoryPath = path;
      anchor.href = '#';
      return;
    }
    if (/^https?:/i.test(anchor.href)) {
      anchor.target = '_blank';
      anchor.rel = 'noopener noreferrer';
    }
  });
  if (props.repositoryId && props.sourcePath) {
    parsed.querySelectorAll<HTMLImageElement>('img[src]').forEach(image => {
      const path = repositoryPath(props.sourcePath!, image.getAttribute('src') ?? '');
      if (path) {
        image.src = `/api/repositories/${encodeURIComponent(props.repositoryId!)}/files/raw?path=${encodeURIComponent(path)}`;
        image.loading = 'lazy';
      }
    });
  }
  return DOMPurify.sanitize(parsed.body.innerHTML, { USE_PROFILES: { html: true } });
});

function handleClick(event: MouseEvent) {
  const figure = event.target instanceof Element ? event.target.closest<HTMLElement>('.markdown-mermaid') : null;
  const svg = figure?.querySelector('svg');
  if (svg) {
    event.preventDefault();
    figure?.querySelector<HTMLButtonElement>('button')?.focus();
    activeDiagram.value = svg.outerHTML;
    diagramVisible.value = true;
    return;
  }
  const target = event.target instanceof Element
    ? event.target.closest<HTMLAnchorElement>('a[data-repository-path]')
    : null;
  const path = target?.dataset.repositoryPath;
  if (!path) return;
  event.preventDefault();
  emit('openPath', path);
}
</script>

<template>
  <section class="markdown-preview-scroll" :class="{ 'markdown-preview-embedded': embedded }" role="document" aria-label="Markdown 预览">
    <!-- Markdown 先由 DOMPurify 清理，再作为受控 HTML 渲染。 -->
    <article ref="markdownBody" class="markdown-body" v-html="renderedMarkdown" @click="handleClick"></article>
    <MermaidViewerDialog v-if="activeDiagram" v-model="diagramVisible" :svg="activeDiagram" />
  </section>
</template>

<style scoped>
.markdown-body :deep(.markdown-mermaid) {
  margin: 18px 0;
  padding: 16px;
  overflow-x: auto;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  cursor: zoom-in;
}
.markdown-body :deep(.markdown-mermaid-expand) { display: block; margin: 12px 0 0 auto; padding: 6px 12px; color: var(--el-color-primary); background: var(--el-color-primary-light-9); border: 1px solid var(--el-border-color); border-radius: 6px; cursor: pointer; font: inherit; font-size: 13px; }
.markdown-body :deep(.markdown-mermaid-expand:focus-visible) { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.markdown-body :deep(.markdown-mermaid svg) { display: block; max-width: 100%; height: auto; margin: auto; }
.markdown-body :deep(.markdown-mermaid-error) { color: var(--el-color-warning); font-size: 13px; }
.markdown-preview-scroll {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  background: #fff;
  overscroll-behavior: contain;
}

.markdown-body {
  width: min(100%, 920px);
  margin: 0 auto;
  padding: 28px clamp(24px, 5vw, 64px) 56px;
  color: #303036;
  font-size: 14px;
  line-height: 1.75;
  overflow-wrap: anywhere;
}

.markdown-preview-embedded {
  overflow: visible;
  background: transparent;
}

.markdown-preview-embedded .markdown-body {
  width: 100%;
  margin: 0;
  padding: 0;
}

.markdown-body > :deep(:first-child) { margin-top: 0; }
.markdown-body > :deep(:last-child) { margin-bottom: 0; }

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  color: #1f2933;
  line-height: 1.35;
}

.markdown-body :deep(h1) {
  margin: 0 0 24px;
  padding-bottom: 12px;
  border-bottom: 1px solid #dfe3e8;
  font-size: 28px;
}

.markdown-body :deep(h2) {
  margin: 34px 0 14px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eceff2;
  font-size: 21px;
}

.markdown-body :deep(h3) {
  margin: 26px 0 10px;
  font-size: 17px;
}

.markdown-body :deep(h4) {
  margin: 22px 0 8px;
  font-size: 15px;
}

.markdown-body :deep(p) {
  margin: 0 0 16px;
}

.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: 20px 0 8px;
  font-size: 14px;
}

.markdown-body :deep(a) {
  color: var(--app-color-action);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0 0 18px;
  padding-left: 28px;
}

.markdown-body :deep(li + li) {
  margin-top: 5px;
}

.markdown-body :deep(li > ul),
.markdown-body :deep(li > ol) {
  margin: 6px 0;
}

.markdown-body :deep(li > p) { margin-bottom: 6px; }

.markdown-body :deep(blockquote) {
  margin: 18px 0;
  padding: 10px 16px;
  border-left: 3px solid #7bb2df;
  background: #f5f9fc;
  color: #5c6874;
}

.markdown-body :deep(blockquote p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(code) {
  padding: 2px 5px;
  border: 1px solid #dfe4e8;
  border-radius: 4px;
  background: #f3f5f7;
  color: #b42318;
  font: 14px/1.5 "SFMono-Regular", Consolas, monospace;
}

.markdown-body :deep(pre) {
  margin: 18px 0;
  padding: 16px 18px;
  overflow: auto;
  border: 1px solid #34373d;
  border-radius: 7px;
  background: #202124;
  color: #e6edf3;
}

.markdown-body :deep(pre code) {
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  line-height: 1.7;
  white-space: pre;
}

.markdown-body :deep(pre .hljs-comment),
.markdown-body :deep(pre .hljs-quote) { color: #8b949e; font-style: italic; }
.markdown-body :deep(pre .hljs-keyword),
.markdown-body :deep(pre .hljs-selector-tag),
.markdown-body :deep(pre .hljs-type) { color: #ff7ab2; }
.markdown-body :deep(pre .hljs-string),
.markdown-body :deep(pre .hljs-attribute),
.markdown-body :deep(pre .hljs-template-tag),
.markdown-body :deep(pre .hljs-template-variable) { color: #a8cc8c; }
.markdown-body :deep(pre .hljs-number),
.markdown-body :deep(pre .hljs-literal),
.markdown-body :deep(pre .hljs-variable),
.markdown-body :deep(pre .hljs-regexp) { color: #d2a8ff; }
.markdown-body :deep(pre .hljs-title) { color: #82aaff; }
.markdown-body :deep(pre .hljs-built_in),
.markdown-body :deep(pre .hljs-symbol),
.markdown-body :deep(pre .hljs-meta) { color: #f7c66f; }
.markdown-body :deep(pre .hljs-params),
.markdown-body :deep(pre .hljs-property),
.markdown-body :deep(pre .hljs-attr) { color: #c9d1d9; }

.markdown-body :deep(.markdown-table-scroll) {
  max-width: 100%;
  overflow: auto;
  margin: 18px 0;
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 0;
  border-spacing: 0;
  border-collapse: collapse;
  font-size: 14px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 9px 12px;
  border: 1px solid #dfe3e8;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f5f7f9;
  font-weight: 650;
}

.markdown-body :deep(td[align="center"]),
.markdown-body :deep(th[align="center"]) { text-align: center; }
.markdown-body :deep(td[align="right"]),
.markdown-body :deep(th[align="right"]) { text-align: right; }

.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 6px;
}

.markdown-body :deep(hr) {
  margin: 28px 0;
  border: 0;
  border-top: 1px solid #dfe3e8;
}

.markdown-body :deep(input[type="checkbox"]) {
  margin-right: 7px;
}

@media (max-width: 760px) {
  .markdown-body {
    padding: 22px 18px 44px;
  }

  .markdown-body :deep(h1) {
    font-size: 24px;
  }
}
</style>
