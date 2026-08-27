<script setup lang="ts">
import { computed } from 'vue';
import {
  ArrowRight,
  BookOpen,
  GitBranch,
  GitCommitHorizontal,
  RefreshCw,
  Sparkles,
} from 'lucide-vue-next';
import type { ProjectProfile } from '@/api/repositories';
import type { Repository, RepositorySnapshotFiles } from '@/types/api';
import type { ProjectDocument } from './useProjectReadme';

interface Props {
  repository: Repository;
  snapshot: RepositorySnapshotFiles | null;
  profile: ProjectProfile | null;
  primaryDocument: ProjectDocument | null;
  projectTitle: string;
  projectSummary: string;
  loading: boolean;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  refresh: [];
  read: [];
  guide: [];
}>();

const title = computed(() => props.projectTitle || props.repository.name);
const summary = computed(() => props.projectSummary
  || props.repository.description
  || '这个项目还没有可用的介绍。可以从代码和文档生成一份智能导读。');
const languages = computed(() => props.profile?.languages.slice(0, 4) ?? []);
const shortCommit = computed(() => props.snapshot?.commit?.slice(0, 10) ?? '未发布');
</script>

<template>
  <header class="readme-hero">
    <div class="hero-copy">
      <div class="source-line">
        <span><BookOpen :size="14" />{{ primaryDocument?.path ?? '等待项目文档' }}</span>
        <span v-if="primaryDocument" class="source-state">项目原文</span>
      </div>
      <h1>{{ title }}</h1>
      <p>{{ summary }}</p>

      <div class="hero-actions">
        <button v-if="primaryDocument" type="button" class="primary-action" @click="emit('read')">
          阅读项目介绍 <ArrowRight :size="14" />
        </button>
        <button type="button" class="guide-action" @click="emit('guide')">
          <Sparkles :size="14" />生成智能导读
        </button>
      </div>
    </div>

    <aside class="project-stamp" aria-label="当前项目版本">
      <div class="stamp-top">
        <span>PROJECT SNAPSHOT</span>
        <button type="button" title="刷新项目内容" :disabled="loading" @click="emit('refresh')">
          <RefreshCw :size="14" :class="{ spinning: loading }" />
        </button>
      </div>
      <div class="stamp-version">
        <GitBranch :size="16" />
        <span>{{ snapshot?.branch ?? repository.branch ?? '无分支' }}</span>
        <GitCommitHorizontal :size="16" />
        <strong>{{ shortCommit }}</strong>
      </div>
      <div class="language-strip">
        <span v-for="language in languages" :key="language.name">
          {{ language.name }} <b>{{ language.count }}</b>
        </span>
        <span v-if="!languages.length">等待识别技术栈</span>
      </div>
      <p v-if="repository.dirty">工作区有尚未进入当前预览的变更</p>
      <p v-else>内容来自当前只读快照</p>
    </aside>
  </header>
</template>

<style scoped>
.readme-hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(280px, .72fr);
  min-height: 300px;
  overflow: hidden;
  border: 1px solid #d7dce2;
  border-radius: 10px;
  background: #fff;
}

.readme-hero::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 7px;
  content: '';
  background: linear-gradient(180deg, #0066cc 0 58%, #1d8a65 58% 100%);
}

.hero-copy {
  display: grid;
  align-content: center;
  padding: 38px clamp(28px, 4vw, 58px) 38px clamp(34px, 5vw, 72px);
}

.source-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  color: #69737e;
  font: 600 11px "SFMono-Regular", Consolas, monospace;
}

.source-line > span:first-child {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-state {
  flex: none;
  padding: 3px 6px;
  color: #156b50;
  border: 1px solid #bfdfd2;
  border-radius: 999px;
  background: #eff8f4;
  font-family: inherit;
}

.hero-copy h1 {
  max-width: 850px;
  margin: 0;
  color: #17202a;
  font-size: clamp(32px, 4vw, 50px);
  font-weight: 680;
  line-height: 1.06;
  letter-spacing: -.035em;
}

.hero-copy p {
  max-width: 760px;
  margin: 18px 0 0;
  color: #56616d;
  font-size: 15px;
  line-height: 1.75;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin-top: 26px;
}

.hero-actions button {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  gap: 7px;
  padding: 8px 13px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 650;
}

.primary-action {
  color: #fff;
  border: 1px solid #0066cc;
  background: #0066cc;
}

.primary-action:hover,
.primary-action:focus-visible {
  outline: none;
  background: #0057ad;
}

.guide-action {
  color: #31475a;
  border: 1px solid #cfd7de;
  background: #f8fafb;
}

.guide-action:hover,
.guide-action:focus-visible {
  color: #0066cc;
  border-color: #91bce1;
  outline: none;
  background: #f2f8fd;
}

.project-stamp {
  display: grid;
  align-content: center;
  min-width: 0;
  padding: 30px;
  color: #d9e7f2;
  background:
    linear-gradient(rgb(19 35 49 / 96%), rgb(19 35 49 / 96%)),
    repeating-linear-gradient(135deg, transparent 0 16px, rgb(255 255 255 / 4%) 16px 17px);
}

.stamp-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 13px;
  color: #8facbf;
  border-bottom: 1px solid rgb(255 255 255 / 14%);
  font: 700 10px "SFMono-Regular", Consolas, monospace;
  letter-spacing: .12em;
}

.stamp-top button {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  color: #b9d0df;
  border: 0;
  border-radius: 4px;
  background: rgb(255 255 255 / 7%);
}

.stamp-top button:hover,
.stamp-top button:focus-visible {
  color: #fff;
  outline: 1px solid #7daecc;
}

.stamp-version {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  align-items: center;
  gap: 7px 9px;
  padding: 20px 0;
  color: #8facbf;
}

.stamp-version span,
.stamp-version strong {
  overflow: hidden;
  color: #f2f7fa;
  font: 600 12px "SFMono-Regular", Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.language-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.language-strip span {
  padding: 5px 7px;
  color: #c5d7e3;
  border: 1px solid rgb(255 255 255 / 13%);
  border-radius: 4px;
  background: rgb(255 255 255 / 5%);
  font-size: 11px;
}

.language-strip b {
  margin-left: 3px;
  color: #7fd0ae;
}

.project-stamp > p {
  margin: 18px 0 0;
  color: #8facbf;
  font-size: 11px;
}

.spinning { animation: spin .9s linear infinite; }

@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 880px) {
  .readme-hero { grid-template-columns: 1fr; }
  .project-stamp { min-height: 210px; }
}

@media (max-width: 600px) {
  .hero-copy { padding: 30px 24px 32px 30px; }
  .hero-copy h1 { font-size: 32px; }
  .hero-copy p { font-size: 13px; }
  .project-stamp { padding: 24px; }
}

@media (prefers-reduced-motion: reduce) {
  .spinning { animation: none; }
}
</style>
