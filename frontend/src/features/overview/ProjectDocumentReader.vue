<script setup lang="ts">
import { ExternalLink, FileText, Sparkles } from 'lucide-vue-next';
import MarkdownPreview from '@/components/MarkdownPreview.vue';
import type { RepositoryFileContent } from '@/types/api';

interface Props {
  repositoryId: string;
  file: RepositoryFileContent | null;
  loading: boolean;
  error: string | null;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  openFile: [path: string];
  guide: [];
}>();

function openRepositoryPath(path: string) {
  emit('openFile', path);
}
</script>

<template>
  <section id="project-readme" class="document-reader">
    <header class="reader-header">
      <div class="reader-title">

        <h2>项目介绍</h2>
        <p>{{ file?.path ?? 'README.md' }}</p>
      </div>

      <button
        v-if="file"
        type="button"
        class="source-action"
        @click="emit('openFile', file.path)"
      >
        查看源码
        <ExternalLink :size="13" />
      </button>
    </header>

    <main v-loading="loading" class="reading-surface">
      <MarkdownPreview
        v-if="file"
        :content="file.content"
        :source-path="file.path"
        :repository-id="repositoryId"
        @open-path="openRepositoryPath"
      />

      <div v-else-if="error" class="reader-state is-error">
        <FileText :size="28" />
        <h2>README 暂时无法读取</h2>
        <p>{{ error }}</p>
      </div>

      <div v-else-if="!loading" class="reader-state">
        <Sparkles :size="28" />
        <h2>这个项目还没有 README</h2>
        <p>可以进入知识问答，根据代码、配置和已有文档生成一份项目导读。</p>
        <button type="button" @click="emit('guide')">生成智能导读</button>
      </div>
    </main>
  </section>
</template>

<style scoped>
.document-reader {
  min-width: 0;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  background: #fff;
  overscroll-behavior: contain;
  scrollbar-color: #becbd3 transparent;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.document-reader::-webkit-scrollbar { width: 8px; }
.document-reader::-webkit-scrollbar-track { background: transparent; }
.document-reader::-webkit-scrollbar-thumb { border-radius: 4px; background: #becbd3; }

.reader-header {
  position: sticky;
  z-index: 3;
  top: 0;
  display: flex;
  min-height: 92px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px clamp(28px, 4vw, 54px);
  border-bottom: 1px solid #dce4e9;
  background: rgb(255 255 255 / 96%);
  backdrop-filter: blur(8px);
}

.reader-title {
  display: grid;
  min-width: 0;
  gap: 3px;
}


.reader-title > h2 {
  margin: 0;
  color: #172631;
  font-size: 23px;
  font-weight: 720;
  letter-spacing: -.02em;
}

.reader-title > p {
  overflow: hidden;
  margin: 0;
  color: #80909b;
  font: 500 13px/1.4 "SFMono-Regular", Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-action,
.reader-state > button {
  display: inline-flex;
  min-height: 34px;
  align-items: center;
  gap: 7px;
  padding: 7px 11px;
  color: #175d86;
  border: 1px solid #bfd0db;
  border-radius: 4px;
  background: #fff;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
}

.source-action:hover,
.source-action:focus-visible,
.reader-state > button:hover,
.reader-state > button:focus-visible {
  border-color: #6d9dbc;
  outline: 2px solid rgb(45 130 189 / 16%);
  outline-offset: 2px;
  background: #f4f9fc;
}

.reading-surface {
  min-width: 0;
  min-height: 720px;
  background: #fff;
}

.reading-surface :deep(.markdown-preview-scroll) {
  overflow: visible;
}

.reading-surface :deep(.markdown-body) {
  width: min(100%, 980px);
  margin: 0;
  padding: clamp(34px, 5vw, 62px) clamp(30px, 5vw, 68px) 80px;
  color: #26343e;
  font-size: 14px;
  line-height: 1.82;
}

.reading-surface :deep(.markdown-body h1) {
  color: #14242f;
  font-size: clamp(30px, 3.2vw, 42px);
  letter-spacing: -.035em;
}

.reading-surface :deep(.markdown-body h2) {
  margin-top: 42px;
  color: #17344c;
  font-size: 24px;
}

.reading-surface :deep(.markdown-body pre) {
  border-color: #182832;
  border-radius: 5px;
  background: #13232e;
}

.reader-state {
  display: grid;
  min-height: 680px;
  place-content: center;
  justify-items: center;
  padding: 48px;
  color: #2d82bd;
  text-align: center;
  background: #fbfcfd;
}

.reader-state > h2 {
  margin: 14px 0 6px;
  color: #263744;
  font-size: 18px;
}

.reader-state > p {
  max-width: 430px;
  margin: 0 0 18px;
  color: #697580;
  font-size: 14px;
  line-height: 1.65;
}

.reader-state.is-error {
  color: var(--app-color-warning);
}

@media (max-width: 820px) {
  .document-reader {
    height: auto;
    overflow: visible;
    scrollbar-gutter: auto;
  }
}

@media (max-width: 720px) {
  .reader-header {
    min-height: 78px;
    padding: 16px 18px;
  }

  .reader-title > h2 {
    font-size: 19px;
  }

  .reading-surface :deep(.markdown-body) {
    padding: 28px 20px 56px;
  }
}
</style>