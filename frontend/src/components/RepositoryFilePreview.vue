<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, watch } from 'vue';
import { CopyDocument } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { FileCode2, TriangleAlert } from 'lucide-vue-next';
import hljs from 'highlight.js/lib/common';
import dos from 'highlight.js/lib/languages/dos';
import MarkdownPreview from '@/components/MarkdownPreview.vue';
import type { RepositoryFileContent } from '@/types/api';

const aliases: Record<string, string> = {
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
  properties: 'ini',
  bat: 'dos',
  cmd: 'dos',
  batch: 'dos',
};

hljs.registerLanguage('dos', dos);

const props = defineProps<{
  file: RepositoryFileContent | null;
  loading: boolean;
  error: string | null;
  focusLine: number | null;
  focusEndLine: number | null;
  focusVersion: number;
}>();
const root = ref<HTMLElement>();
const previewMode = shallowRef<'preview' | 'source'>('source');

const isMarkdown = computed(() => /\.(md|markdown)$/i.test(props.file?.path ?? ''));

const highlightedLines = computed(() => {
  if (!props.file) return [];
  const requested = aliases[props.file.language.toLowerCase()] ?? props.file.language.toLowerCase();
  const highlighted = requested && hljs.getLanguage(requested)
    ? hljs.highlight(props.file.content, { language: requested }).value
    : hljs.highlightAuto(props.file.content).value;
  return highlighted.split('\n').map((html, index) => ({
    number: index + 1,
    html: html || '&nbsp;',
  }));
});

function isFocused(number: number) {
  if (!props.focusLine) return false;
  return number >= props.focusLine && number <= (props.focusEndLine ?? props.focusLine);
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

async function copyContent() {
  if (!props.file) return;
  try {
    await navigator.clipboard.writeText(props.file.content);
    ElMessage.success('文件内容已复制');
  } catch {
    ElMessage.error('复制失败，请检查浏览器剪贴板权限');
  }
}

async function revealFocus() {
  if (!props.focusLine) return;
  await nextTick();
  root.value?.querySelector<HTMLElement>(`[data-line="${props.focusLine}"]`)
    ?.scrollIntoView({ block: 'center' });
}

watch(
  () => [props.file?.path, props.focusLine, props.focusVersion],
  async ([path, focusLine]) => {
    previewMode.value = isMarkdown.value && !focusLine ? 'preview' : 'source';
    if (path && focusLine) await revealFocus();
  },
  { flush: 'post' },
);
</script>

<template>
  <main ref="root" v-loading="loading" class="repository-file-preview">
    <template v-if="file">
      <header class="file-preview-head">
        <div class="file-title">
          <div>
            <h2>{{ file.name }}</h2>
            <p class="mono">{{ file.path }}</p>
          </div>
          <div class="file-actions">
            <el-button-group v-if="isMarkdown" aria-label="Markdown 查看方式">
              <el-button
                :type="previewMode === 'preview' ? 'primary' : 'default'"
                @click="previewMode = 'preview'"
              >
                预览
              </el-button>
              <el-button
                :type="previewMode === 'source' ? 'primary' : 'default'"
                @click="previewMode = 'source'"
              >
                源码
              </el-button>
            </el-button-group>
            <el-button :icon="CopyDocument" plain @click="copyContent">
              {{ isMarkdown ? '复制内容' : '复制代码' }}
            </el-button>
          </div>
        </div>
        <div class="file-facts">
          <span>{{ file.language || '文本' }}</span>
          <span>{{ file.lineCount }} 行</span>
          <span>{{ formatBytes(file.sizeBytes) }}</span>
          <span v-if="focusLine" class="focus-badge">
            定位到第 {{ focusLine }}{{ focusEndLine && focusEndLine !== focusLine ? `–${focusEndLine}` : '' }} 行
          </span>
        </div>
      </header>
      <MarkdownPreview
        v-if="isMarkdown && previewMode === 'preview'"
        :content="file.content"
      />
      <div v-else class="file-code-scroll" role="region" :aria-label="`${file.name} 完整代码`">
        <div class="file-code-lines">
          <div
            v-for="line in highlightedLines"
            :key="line.number"
            class="file-code-line"
            :class="{ focused: isFocused(line.number) }"
            :data-line="line.number"
          >
            <span class="file-line-number" aria-hidden="true">{{ line.number }}</span>
            <!-- highlight.js 会先转义代码文本，再生成受控的高亮标签。 -->
            <code class="file-line-content" v-html="line.html"></code>
          </div>
        </div>
      </div>
    </template>
    <div v-else class="preview-empty" :class="{ 'is-error': error }">
      <div class="preview-empty-icon" aria-hidden="true">
        <TriangleAlert v-if="error" :size="27" />
        <FileCode2 v-else :size="29" />
      </div>
      <div class="preview-empty-copy">
        <h2>{{ error ? '暂时无法预览这个文件' : '选择一个文件开始阅读' }}</h2>
        <p>{{ error ?? '从左侧目录打开文件，在这里查看完整代码、行号和搜索定位。' }}</p>
      </div>
      <div v-if="!error" class="preview-empty-steps" aria-label="操作提示">
        <span><b>1</b> 展开目录</span>
        <i>→</i>
        <span><b>2</b> 选择文件</span>
      </div>
    </div>
  </main>
</template>

<style scoped>
.repository-file-preview {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border-block: 1px solid #dedee3;
}

.preview-empty {
  position: relative;
  display: grid;
  place-content: center;
  justify-items: center;
  min-height: 0;
  padding: 40px;
  overflow: hidden;
  color: #5f6f7f;
  text-align: center;
  background:
    linear-gradient(rgb(255 255 255 / 88%), rgb(255 255 255 / 88%)),
    repeating-linear-gradient(0deg, transparent 0 27px, #dbe4ec 28px),
    repeating-linear-gradient(90deg, transparent 0 27px, #dbe4ec 28px);
}

.preview-empty::after {
  position: absolute;
  width: 280px;
  height: 280px;
  content: "";
  background: radial-gradient(circle, rgb(0 102 204 / 7%), transparent 68%);
  pointer-events: none;
}

.preview-empty-icon {
  position: relative;
  z-index: 1;
  display: grid;
  width: 62px;
  height: 62px;
  place-items: center;
  color: var(--app-color-action);
  background: #edf5fd;
  border: 1px solid #c9dff3;
  border-radius: 16px;
  box-shadow: 0 8px 24px rgb(30 76 118 / 10%);
}

.preview-empty-copy {
  position: relative;
  z-index: 1;
  max-width: 420px;
}

.preview-empty-copy h2 {
  margin: 18px 0 7px;
  color: #24384b;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -.01em;
}

.preview-empty-copy p {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 14px;
  line-height: 1.7;
}

.preview-empty-steps {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 20px;
  padding: 7px 10px;
  color: #637281;
  background: rgb(255 255 255 / 78%);
  border: 1px solid #dce4eb;
  border-radius: 7px;
  font-size: 13px;
  box-shadow: 0 3px 12px rgb(30 54 78 / 5%);
}

.preview-empty-steps span {
  display: flex;
  align-items: center;
  gap: 6px;
}

.preview-empty-steps b {
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  color: #005eb8;
  background: var(--app-color-action-soft);
  border-radius: 50%;
  font-size: 13px;
}

.preview-empty-steps i {
  color: #a1acb7;
  font-style: normal;
}

.preview-empty.is-error .preview-empty-icon {
  color: var(--app-color-warning);
  background: #fff4e8;
  border-color: #f2d0ad;
}

.preview-empty.is-error::after {
  background: radial-gradient(circle, rgb(181 71 8 / 7%), transparent 68%);
}

.file-preview-head {
  display: grid;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid #dedee3;
}

.file-title {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.file-title > div {
  min-width: 0;
}

.file-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.file-title h2 {
  margin: 0;
  color: #242428;
  font-size: 15px;
}

.file-title p {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--app-text-muted);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 16px;
  color: #66666d;
  font-size: 13px;
}

.focus-badge {
  color: #005eb8;
  font-weight: 600;
}

.file-code-scroll {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  color: #e6edf3;
  background: #202124;
  font: 14px/1.8 "SFMono-Regular", Consolas, monospace;
  overscroll-behavior: contain;
}

.file-code-lines {
  display: table;
  min-width: 100%;
  padding: 14px 0;
  border-spacing: 0;
}

.file-code-line {
  display: table-row;
}

.file-code-line.focused {
  background: rgb(255 202 58 / 14%);
  box-shadow: inset 3px 0 #f0b429;
}

.file-line-number,
.file-line-content {
  display: table-cell;
  height: 22px;
  vertical-align: top;
}

.file-line-number {
  position: sticky;
  left: 0;
  z-index: 1;
  width: 1%;
  min-width: 52px;
  padding: 0 12px 0 10px;
  color: #71717a;
  text-align: right;
  user-select: none;
  background: #202124;
  border-right: 1px solid #35363a;
}

.focused .file-line-number {
  color: #f4ca64;
  background: #2d2b23;
}

.file-line-content {
  min-width: 100%;
  padding: 0 20px;
  white-space: pre;
}

.file-line-content :deep(.hljs-comment),
.file-line-content :deep(.hljs-quote) { color: #8b949e; font-style: italic; }
.file-line-content :deep(.hljs-keyword),
.file-line-content :deep(.hljs-selector-tag),
.file-line-content :deep(.hljs-type) { color: #ff7ab2; }
.file-line-content :deep(.hljs-string),
.file-line-content :deep(.hljs-attribute),
.file-line-content :deep(.hljs-template-tag),
.file-line-content :deep(.hljs-template-variable) { color: #a8cc8c; }
.file-line-content :deep(.hljs-number),
.file-line-content :deep(.hljs-literal),
.file-line-content :deep(.hljs-variable),
.file-line-content :deep(.hljs-regexp) { color: #d2a8ff; }
.file-line-content :deep(.hljs-title),
.file-line-content :deep(.hljs-title.class_),
.file-line-content :deep(.hljs-title.function_) { color: #82aaff; }
.file-line-content :deep(.hljs-built_in),
.file-line-content :deep(.hljs-symbol),
.file-line-content :deep(.hljs-meta) { color: #f7c66f; }
.file-line-content :deep(.hljs-params),
.file-line-content :deep(.hljs-property),
.file-line-content :deep(.hljs-attr) { color: #c9d1d9; }

@media (max-width: 760px) {
  .file-actions {
    align-items: flex-end;
    flex-direction: column;
  }

  .file-actions .el-button {
    padding-inline: 10px;
  }
}
</style>
