<script setup lang="ts">
import { computed } from 'vue';
import {
  AlertCircle,
  BookOpenCheck,
  Braces,
  Check,
  CircleDot,
  Code2,
  Database,
  Files,
  GitBranch,
  Languages,
  Network,
  RefreshCw,
} from 'lucide-vue-next';
import type { ProjectCodeFacts, ProjectProfile, RepositoryPreparation } from '@/api/repositories';
import type { Repository, RepositoryFileContent } from '@/types/api';
import ProjectDocumentReader from './ProjectDocumentReader.vue';

interface Props {
  repository: Repository;
  preparation: RepositoryPreparation | null;
  profile: ProjectProfile | null;
  codeFacts: ProjectCodeFacts | null;
  readmeFile: RepositoryFileContent | null;
  readmeLoading: boolean;
  readmeError: string | null;
  loading: boolean;
  preparing: boolean;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  refresh: [];
  prepare: [];
  openFile: [path: string];
  generateGuide: [];
}>();

const STATE_COPY: Record<string, { label: string; tone: string }> = {
  READY: { label: '已准备', tone: 'ready' },
  DEGRADED: { label: '降级可用', tone: 'warning' },
  PROCESSING: { label: '准备中', tone: 'running' },
  ACTION_REQUIRED: { label: '需要处理', tone: 'danger' },
  NOT_READY: { label: '尚未准备', tone: 'muted' },
};

const state = computed(() => props.preparation?.state ?? 'NOT_READY');
const stateCopy = computed(() => STATE_COPY[state.value] ?? STATE_COPY.NOT_READY);
const vectorCoverage = computed(() => {
  if (!props.profile?.chunkCount) return 0;
  return Math.round(props.profile.vectorizedChunks / props.profile.chunkCount * 100);
});
const languageMaximum = computed(() => Math.max(
  1,
  ...(props.profile?.languages.map(item => item.count) ?? []),
));
const languages = computed(() => props.profile?.languages.slice(0, 6) ?? []);
const technologies = computed(() => props.codeFacts?.technologies.slice(0, 10) ?? []);
const projectDescription = computed(() => (
  props.repository.description
  || props.codeFacts?.projectType
  || '从仓库 README 出发，理解代码、知识与关系。'
));
const prepareLabel = computed(() => {
  if (state.value === 'NOT_READY') return '开始准备';
  if (state.value === 'PROCESSING') return '继续准备';
  if (state.value === 'READY') return '检查更新';
  return '重新准备';
});

function bytes(value: number) {
  if (value < 1024) return value + ' B';
  if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KiB';
  return (value / 1024 / 1024).toFixed(1) + ' MiB';
}

function languageWidth(value: number) {
  return Math.max(7, Math.round(value / languageMaximum.value * 100)) + '%';
}

function stageTone(stageState: string) {
  if (stageState === 'READY') return 'ready';
  if (stageState === 'RUNNING') return 'running';
  if (stageState === 'FAILED' || stageState === 'DEGRADED') return 'danger';
  return 'muted';
}
</script>

<template>
  <article class="overview-sheet">
    <aside class="project-dossier">
      <header class="project-header">
        <div class="project-title-line">
          <span class="project-mark"><Code2 :size="21" /></span>
          <h1>{{ repository.name }}</h1>
        </div>

        <div class="project-meta">
          <span class="branch-label">
            <GitBranch :size="13" />
            {{ repository.branch ?? '无分支' }}
          </span>
          <span class="state-label" :data-tone="stateCopy.tone">
            <i></i>
            {{ stateCopy.label }} · {{ preparation?.progress ?? 0 }}%
          </span>
        </div>

        <p class="project-description">{{ projectDescription }}</p>

        <div class="project-actions">
          <button
            v-if="repository.capabilities?.canIndex"
            type="button"
            class="primary-action"
            :disabled="loading || preparing"
            @click="emit('prepare')"
          >
            {{ prepareLabel }}
            <RefreshCw :size="14" :class="{ spinning: preparing }" />
          </button>
          <button
            type="button"
            class="secondary-action"
            :disabled="loading || preparing"
            @click="emit('refresh')"
          >
            <RefreshCw :size="13" :class="{ spinning: loading }" />
            刷新数据
          </button>
        </div>

        <p class="snapshot-note">
          {{ profile?.fileCount ?? 0 }} 个快照文件
          <span>·</span>
          {{ bytes(profile?.totalBytes ?? 0) }}
        </p>
      </header>

      <section class="dossier-section data-section" aria-labelledby="project-data-title">
        <header class="dossier-heading">
          <span>PROJECT DATA</span>
          <h2 id="project-data-title">项目数据</h2>
        </header>

        <div class="data-ledger">
          <article class="ledger-row">
            <Network :size="18" />
            <div class="ledger-label">
              <strong>CodeGraph</strong>
              <small>代码关系</small>
            </div>
            <div class="ledger-value">
              <strong>{{ profile?.graphNodes ?? 0 }}</strong>
              <span>节点 / {{ profile?.graphEdges ?? 0 }} 关系</span>
            </div>
          </article>

          <article class="ledger-row">
            <Database :size="18" />
            <div class="ledger-label">
              <strong>向量数据</strong>
              <small>{{ vectorCoverage }}% 覆盖</small>
            </div>
            <div class="ledger-value">
              <strong>{{ profile?.vectorizedChunks ?? 0 }} / {{ profile?.chunkCount ?? 0 }}</strong>
              <span>已完成向量化</span>
            </div>
          </article>

          <article class="ledger-row">
            <BookOpenCheck :size="18" />
            <div class="ledger-label">
              <strong>知识数据</strong>
              <small>项目知识</small>
            </div>
            <div class="ledger-value">
              <strong>{{ profile?.knowledgeCards ?? 0 }}</strong>
              <span>卡片 / {{ profile?.chunkCount ?? 0 }} 片段</span>
            </div>
          </article>

          <article class="ledger-row">
            <Files :size="18" />
            <div class="ledger-label">
              <strong>代码快照</strong>
              <small>当前版本</small>
            </div>
            <div class="ledger-value">
              <strong>{{ codeFacts?.codeFileCount ?? 0 }}</strong>
              <span>代码 / {{ profile?.fileCount ?? 0 }} 全部</span>
            </div>
          </article>
        </div>
      </section>

      <section class="dossier-section readiness-section" aria-labelledby="readiness-title">
        <header class="dossier-heading">
          <span>READINESS</span>
          <h2 id="readiness-title">准备流程</h2>
        </header>

        <div v-if="preparation?.stages.length" class="preparation-track">
          <article
            v-for="stage in preparation.stages"
            :key="stage.key"
            class="preparation-stage"
            :data-tone="stageTone(stage.state)"
          >
            <span class="stage-marker">
              <Check v-if="stage.state === 'READY'" :size="12" />
              <AlertCircle
                v-else-if="stage.state === 'FAILED' || stage.state === 'DEGRADED'"
                :size="12"
              />
              <CircleDot v-else :size="12" />
            </span>
            <div class="stage-copy">
              <strong>{{ stage.label }}</strong>
              <small>{{ stage.detail }}</small>
            </div>
          </article>
        </div>
        <p v-else class="empty-copy">准备后将显示快照、内容、向量与图谱状态。</p>
      </section>

      <section class="dossier-section language-section" aria-labelledby="language-title">
        <header class="dossier-heading heading-with-icon">
          <Languages :size="15" />
          <div>
            <span>LANGUAGES</span>
            <h2 id="language-title">语言构成</h2>
          </div>
        </header>

        <div v-if="languages.length" class="language-list">
          <article v-for="language in languages" :key="language.name" class="language-item">
            <div>
              <span>{{ language.name }}</span>
              <strong>{{ language.count }}</strong>
            </div>
            <i><b :style="{ width: languageWidth(language.count) }"></b></i>
          </article>
        </div>
        <p v-else class="empty-copy">当前快照没有可归类的文本文件。</p>
      </section>

      <section class="dossier-section technology-section" aria-labelledby="technology-title">
        <header class="dossier-heading heading-with-icon">
          <Braces :size="15" />
          <div>
            <span>STACK</span>
            <h2 id="technology-title">技术栈</h2>
          </div>
        </header>

        <div v-if="technologies.length" class="technology-list">
          <button
            v-for="technology in technologies"
            :key="technology.name"
            type="button"
            :disabled="!technology.evidencePaths.length"
            :title="technology.evidencePaths[0] ?? technology.detail"
            @click="technology.evidencePaths[0] && emit('openFile', technology.evidencePaths[0])"
          >
            <span>{{ technology.name }}</span>
            <small>{{ technology.category }}</small>
            <em>{{ technology.confidence === 'HIGH' ? '已确认' : '推断' }}</em>
          </button>
        </div>
        <p v-else class="empty-copy">未从源码或依赖清单中识别到技术栈。</p>
      </section>
    </aside>

    <ProjectDocumentReader
      :repository-id="repository.id"
      :file="readmeFile"
      :loading="readmeLoading"
      :error="readmeError"
      @open-file="emit('openFile', $event)"
      @guide="emit('generateGuide')"
    />
  </article>
</template>

<style scoped>
.overview-sheet {
  --navy: #153249;
  --ink: #1a2934;
  --text: #4f606c;
  --muted: #80909b;
  --line: #dce4e9;
  --soft: #f6f9fa;
  --blue: #2d82bd;
  --green: #218b61;
  display: grid;
  grid-template-columns: minmax(330px, 380px) minmax(0, 1fr);
  min-width: 0;
  height: 100%;
  min-height: 0;
  align-items: stretch;
  overflow: hidden;
  color: var(--ink);
  background: #fff;
  font-family: Inter, "Microsoft YaHei", sans-serif;
}

.overview-sheet button {
  font: inherit;
}

.overview-sheet button:disabled {
  cursor: not-allowed;
  opacity: .48;
}

.project-dossier {
  min-width: 0;
  height: 100%;
  min-height: 0;
  padding: 30px 28px 38px;
  overflow-y: auto;
  border-right: 1px solid var(--line);
  background: #f8fafb;
  overscroll-behavior: contain;
  scrollbar-color: #becbd3 transparent;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.project-dossier::-webkit-scrollbar { width: 8px; }
.project-dossier::-webkit-scrollbar-track { background: transparent; }
.project-dossier::-webkit-scrollbar-thumb { border-radius: 4px; background: #becbd3; }

.project-header {
  padding-bottom: 27px;
  border-bottom: 1px solid #cfd9df;
}

.project-title-line {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
}

.project-mark {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  color: #fff;
  border-radius: 5px;
  background: var(--navy);
}

.project-title-line > h1 {
  min-width: 0;
  overflow: hidden;
  margin: 0;
  color: #111f2a;
  font-size: clamp(24px, 2.4vw, 31px);
  font-weight: 740;
  letter-spacing: -.035em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-top: 17px;
}

.branch-label,
.state-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #526775;
  font: 600 10px/1.4 "SFMono-Regular", Consolas, monospace;
}

.state-label {
  color: #697781;
}

.state-label > i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}

.state-label[data-tone='ready'] {
  color: var(--green);
}

.state-label[data-tone='warning'] {
  color: #a85b00;
}

.state-label[data-tone='running'] {
  color: var(--blue);
}

.state-label[data-tone='danger'] {
  color: #b5473d;
}

.project-description {
  margin: 15px 0 0;
  color: #5c6d78;
  font-size: 12px;
  line-height: 1.7;
}

.project-actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 9px;
  margin-top: 20px;
}

.project-actions > button {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 680;
  cursor: pointer;
}

.primary-action {
  color: #fff;
  border: 1px solid var(--blue);
  background: var(--blue);
}

.secondary-action {
  padding: 7px 10px;
  color: #31536d;
  border: 1px solid #c5d2da;
  background: #fff;
}

.secondary-action:only-child {
  grid-column: 1 / -1;
}

.project-actions > button:hover,
.project-actions > button:focus-visible,
.technology-list > button:hover,
.technology-list > button:focus-visible {
  outline: 2px solid rgb(45 130 189 / 18%);
  outline-offset: 2px;
}

.snapshot-note {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin: 12px 0 0;
  color: #86939c;
  font: 500 9px/1.4 "SFMono-Regular", Consolas, monospace;
}

.dossier-section {
  padding: 25px 0;
  border-bottom: 1px solid #d6dfe4;
}

.dossier-section:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.dossier-heading {
  display: grid;
  gap: 3px;
  margin-bottom: 15px;
}

.dossier-heading > span,
.heading-with-icon span {
  color: var(--blue);
  font: 700 8px/1.2 "SFMono-Regular", Consolas, monospace;
  letter-spacing: .14em;
}

.dossier-heading > h2,
.heading-with-icon h2 {
  margin: 0;
  color: var(--navy);
  font-size: 14px;
  font-weight: 720;
}

.heading-with-icon {
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  gap: 9px;
  color: var(--blue);
}

.heading-with-icon > div {
  display: grid;
  gap: 3px;
}

.data-ledger {
  border-top: 1px solid #d6dfe4;
}

.ledger-row {
  display: grid;
  grid-template-columns: 24px minmax(84px, .85fr) minmax(0, 1.2fr);
  min-height: 64px;
  align-items: center;
  gap: 8px;
  color: var(--blue);
  border-bottom: 1px solid #dfe6ea;
}

.ledger-row:last-child {
  border-bottom: 0;
}

.ledger-label,
.ledger-value {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.ledger-label > strong {
  color: #283b48;
  font-size: 11px;
}

.ledger-label > small {
  color: #8b98a1;
  font-size: 8px;
}

.ledger-value {
  justify-items: end;
  text-align: right;
}

.ledger-value > strong {
  color: #152b3b;
  font: 680 14px/1.3 "SFMono-Regular", Consolas, monospace;
}

.ledger-value > span {
  color: #74838d;
  font-size: 8px;
}

.preparation-track {
  position: relative;
  display: grid;
  gap: 14px;
}

.preparation-track::before {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 10px;
  width: 1px;
  content: '';
  background: #bfcbd2;
}

.preparation-stage {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 21px minmax(0, 1fr);
  align-items: start;
  gap: 11px;
}

.stage-marker {
  display: grid;
  width: 21px;
  height: 21px;
  place-items: center;
  color: #85929a;
  border: 1px solid #c7d1d7;
  border-radius: 50%;
  background: #f8fafb;
}

.preparation-stage[data-tone='ready'] .stage-marker {
  color: #fff;
  border-color: var(--green);
  background: var(--green);
}

.preparation-stage[data-tone='running'] .stage-marker {
  color: #fff;
  border-color: var(--blue);
  background: var(--blue);
}

.preparation-stage[data-tone='danger'] .stage-marker {
  color: #fff;
  border-color: #b5473d;
  background: #b5473d;
}

.stage-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.stage-copy > strong {
  color: #334752;
  font-size: 10px;
}

.stage-copy > small {
  overflow: hidden;
  color: #85939c;
  font-size: 8px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.language-list {
  display: grid;
  gap: 11px;
}

.language-item {
  display: grid;
  gap: 5px;
}

.language-item > div {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 12px;
}

.language-item span,
.language-item strong {
  overflow: hidden;
  color: #465a67;
  font: 600 9px/1.4 "SFMono-Regular", Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.language-item strong {
  color: #71818b;
}

.language-item > i {
  display: block;
  height: 3px;
  overflow: hidden;
  background: #e1e7eb;
}

.language-item b {
  display: block;
  height: 100%;
  background: var(--blue);
}

.technology-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.technology-list > button {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  min-width: 0;
  align-items: center;
  gap: 3px 7px;
  min-height: 41px;
  padding: 7px 0;
  color: #263b49;
  text-align: left;
  border: 0;
  border-bottom: 1px solid #e0e6ea;
  background: transparent;
  cursor: pointer;
}

.technology-list span {
  overflow: hidden;
  font-size: 10px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.technology-list small {
  grid-row: 2;
  color: #8a969e;
  font: 7px/1.3 "SFMono-Regular", Consolas, monospace;
}

.technology-list em {
  grid-column: 2;
  grid-row: 1 / 3;
  color: var(--green);
  font-size: 7px;
  font-style: normal;
}

.empty-copy {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.6;
}

.spinning {
  animation: spin .85s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1080px) {
  .overview-sheet {
    grid-template-columns: minmax(305px, 330px) minmax(0, 1fr);
  }

  .project-dossier {
    padding-right: 22px;
    padding-left: 22px;
  }

  .technology-list {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .overview-sheet {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 100%;
    overflow: visible;
  }

  .project-dossier {
    height: auto;
    padding: 24px 20px 30px;
    overflow: visible;
    border-right: 0;
    border-bottom: 1px solid var(--line);
    scrollbar-gutter: auto;
  }

  .data-ledger {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ledger-row:nth-child(odd) {
    padding-right: 14px;
    border-right: 1px solid #dfe6ea;
  }

  .ledger-row:nth-child(even) {
    padding-left: 14px;
  }

  .technology-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .project-actions {
    grid-template-columns: 1fr;
  }

  .data-ledger,
  .technology-list {
    grid-template-columns: 1fr;
  }

  .ledger-row:nth-child(odd) {
    padding-right: 0;
    border-right: 0;
  }

  .ledger-row:nth-child(even) {
    padding-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinning {
    animation: none;
  }
}
</style>