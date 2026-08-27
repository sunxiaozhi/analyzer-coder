<script setup lang="ts">
import { computed } from 'vue';
import { BookMarked, FileText, Sparkles } from 'lucide-vue-next';
import MarkdownPreview from '@/components/MarkdownPreview.vue';
import type { RepositoryFileContent } from '@/types/api';
import type { ProjectDocument, ProjectDocumentCategory } from './useProjectReadme';

interface Props {
  repositoryId: string;
  documents: ProjectDocument[];
  selectedPath: string | null;
  file: RepositoryFileContent | null;
  loading: boolean;
  error: string | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  select: [path: string];
  openFile: [path: string];
  guide: [];
}>();

const categoryOrder: ProjectDocumentCategory[] = [
  'overview',
  'getting-started',
  'architecture',
  'development',
  'operations',
  'rules',
  'other',
];

const groups = computed(() => categoryOrder
  .map(category => ({
    category,
    label: props.documents.find(document => document.category === category)?.categoryLabel ?? '',
    documents: props.documents.filter(document => document.category === category),
  }))
  .filter(group => group.documents.length));

function navigateMarkdown(path: string) {
  if (props.documents.some(document => document.path === path)) emit('select', path);
  else emit('openFile', path);
}
</script>

<template>
  <section id="project-readme" class="document-reader">
    <aside class="document-spine" aria-label="项目文档">
      <header>
        <span><BookMarked :size="15" />项目文档</span>
        <strong>{{ documents.length }}</strong>
      </header>
      <nav v-if="groups.length">
        <section v-for="group in groups" :key="group.category">
          <h2>{{ group.label }}</h2>
          <button
            v-for="document in group.documents"
            :key="document.path"
            type="button"
            :class="{ active: selectedPath === document.path }"
            :title="document.path"
            @click="emit('select', document.path)"
          >
            <FileText :size="13" />
            <span><strong>{{ document.title }}</strong><small>{{ document.path }}</small></span>
          </button>
        </section>
      </nav>
      <div v-else class="spine-empty">
        <FileText :size="20" />
        <span>没有发现 Markdown 文档</span>
      </div>
    </aside>

    <main v-loading="loading" class="reading-surface">
      <header v-if="file" class="reading-head">
        <div>
          <span>正在阅读</span>
          <strong>{{ file.path }}</strong>
        </div>
        <button type="button" @click="emit('openFile', file.path)">查看源码</button>
      </header>

      <MarkdownPreview
        v-if="file"
        :content="file.content"
        :source-path="file.path"
        :repository-id="repositoryId"
        @open-path="navigateMarkdown"
      />

      <div v-else-if="error" class="reader-state is-error">
        <FileText :size="28" />
        <h2>项目文档暂时无法读取</h2>
        <p>{{ error }}</p>
      </div>

      <div v-else-if="!loading" class="reader-state">
        <Sparkles :size="28" />
        <h2>这个项目还没有 README</h2>
        <p>可以根据代码结构、配置和已有文档生成一份项目导读。</p>
        <button type="button" @click="emit('guide')">生成智能导读</button>
      </div>
    </main>
  </section>
</template>

<style scoped>
.document-reader {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  min-width: 0;
  min-height: 680px;
  overflow: hidden;
  border: 1px solid #d7dce2;
  border-radius: 9px;
  background: #fff;
}

.document-spine {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  color: #c6d4df;
  background: #1a2935;
}

.document-spine > header {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 13px;
  border-bottom: 1px solid rgb(255 255 255 / 10%);
}

.document-spine > header span {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 650;
}

.document-spine > header strong {
  display: grid;
  min-width: 24px;
  height: 24px;
  place-items: center;
  color: #8fb5cf;
  border-radius: 999px;
  background: rgb(255 255 255 / 7%);
  font: 600 11px Consolas, monospace;
}

.document-spine nav {
  min-height: 0;
  padding: 8px;
  overflow: auto;
}

.document-spine nav section + section { margin-top: 12px; }

.document-spine h2 {
  margin: 0;
  padding: 6px 7px;
  color: #7893a6;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .08em;
}

.document-spine nav button {
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  align-items: start;
  gap: 7px;
  width: 100%;
  padding: 8px 7px;
  color: #aebfcb;
  text-align: left;
  border: 0;
  border-radius: 5px;
  background: transparent;
}

.document-spine nav button:hover,
.document-spine nav button:focus-visible {
  color: #fff;
  outline: none;
  background: rgb(255 255 255 / 7%);
}

.document-spine nav button.active {
  color: #fff;
  background: #0066cc;
  box-shadow: inset 3px 0 #7fd0ae;
}

.document-spine nav button > span {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.document-spine nav strong {
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-spine nav small {
  overflow: hidden;
  color: #7893a6;
  font: 10px Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-spine nav button.active small { color: #d6e8f6; }

.spine-empty {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 9px;
  padding: 24px;
  color: #7893a6;
  text-align: center;
  font-size: 11px;
}

.reading-surface {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  background: #fff;
}

.reading-head {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 16px;
  border-bottom: 1px solid #e6e9ed;
}

.reading-head > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.reading-head span {
  color: #828b95;
  font-size: 10px;
  font-weight: 650;
}

.reading-head strong {
  overflow: hidden;
  color: #344451;
  font: 600 11px "SFMono-Regular", Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reading-head button,
.reader-state button {
  flex: none;
  padding: 6px 9px;
  color: #0066cc;
  border: 1px solid #c7dced;
  border-radius: 5px;
  background: #f5faff;
  font-size: 11px;
}

.reading-head button:hover,
.reading-head button:focus-visible,
.reader-state button:hover,
.reader-state button:focus-visible {
  border-color: #7eafd6;
  outline: none;
  background: #edf6fd;
}

.reader-state {
  display: grid;
  place-content: center;
  justify-items: center;
  padding: 48px;
  color: #0066cc;
  text-align: center;
  background: #fbfcfd;
}

.reader-state h2 {
  margin: 14px 0 6px;
  color: #263744;
  font-size: 18px;
}

.reader-state p {
  max-width: 420px;
  margin: 0 0 18px;
  color: #697580;
  font-size: 12px;
  line-height: 1.6;
}

.reader-state.is-error { color: #b54708; }

@media (max-width: 760px) {
  .document-reader { grid-template-columns: 1fr; min-height: 760px; }
  .document-spine { grid-template-rows: auto auto; }
  .document-spine nav { display: flex; gap: 6px; padding: 8px; overflow-x: auto; }
  .document-spine nav section { display: contents; }
  .document-spine nav section + section { margin-top: 0; }
  .document-spine h2 { display: none; }
  .document-spine nav button { width: 180px; flex: none; }
}
</style>
