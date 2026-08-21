<script setup lang="ts">
import { computed } from 'vue';
import { ArrowRight, BookOpen, GitCommitHorizontal, Network, Search, Sparkles } from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import AgentContextPackPanel from '@/features/overview/AgentContextPackPanel.vue';
import ModuleSymbolDrawer from '@/features/overview/ModuleSymbolDrawer.vue';
import ProjectAssetsPanel from '@/features/overview/ProjectAssetsPanel.vue';
import ProjectArchitectureMap from '@/features/overview/ProjectArchitectureMap.vue';
import ProjectStructureRail from '@/features/overview/ProjectStructureRail.vue';
import { useProjectOverview } from '@/features/overview/useProjectOverview';
import { useModuleSymbols } from '@/features/overview/useModuleSymbols';
import type { ProjectArchitectureSymbol } from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';

const router = useRouter();
const repositories = useRepositoryStore();
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
const moduleSymbols = useModuleSymbols(
  () => repositories.selectedRepositoryId,
);


const repository = computed(() => repositories.selectedRepository);
const profile = computed(() => preparation.value?.profile ?? null);
const shortCommit = computed(() => snapshot.value?.commit?.slice(0, 10) ?? '未发布');
const readinessLabel = computed(() => ({
  READY: '项目知识已就绪',
  DEGRADED: '项目知识可用，部分能力降级',
  PROCESSING: '正在构建项目知识',
  ACTION_REQUIRED: '项目准备需要处理',
  NOT_READY: '项目尚未准备',
}[preparation.value?.state ?? 'NOT_READY']));

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
</script>

<template>
  <section class="overview-page">
    <el-empty v-if="!repositories.selectedRepositoryId" description="先从顶部选择一个仓库，建立项目知识视图">
      <el-button type="primary" @click="router.push('/repositories')">前往仓库管理</el-button>
    </el-empty>

    <el-alert v-else-if="error" :title="error" type="error" :closable="false">
      <el-button size="small" @click="reload">重新加载</el-button>
    </el-alert>

    <div v-else v-loading="loading" class="overview-content">
      <header class="project-thesis">
        <div class="thesis-copy">
          <span class="thesis-label">CURRENT PROJECT MEMORY</span>
          <h1>{{ repository?.name ?? '项目总览' }}</h1>
          <p>{{ repository?.description || '从项目结构、规则与关键资产开始，再下钻到源码和调用关系。' }}</p>
        </div>
        <div class="version-stamp">
          <GitCommitHorizontal :size="16" />
          <div><span>{{ snapshot?.branch ?? repository?.branch ?? '无分支' }}</span><strong>{{ shortCommit }}</strong></div>
          <em :data-state="preparation?.state">{{ readinessLabel }}</em>
        </div>
      </header>

      <div v-if="profile" class="metric-line">
        <article><span>项目文件</span><strong>{{ profile.fileCount }}</strong><small>当前快照</small></article>
        <article><span>可检索资产</span><strong>{{ profile.chunkCount }}</strong><small>{{ profile.vectorizedChunks }} 条语义就绪</small></article>
        <article><span>架构关系</span><strong>{{ profile.graphEdges }}</strong><small>{{ profile.graphNodes }} 个图谱节点</small></article>
        <article><span>人工知识</span><strong>{{ profile.knowledgeCards }}</strong><small>知识卡片</small></article>
        <nav class="quick-actions" aria-label="项目快捷入口">
          <button type="button" @click="router.push('/search')"><Search :size="13" />源码与资产<ArrowRight :size="12" /></button>
          <button type="button" @click="router.push('/graph')"><Network :size="13" />影响分析<ArrowRight :size="12" /></button>
          <button type="button" @click="router.push('/ask')"><Sparkles :size="13" />项目问答<ArrowRight :size="12" /></button>
          <button type="button" @click="router.push('/knowledge')"><BookOpen :size="13" />人工知识<ArrowRight :size="12" /></button>
        </nav>
      </div>

      <ProjectArchitectureMap
        v-if="profile"
        :map="architecture"
        @open-module="openModule"
        @open-symbols="moduleSymbols.open"
        @open-file="openFile"
        @open-graph="openSymbolGraph"
      />

      <div v-if="profile" class="overview-grid">
        <ProjectStructureRail
          :repository-name="repository?.name ?? '当前项目'"
          :modules="profile.modules"
          :entry-points="profile.entryPoints"
          @open-file="openFile"
        />
        <ProjectAssetsPanel
          :assets="profile.assets"
          :key-assets="profile.keyAssets"
          :stages="preparation?.stages ?? []"
          @open-asset="openFile"
        />
      </div>

      <AgentContextPackPanel
        :pack="contextPack"
        :busy="contextLoading"
        :disabled="!profile?.chunkCount"
        @generate="generateContext"
        @copy="copyContext"
        @download="downloadContext"
        @open-file="openFile"
      />
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
.overview-page { min-height: 100%; overflow: auto; }
.overview-content { display: grid; gap: 12px; min-height: 100%; padding-bottom: 16px; }
.project-thesis { display: flex; min-height: 104px; align-items: center; justify-content: space-between; gap: 20px; padding: 18px 22px; overflow: hidden; color: #fff; border-radius: 8px; background: linear-gradient(112deg, #203747 0%, #294b5c 68%, #16735a 130%); }
.thesis-copy { min-width: 0; }
.thesis-label { color: #8ed6c1; font: 700 9px Consolas, monospace; letter-spacing: .15em; }
.thesis-copy h1 { margin: 7px 0 5px; font-size: 23px; line-height: 1.1; letter-spacing: -.02em; }
.thesis-copy p { margin: 0; color: #c5d2da; font-size: 11px; }
.version-stamp { display: grid; grid-template-columns: 20px auto; align-items: center; gap: 4px 9px; min-width: 250px; padding: 12px 14px; border: 1px solid rgb(255 255 255 / 17%); border-radius: 6px; background: rgb(255 255 255 / 7%); }
.version-stamp > div { display: flex; align-items: baseline; gap: 8px; }
.version-stamp span { color: #c6d4dc; font-size: 9px; }
.version-stamp strong { font: 600 11px Consolas, monospace; }
.version-stamp em { grid-column: 2; color: #8ed6c1; font-size: 9px; font-style: normal; }
.version-stamp em[data-state='ACTION_REQUIRED'] { color: #ffb3a8; }
.metric-line { display: grid; grid-template-columns: repeat(4, minmax(105px, 1fr)) minmax(260px, 1.7fr); overflow: hidden; border: 1px solid #d9e1e8; border-radius: 8px; background: #fff; }
.metric-line article { display: grid; align-content: center; gap: 2px; min-height: 76px; padding: 12px 15px; border-right: 1px solid #e6ebef; }
.metric-line article span { color: #687782; font-size: 9px; }
.metric-line article strong { color: #263946; font: 700 19px Consolas, monospace; }
.metric-line article small { color: #929ba2; font-size: 8px; }
.quick-actions { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1px; background: #e6ebef; }
.quick-actions button { display: grid; grid-template-columns: 18px 1fr 14px; align-items: center; gap: 4px; padding: 9px 11px; color: #405664; text-align: left; border: 0; background: #f8fafb; font-size: 9px; }
.quick-actions button:hover { color: #0066cc; background: #eef6fb; }
.overview-grid { display: grid; grid-template-columns: minmax(480px, 1.35fr) minmax(380px, 1fr); gap: 12px; align-items: stretch; }
@media (max-width: 1100px) {
  .metric-line { grid-template-columns: repeat(4, 1fr); }
  .quick-actions { grid-column: 1 / -1; grid-template-columns: repeat(4, 1fr); }
  .overview-grid { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .project-thesis { align-items: stretch; flex-direction: column; }
  .version-stamp { min-width: 0; }
  .metric-line { grid-template-columns: repeat(2, 1fr); }
  .quick-actions { grid-template-columns: repeat(2, 1fr); }
}
@media (prefers-reduced-motion: reduce) {
  .quick-actions button { transition: none; }
}
</style>
