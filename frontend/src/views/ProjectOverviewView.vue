<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import {
  BookOpenCheck,
  ChevronDown,
  FolderTree,
  Network,
  Sparkles,
} from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import AgentContextPackPanel from '@/features/overview/AgentContextPackPanel.vue';
import ModuleSymbolDrawer from '@/features/overview/ModuleSymbolDrawer.vue';
import ProjectArchitectureMap from '@/features/overview/ProjectArchitectureMap.vue';
import ProjectAssetsPanel from '@/features/overview/ProjectAssetsPanel.vue';
import ProjectDocumentReader from '@/features/overview/ProjectDocumentReader.vue';
import ProjectGuideRail from '@/features/overview/ProjectGuideRail.vue';
import ProjectReadmeHero from '@/features/overview/ProjectReadmeHero.vue';
import ProjectStructureRail from '@/features/overview/ProjectStructureRail.vue';
import { useModuleSymbols } from '@/features/overview/useModuleSymbols';
import { useProjectOverview } from '@/features/overview/useProjectOverview';
import { useProjectReadme } from '@/features/overview/useProjectReadme';
import type { ProjectArchitectureSymbol } from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';

type AnalysisView = 'architecture' | 'structure' | 'assets';

const router = useRouter();
const repositories = useRepositoryStore();
const analysisView = shallowRef<AnalysisView>('architecture');
const advancedOpen = shallowRef(false);
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
const readme = useProjectReadme(
  () => repositories.selectedRepositoryId,
  () => snapshot.value,
);
const moduleSymbols = useModuleSymbols(() => repositories.selectedRepositoryId);

const repository = computed(() => repositories.selectedRepository);
const profile = computed(() => preparation.value?.profile ?? null);
const taskSuggestions = computed(() => {
  const suggestions: string[] = [];
  const firstEntry = profile.value?.entryPoints[0];
  const firstModule = profile.value?.modules[0]?.name;
  if (firstEntry) suggestions.push(`解释 ${firstEntry} 的启动流程和关键调用`);
  if (firstModule) suggestions.push(`梳理 ${firstModule} 模块的职责、入口和外部依赖`);
  suggestions.push('总结这个项目的核心业务流程、运行方式和推荐阅读顺序');
  return suggestions.slice(0, 3);
});
const analysisTabs: Array<{ key: AnalysisView; label: string; icon: typeof Network }> = [
  { key: 'architecture', label: '架构关系', icon: Network },
  { key: 'structure', label: '代码结构', icon: FolderTree },
  { key: 'assets', label: '知识资产', icon: BookOpenCheck },
];

function scrollToReadme() {
  document.querySelector('#project-readme')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function openSmartGuide() {
  const name = repository.value?.name ?? '当前项目';
  const source = readme.primaryDocument.value?.path ?? '现有文档和代码';
  void router.push({
    name: 'ask',
    query: {
      q: `请以新接手项目的开发者为读者，基于 ${source}、相关 Markdown 和代码证据，生成一份 ${name} 项目导读：说明项目目标、核心功能、技术栈、运行方式、主要模块、关键业务流程和推荐阅读顺序。所有结论都附来源。`,
    },
  });
}

function openFile(path: string) {
  void router.push({ path: '/search', query: { path } });
}

function openModule(path: string) {
  void router.push({ path: '/search', query: { q: path } });
}

function openImpact(task: string) {
  void router.push({ name: 'change-impact', query: task ? { task } : {} });
}

function openSearch(task: string) {
  void router.push({ name: 'search', query: task ? { q: task } : {} });
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
</script>

<template>
  <section class="overview-page">
    <div v-if="!repositories.selectedRepositoryId" class="overview-empty">
      <span class="empty-mark"><FolderTree :size="28" /></span>
      <span class="eyebrow">未选择仓库</span>
      <h1>先选择一个要了解的项目</h1>
      <p>选择仓库后，这里会优先展示项目自己的 README 和相关说明文档。</p>
      <el-button type="primary" @click="router.push('/repositories')">前往仓库管理</el-button>
    </div>

    <el-alert v-else-if="error" :title="error" type="error" :closable="false">
      <el-button size="small" @click="reload">重新加载</el-button>
    </el-alert>

    <div v-else-if="repository" v-loading="loading" class="overview-content">
      <ProjectReadmeHero
        :repository="repository"
        :snapshot="snapshot"
        :profile="profile"
        :primary-document="readme.primaryDocument.value"
        :project-title="readme.projectTitle.value"
        :project-summary="readme.projectSummary.value"
        :loading="loading"
        @refresh="reload"
        @read="scrollToReadme"
        @guide="openSmartGuide"
      />

      <div class="reading-layout">
        <ProjectDocumentReader
          :repository-id="repository.id"
          :documents="readme.documents.value"
          :selected-path="readme.selectedPath.value"
          :file="readme.selectedFile.value"
          :loading="readme.loading.value"
          :error="readme.error.value"
          @select="readme.openDocument"
          @open-file="openFile"
          @guide="openSmartGuide"
        />

        <ProjectGuideRail
          :profile="profile"
          :commands="readme.commands.value"
          @open-file="openFile"
          @open-module="openModule"
          @guide="openSmartGuide"
        />
      </div>

      <section v-if="profile" class="advanced-analysis" :class="{ open: advancedOpen }">
        <button type="button" class="advanced-toggle" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">
          <span class="advanced-mark"><Sparkles :size="16" /></span>
          <span>
            <strong>深入分析</strong>
            <small>查看架构关系、代码结构、知识资产和 Agent 上下文</small>
          </span>
          <ChevronDown :size="17" />
        </button>

        <div v-if="advancedOpen" class="advanced-content">
          <div class="analysis-tabs" role="tablist" aria-label="项目深入分析">
            <button
              v-for="tab in analysisTabs"
              :key="tab.key"
              type="button"
              role="tab"
              :aria-selected="analysisView === tab.key"
              :class="{ active: analysisView === tab.key }"
              @click="analysisView = tab.key"
            >
              <component :is="tab.icon" :size="14" />{{ tab.label }}
            </button>
          </div>

          <ProjectArchitectureMap
            v-if="analysisView === 'architecture'"
            :map="architecture"
            @open-module="openModule"
            @open-symbols="moduleSymbols.open"
            @open-file="openFile"
            @open-graph="openSymbolGraph"
          />
          <ProjectStructureRail
            v-else-if="analysisView === 'structure'"
            :repository-name="repository.name"
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

          <AgentContextPackPanel
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
  min-height: 100%;
  overflow: auto;
  color: #1d2730;
}

.overview-content {
  display: grid;
  gap: 12px;
  min-height: 100%;
  padding-bottom: 24px;
}

.reading-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  align-items: start;
  gap: 12px;
  min-width: 0;
}

.advanced-analysis {
  overflow: hidden;
  border: 1px solid #d7dce2;
  border-radius: 8px;
  background: #fff;
}

.advanced-toggle {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 64px;
  padding: 10px 14px;
  color: #51606c;
  text-align: left;
  border: 0;
  background: #fff;
}

.advanced-toggle:hover,
.advanced-toggle:focus-visible {
  color: #0066cc;
  outline: none;
  background: #f7fafc;
}

.advanced-toggle > span:nth-child(2) {
  display: grid;
  gap: 3px;
}

.advanced-toggle strong { color: #2f3e49; font-size: 12px; }
.advanced-toggle small { color: #7a858e; font-size: 11px; }
.advanced-toggle > svg { transition: transform .18s ease; }
.advanced-analysis.open .advanced-toggle > svg { transform: rotate(180deg); }

.advanced-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #0066cc;
  border-radius: 6px;
  background: #eaf3fd;
}

.advanced-content {
  display: grid;
  gap: 10px;
  padding: 10px;
  border-top: 1px solid #e7eaed;
  background: #f6f8fa;
}

.analysis-tabs {
  display: flex;
  width: max-content;
  max-width: 100%;
  gap: 3px;
  padding: 3px;
  border-radius: 6px;
  background: #e9edf0;
}

.analysis-tabs button {
  display: flex;
  min-height: 31px;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  color: #65717b;
  border: 0;
  border-radius: 4px;
  background: transparent;
  font-size: 11px;
}

.analysis-tabs button.active { color: #0066cc; background: #fff; box-shadow: 0 1px 3px rgb(32 50 64 / 10%); }
.analysis-tabs button:focus-visible { outline: 2px solid rgb(0 102 204 / 24%); }

.overview-empty {
  display: grid;
  min-height: 520px;
  place-content: center;
  justify-items: center;
  padding: 40px;
  text-align: center;
  border: 1px dashed #c9cfd5;
  border-radius: 9px;
  background: #fff;
}

.empty-mark { display: grid; width: 60px; height: 60px; place-items: center; margin-bottom: 16px; color: #0066cc; border-radius: 14px; background: #eaf3fd; }
.eyebrow { color: #7c8790; font-size: 11px; font-weight: 650; letter-spacing: .04em; }
.overview-empty h1 { margin: 8px 0; color: #253541; font-size: 24px; }
.overview-empty p { max-width: 460px; margin: 0 0 20px; color: #727d86; font-size: 12px; line-height: 1.6; }

@media (max-width: 980px) {
  .reading-layout { grid-template-columns: 1fr; }
}

@media (max-width: 600px) {
  .overview-content { gap: 10px; }
  .advanced-toggle { grid-template-columns: 36px minmax(0, 1fr) 18px; }
  .analysis-tabs { width: 100%; }
  .analysis-tabs button { flex: 1; justify-content: center; }
}

@media (prefers-reduced-motion: reduce) {
  .advanced-toggle > svg { transition: none; }
}
</style>
