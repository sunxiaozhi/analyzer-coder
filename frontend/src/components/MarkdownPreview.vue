<script setup lang="ts">
import { computed } from 'vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';

const props = defineProps<{
  content: string;
  sourcePath?: string;
  repositoryId?: string;
}>();
const emit = defineEmits<{ openPath: [path: string] }>();

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
  const sanitized = DOMPurify.sanitize(
    marked.parse(props.content, { async: false, gfm: true }),
    { USE_PROFILES: { html: true } },
  );
  if (!props.sourcePath) return sanitized;
  const parsed = new DOMParser().parseFromString(sanitized, 'text/html');
  parsed.querySelectorAll<HTMLAnchorElement>('a[href]').forEach(anchor => {
    const path = repositoryPath(props.sourcePath!, anchor.getAttribute('href') ?? '');
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
  if (props.repositoryId) {
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
  <section class="markdown-preview-scroll" role="document" aria-label="Markdown 预览">
    <!-- Markdown 先由 DOMPurify 清理，再作为受控 HTML 渲染。 -->
    <article class="markdown-body" v-html="renderedMarkdown" @click="handleClick"></article>
  </section>
</template>

<style scoped>
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

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
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

.markdown-body :deep(a) {
  color: #0066cc;
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
  font: 12px/1.5 "SFMono-Regular", Consolas, monospace;
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
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 18px 0;
  border-spacing: 0;
  border-collapse: collapse;
  font-size: 12px;
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

.markdown-body :deep(img) {
  max-width: 100%;
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
