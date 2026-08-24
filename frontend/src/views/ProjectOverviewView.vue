<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import {
  BookOpenCheck,
  CheckCircle2,
  CircleDotDashed,
  FolderTree,
  GitCommitHorizontal,
  Network,
  RefreshCw,
} from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import AgentContextPackPanel from '@/features/overview/AgentContextPackPanel.vue';
import ModuleSymbolDrawer from '@/features/overview/ModuleSymbolDrawer.vue';
import ProjectAssetsPanel from '@/features/overview/ProjectAssetsPanel.vue';
import ProjectArchitectureMap from '@/features/overview/ProjectArchitectureMap.vue';
import ProjectDecisionBrief from '@/features/overview/ProjectDecisionBrief.vue';
import ProjectStructureRail from '@/features/overview/ProjectStructureRail.vue';
import { useProjectOverview } from '@/features/overview/useProjectOverview';
import { useModuleSymbols } from '@/features/overview/useModuleSymbols';
import type { ProjectArchitectureSymbol } from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';

type ExplorerView = 'architecture' | 'structure' | 'assets';

const router = useRouter();
const repositories = useRepositoryStore();
const explorerView = shallowRef<ExplorerView>('architecture');
const {
  preparation,
  snapshot,
  architecture,
  contextPack,
  loading,
  contextLoading,
  error,
  reload,
  generateContext,
  copyContext,
  downloadContext,
} = useProjectOverview();
const moduleSymbols = useModuleSymbols(() => repositories.selectedRepositoryId);

const repository = computed(() => repositories.selectedRepository);
const profile = computed(() => preparation.value?.profile ?? null);
const shortCommit = computed(() => snapshot.value?.commit?.slice(0, 10) ?? '未发布');
const readiness = computed(() => ({
  READY: {
    label: '可直接使用',
    tone: 'ready',
    summary: '源码检索、项目问答和影响分析已连接到当前快照。',
  },
  DEGRADED: {
    label: '部分可用',
    tone: 'warning',
    summary: '基础检索仍然可用，但部分智能能力需要重新准备。',
  },
  PROCESSING: {
    label: '正在准备',
    tone: 'running',
    summary: preparation.value?.message || '项目知识正在后台构建。',
  },
  ACTION_REQUIRED: {
    label: '需要处理',
    tone: 'danger',
    summary: preparation.value?.message || '准备流程遇到问题，请检查索引任务。',
  },
  NOT_READY: {
    label: '尚未准备',
    tone: 'muted',
    summary: '先生成项目快照与索引，再开始问答和影响分析。',
  },
}[preparation.value?.state ?? 'NOT_READY']));
const taskSuggestions = computed(() => {
  const suggestions: string[] = [];
  const firstModule = profile.value?.modules[0]?.name;
  const firstEntry = profile.value?.entryPoints[0];
  const firstRisk = architecture.value?.risks[0];
  if (firstModule) suggestions.push(`梳理 ${firstModule} 模块的职责、入口和外部依赖`);
  if (firstEntry) suggestions.push(`解释 ${firstEntry} 的启动流程和关键调用`);
  if (firstRisk) suggestions.push(`分析“${firstRisk.title}”的影响和处理方案`);
  if (!suggestions.length) suggestions.push('这个项目的核心业务流程从哪里开始？');
  return suggestions.slice(0, 3);
});
const explorerTabs: Array<{ key: ExplorerView; label: string; detail: string; icon: typeof Network }> = [
  { key: 'architecture', label: '系统关系', detail: '模块依赖与外部资源', icon: Network },
  { key: 'structure', label: '代码地形', detail: '模块规模与入口文件', icon: FolderTree },
  { key: 'assets', label: '知识入口', detail: '文档、规则与配置', icon: BookOpenCheck },
];

function openModule(path: string) {
  void router.push({ path: '/search', query: { q: path } });
}

function openSymbolGraph(symbol?: ProjectArchitectureSymbol) {
  moduleSymbols.close();
  if (!symbol) {
    void router.push('/graph');
    return;
  }
  void router.push({
    name: 'graph',
    query: { symbol: symbol.symbolName, depth: '3', analyze: '1' },
  });
}

function openSymbolFile(symbol: ProjectArchitectureSymbol) {
  moduleSymbols.close();
  void router.push({
    path: '/search',
    query: {
      path: symbol.filePath,
      startLine: symbol.startLine ?? undefined,
      endLine: symbol.endLine ?? undefined,
    },
  });
}

function openFile(path: string) {
  void router.push({ path: '/search', query: { path } });
}

function openImpact(task: string) {
  void router.push({ name: 'change-impact', query: task ? { task } : {} });
}

function openSearch(task: string) {
  void router.push({ name: 'search', query: task ? { q: task } : {} });
}

function navigateFromBrief(target: 'architecture' | 'structure' | 'assets' | 'indexing' | 'ask') {
  if (target === 'indexing') {
    void router.push('/indexing');
    return;
  }
  if (target === 'ask') {
    openImpact('');
    return;
  }
  explorerView.value = target;
  requestAnimationFrame(() => document.querySelector('.project-explorer')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
}
</script>

<template>
  <section class="overview-page">
    <div v-if="!repositories.selectedRepositoryId" class="overview-empty">
      <span class="empty-mark"><FolderTree :size="28" /></span>
      <span class="eyebrow">未选择仓库</span>
      <h1>先选择一个要理解的项目</h1>
      <p>项目总览只展示当前仓库的代码、规则、架构关系和知识准备状态。</p>
      <el-button type="primary" @click="router.push('/repositories')">前往仓库管理</el-button>
    </div>

    <el-alert v-else-if="error" :title="error" type="error" :closable="false">
      <el-button size="small" @click="reload">重新加载</el-button>
    </el-alert>

    <div v-else v-loading="loading" class="overview-content">
      <header class="project-brief">
        <div class="project-identity">
          <span class="eyebrow">当前项目</span>
          <div class="project-title-line">
            <h1>{{ repository?.name ?? '项目总览' }}</h1>
            <span class="readiness-pill" :data-tone="readiness.tone">
              <CheckCircle2 v-if="readiness.tone === 'ready'" :size="13" />
              <CircleDotDashed v-else :size="13" />
              {{ readiness.label }}
            </span>
          </div>
          <p>{{ repository?.description || '当前仓库没有项目说明。建议补充目标、边界和主要使用者。' }}</p>
        </div>

        <div class="snapshot-brief" aria-label="当前代码版本">
          <GitCommitHorizontal :size="18" />
          <div>
            <span>{{ snapshot?.branch ?? repository?.branch ?? '无分支' }}</span>
            <strong>{{ shortCommit }}</strong>
          </div>
          <span v-if="repository?.dirty" class="dirty-state">工作区有变更</span>
          <button type="button" title="刷新项目总览" @click="reload">
            <RefreshCw :size="15" />
          </button>
        </div>
      </header>

      <ProjectDecisionBrief
        v-if="profile && preparation && repository"
        :repository="repository"
        :preparation="preparation"
        :snapshot="snapshot"
        :architecture="architecture"
        :profile="profile"
        @navigate="navigateFromBrief"
        @open-file="openFile"
        @open-module="moduleSymbols.open"
        @ask="openImpact"
      />

      <AgentContextPackPanel
        v-if="profile"
        :pack="contextPack"
        :busy="contextLoading"
        :disabled="!profile.chunkCount"
        :suggestions="taskSuggestions"
        @analyze="openImpact"
        @search="openSearch"
        @generate="generateContext"
        @copy="copyContext"
        @download="downloadContext"
        @open-file="openFile"
      />

      <section v-if="profile" class="project-explorer">
        <header class="explorer-head">
          <div>
            <span class="eyebrow">项目导航</span>
            <h2>从系统边界开始理解项目</h2>
            <p>先看关系，再进入模块和事实来源；每个节点都可以继续下钻到源码。</p>
          </div>
          <div class="explorer-tabs" role="tablist" aria-label="项目探索视图">
            <button
              v-for="tab in explorerTabs"
              :key="tab.key"
              type="button"
              role="tab"
              :aria-selected="explorerView === tab.key"
              :class="{ active: explorerView === tab.key }"
              @click="explorerView = tab.key"
            >
              <component :is="tab.icon" :size="15" />
              <span><strong>{{ tab.label }}</strong><small>{{ tab.detail }}</small></span>
            </button>
          </div>
        </header>

        <div class="explorer-body">
          <ProjectArchitectureMap
            v-if="explorerView === 'architecture'"
            :map="architecture"
            @open-module="openModule"
            @open-symbols="moduleSymbols.open"
            @open-file="openFile"
            @open-graph="openSymbolGraph"
          />
          <ProjectStructureRail
            v-else-if="explorerView === 'structure'"
            :repository-name="repository?.name ?? '当前项目'"
            :modules="profile.modules"
            :entry-points="profile.entryPoints"
            @open-file="openFile"
          />
          <ProjectAssetsPanel
            v-else
            :assets="profile.assets"
            :key-assets="profile.keyAssets"
            :stages="preparation?.stages ?? []"
            @open-asset="openFile"
          />
        </div>
      </section>

    </div>

    <ModuleSymbolDrawer
      :visible="moduleSymbols.visible.value"
      :loading="moduleSymbols.loading.value"
      :error="moduleSymbols.error.value"
      :data="moduleSymbols.data.value"
      @close="moduleSymbols.close"
      @open-graph="openSymbolGraph"
      @open-file="openSymbolFile"
    />
  </section>
</template>

<style scoped>
.overview-page {
  --overview-primary: var(--el-color-primary, #0066cc);
  --overview-text: var(--el-text-color-primary, #1d1d1f);
  --overview-regular: var(--el-text-color-regular, #4a4a4f);
  --overview-muted: var(--app-text-muted);
  --overview-border: var(--el-border-color, #dedee3);
  --overview-soft: #f7f8fa;
  --overview-success: #16855b;
  --overview-warning: #b75b00;
  --overview-danger: #c23e3e;
  min-height: 100%;
  overflow: auto;
  color: var(--overview-text);
}
.overview-content { display: grid; gap: 12px; min-height: 100%; padding-bottom: 20px; }
.eyebrow { color: var(--overview-muted); font-size: 11px; font-weight: 650; letter-spacing: .04em; }
.project-brief { display: flex; min-height: 104px; align-items: center; justify-content: space-between; gap: 24px; padding: 18px 20px; border: 1px solid var(--overview-border); border-radius: 7px; background: #fff; }
.project-identity { min-width: 0; }
.project-title-line { display: flex; align-items: center; gap: 12px; margin: 6px 0; }
.project-title-line h1 { margin: 0; overflow: hidden; color: var(--overview-text); font-size: 27px; font-weight: 650; line-height: 1.15; text-overflow: ellipsis; white-space: nowrap; }
.project-identity p { max-width: 760px; margin: 0; color: var(--overview-muted); font-size: 12px; line-height: 1.6; }
.readiness-pill { display: inline-flex; flex: none; align-items: center; gap: 5px; padding: 4px 8px; color: #66666d; border: 1px solid var(--overview-border); border-radius: 999px; background: #fafafa; font-size: 11px; font-weight: 600; }
.readiness-pill[data-tone='ready'] { color: var(--overview-success); border-color: #b9ddce; background: #eef8f3; }
.readiness-pill[data-tone='warning'], .readiness-pill[data-tone='running'] { color: var(--overview-warning); border-color: #ead0ac; background: #fff8ed; }
.readiness-pill[data-tone='danger'] { color: var(--overview-danger); border-color: #ebc7c7; background: #fff3f3; }
.snapshot-brief { display: grid; grid-template-columns: 24px minmax(120px, auto) auto 30px; align-items: center; gap: 9px; padding: 9px 10px; color: #65656c; border: 1px solid var(--overview-border); border-radius: 6px; background: #fafafa; }
.snapshot-brief > div { display: grid; gap: 3px; }
.snapshot-brief span { color: var(--overview-muted); font-size: 11px; }
.snapshot-brief strong { color: var(--overview-text); font: 600 11px "SFMono-Regular", Consolas, monospace; }
.snapshot-brief .dirty-state { padding: 3px 6px; color: var(--overview-warning); border-radius: 4px; background: #f8ead5; white-space: nowrap; }
.snapshot-brief button { display: grid; width: 30px; height: 30px; place-items: center; color: #65656c; border: 0; border-radius: 5px; background: transparent; }
.snapshot-brief button:hover, .snapshot-brief button:focus-visible { color: var(--overview-primary); outline: none; background: #eaf3fd; }
.project-explorer { min-width: 0; overflow: hidden; border: 1px solid var(--overview-border); border-radius: 7px; background: #fff; }
.explorer-head { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 16px; border-bottom: 1px solid #ececef; }
.explorer-head h2 { margin: 5px 0 3px; color: var(--overview-text); font-size: 18px; font-weight: 650; }
.explorer-head p { margin: 0; color: var(--overview-muted); font-size: 12px; }
.explorer-tabs { display: grid; grid-template-columns: repeat(3, minmax(150px, 1fr)); gap: 3px; padding: 3px; border-radius: 6px; background: #f1f1f4; }
.explorer-tabs button { display: grid; grid-template-columns: 22px minmax(0, 1fr); align-items: center; gap: 6px; min-height: 42px; padding: 6px 9px; color: #626269; text-align: left; border: 0; border-radius: 5px; background: transparent; }
.explorer-tabs button:hover { color: var(--overview-text); background: rgb(255 255 255 / 65%); }
.explorer-tabs button.active { color: var(--overview-primary); background: #fff; box-shadow: 0 1px 3px rgb(24 39 58 / 12%); }
.explorer-tabs button:focus-visible { outline: 2px solid rgb(0 102 204 / 25%); outline-offset: 1px; }
.explorer-tabs button > span { display: grid; gap: 2px; }
.explorer-tabs strong { font-size: 12px; }
.explorer-tabs small { color: var(--app-text-muted); font-size: 11px; }
.explorer-body { min-width: 0; background: var(--overview-soft); }
.explorer-body :deep(.architecture-panel), .explorer-body :deep(.structure-panel), .explorer-body :deep(.assets-panel) { border: 0; border-radius: 0; }
.overview-empty { display: grid; min-height: 520px; place-content: center; justify-items: center; padding: 40px; text-align: center; border: 1px dashed #c9c9cf; border-radius: 7px; background: #fff; }
.empty-mark { display: grid; width: 60px; height: 60px; place-items: center; margin-bottom: 16px; color: var(--overview-primary); border-radius: 14px; background: #eaf3fd; }
.overview-empty h1 { margin: 8px 0; color: var(--overview-text); font-size: 24px; font-weight: 650; }
.overview-empty p { max-width: 460px; margin: 0 0 20px; color: var(--overview-muted); font-size: 12px; line-height: 1.6; }
@media (max-width: 1120px) { .explorer-head { align-items: stretch; flex-direction: column; } .explorer-tabs { width: 100%; } }
@media (max-width: 760px) {
  .overview-content { gap: 10px; }
  .project-brief { align-items: stretch; flex-direction: column; padding: 16px; }
  .project-title-line { align-items: flex-start; flex-direction: column; }
  .project-title-line h1 { max-width: 100%; font-size: 24px; white-space: normal; }
  .snapshot-brief { grid-template-columns: 24px 1fr auto; }
  .snapshot-brief .dirty-state { display: none; }
  .explorer-tabs { grid-template-columns: 1fr; }
  .explorer-tabs button { min-height: 38px; }
  .explorer-tabs small { display: none; }
}
</style>
