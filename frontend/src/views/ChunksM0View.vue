<script setup lang="ts">
import { computed, onMounted, onScopeDispose, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Close, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import RepositoryFilePreview from '@/components/RepositoryFilePreview.vue';
import RepositoryFileTree from '@/components/RepositoryFileTree.vue';
import CodeEvidencePanel from '@/features/code/CodeEvidencePanel.vue';
import {
  intelligenceApi,
  type RetrievalDiagnostics,
  type UnifiedSearchHit,
} from '@/api/intelligence';
import {
  getRepositoryFile,
  listRepositoryFiles,
} from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { RepositoryFileContent, RepositorySnapshotFiles } from '@/types/api';

type MobilePane = 'tree' | 'code' | 'results';
type RightPane = 'results' | 'context' | null;

const repositories = useRepositoryStore();
const route = useRoute();
const router = useRouter();
const snapshot = shallowRef<RepositorySnapshotFiles | null>(null);
const selectedPath = shallowRef<string | null>(null);
const selectedFile = shallowRef<RepositoryFileContent | null>(null);
const previewError = shallowRef<string | null>(null);
const filesLoading = shallowRef(false);
const snapshotError = shallowRef<string | null>(null);
const fileLoading = shallowRef(false);
const query = shallowRef('');
const hits = shallowRef<UnifiedSearchHit[]>([]);
const retrieval = shallowRef<RetrievalDiagnostics | null>(null);
const totalHits = shallowRef(0);
const staleHits = shallowRef(0);
const searchLoading = shallowRef(false);
const rightPane = shallowRef<RightPane>(null);
const searchPerformed = shallowRef(false);
const selectedSymbol = shallowRef<string | null>(null);
const focusLine = shallowRef<number | null>(null);
const focusEndLine = shallowRef<number | null>(null);
const focusVersion = shallowRef(0);
const mobilePane = shallowRef<MobilePane>('tree');
const fileCache = new Map<string, RepositoryFileContent>();
let snapshotRequest = 0;
let fileRequest = 0;
let searchRequest = 0;

const repository = computed(() => repositories.selectedRepository);
const workbenchReady = computed(() => Boolean(
  repository.value && (filesLoading.value || snapshot.value),
));
const gateCopy = computed(() => {
  if (!repository.value) return {
    title: '先选择一个项目',
    detail: '代码检索、源码预览和关系证据都需要明确的仓库范围。',
    action: '前往项目管理',
    path: '/repositories',
  };
  if (snapshotError.value) return {
    title: '代码快照加载失败',
    detail: snapshotError.value,
    action: '重新加载',
    path: '',
  };
  return {
    title: '项目还没有可浏览的代码快照',
    detail: '先在项目总览完成扫描和内容索引，再回来检索代码与证据。',
    action: '去准备项目',
    path: '/overview',
  };
});
const shortCommit = computed(() => snapshot.value?.commit?.slice(0, 8) ?? '无提交');
const resultSummary = computed(() => {
  if (!searchPerformed.value) return '输入关键词，同时检索当前代码与项目知识';
  const codeCount = hits.value.filter(hit => hit.sourceType === 'CODE').length;
  const knowledgeCount = hits.value.filter(hit => hit.sourceType === 'KNOWLEDGE').length;
  const summary = `命中 ${codeCount} 个代码片段、${knowledgeCount} 条知识`;
  return staleHits.value ? `${summary}，忽略旧快照 ${staleHits.value} 条` : summary;
});
const evidenceDrawerOpen = computed({
  get: () => rightPane.value === 'context',
  set: (open: boolean) => {
    if (open) rightPane.value = 'context';
    else if (rightPane.value === 'context') rightPane.value = null;
  },
});

async function loadSnapshot(repositoryId: string | null) {
  const requestId = ++snapshotRequest;
  fileRequest++;
  searchRequest++;
  fileLoading.value = false;
  filesLoading.value = false;
  searchLoading.value = false;
  focusLine.value = null;
  focusEndLine.value = null;
  snapshot.value = null;
  snapshotError.value = null;
  selectedPath.value = null;
  selectedFile.value = null;
  previewError.value = null;
  hits.value = [];
  retrieval.value = null;
  totalHits.value = 0;
  staleHits.value = 0;
  searchPerformed.value = false;
  rightPane.value = null;
  selectedSymbol.value = null;
  query.value = '';
  fileCache.clear();
  if (!repositoryId) return;
  filesLoading.value = true;
  try {
    const result = await listRepositoryFiles(repositoryId);
    if (requestId !== snapshotRequest) return;
    snapshot.value = result;
    if (typeof route.query.snapshotId === 'string' && route.query.snapshotId !== result.snapshotId) {
      selectedPath.value = typeof route.query.path === 'string' ? route.query.path : null;
      previewError.value = '该证据来自历史快照，当前源码不能代表当时的内容。请返回来源查看保存的证据，或从目录选择当前文件。';
      mobilePane.value = 'code';
      return;
    }
    const routePath = typeof route.query.path === 'string' ? route.query.path : null;
    if (routePath && !result.files.some(file => file.path === routePath)) {
      selectedPath.value = routePath;
      previewError.value = '当前快照中找不到该文件，文件可能已删除或重命名。请从目录重新选择。';
      return;
    }
    const preferred = result.files.find(file => file.path === routePath) ?? result.files.find(file =>
      /\.(vue|tsx?|jsx?|java|kt|py|go|rs|md)$/i.test(file.path),
    ) ?? result.files[0];
    const startLine = routePath ? routeNumber(route.query.startLine) : null;
    const endLine = routePath ? routeNumber(route.query.endLine) : null;
    const routeSymbol = typeof route.query.symbol === 'string' ? route.query.symbol : null;
    if (preferred) await openFile(preferred.path, startLine, endLine, Boolean(routePath), routeSymbol);
    if (requestId !== snapshotRequest) return;
    if (routeSymbol && (route.query.relation === '1' || route.query.analyze === '1')) {
      rightPane.value = 'context';
    }
    const routeQuery = typeof route.query.q === 'string' ? route.query.q : null;
    if (routeQuery) {
      query.value = routeQuery;
      await search();
    }
  } catch (error) {
    if (requestId === snapshotRequest) {
      snapshotError.value = error instanceof Error ? error.message : '代码快照加载失败';
    }
  } finally {
    if (requestId === snapshotRequest) filesLoading.value = false;
  }
}

function resolveGate() {
  if (!gateCopy.value.path) {
    void loadSnapshot(repositories.selectedRepositoryId);
    return;
  }
  void router.push(gateCopy.value.path);
}

async function openFile(
  path: string,
  line: number | null = null,
  endLine: number | null = null,
  switchPane = true,
  symbolName?: string | null,
) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  const requestId = ++fileRequest;
  fileLoading.value = false;
  const changedFile = selectedPath.value !== path;
  selectedPath.value = path;
  if (symbolName !== undefined) selectedSymbol.value = symbolName;
  else if (changedFile) selectedSymbol.value = null;
  focusLine.value = line;
  focusEndLine.value = endLine;
  focusVersion.value++;
  previewError.value = null;
  if (switchPane) mobilePane.value = 'code';

  const cacheKey = `${snapshot.value?.snapshotId ?? ''}:${path}`;
  const cached = fileCache.get(cacheKey);
  if (cached) {
    selectedFile.value = cached;
    return;
  }

  selectedFile.value = null;
  fileLoading.value = true;
  try {
    const file = await getRepositoryFile(repositoryId, path);
    if (requestId !== fileRequest || repositoryId !== repositories.selectedRepositoryId) return;
    if (file.snapshotId !== snapshot.value?.snapshotId) {
      previewError.value = '代码快照已更新，请重新加载页面后查看源码。';
      return;
    }
    fileCache.set(cacheKey, file);
    selectedFile.value = file;
  } catch (error) {
    if (requestId === fileRequest) {
      previewError.value = error instanceof Error ? error.message : '文件预览失败';
    }
  } finally {
    if (requestId === fileRequest) fileLoading.value = false;
  }
}

async function search() {
  const repositoryId = repositories.selectedRepositoryId;
  const keyword = query.value.trim();
  if (!repositoryId) return ElMessage.warning('请先选择仓库');
  if (!keyword) {
    clearSearch();
    return;
  }
  const requestId = ++searchRequest;
  const snapshotId = snapshot.value?.snapshotId;
  searchLoading.value = true;
  try {
    const result = await intelligenceApi.unifiedSearch(repositoryId, keyword, 50);
    if (requestId !== searchRequest || repositoryId !== repositories.selectedRepositoryId || snapshotId !== snapshot.value?.snapshotId) return;
    retrieval.value = result.retrieval;
    const current = result.evidence.filter(hit => (
      hit.sourceType === 'KNOWLEDGE' || hit.snapshotId === snapshot.value?.snapshotId
    ));
    hits.value = current;
    totalHits.value = current.length;
    staleHits.value = result.evidence.length - current.length;
    searchPerformed.value = true;
    rightPane.value = 'results';
    mobilePane.value = 'results';
  } catch (error) {
    if (requestId !== searchRequest) return;
    ElMessage.error(error instanceof Error ? error.message : '代码与知识检索失败');
  } finally {
    if (requestId === searchRequest) searchLoading.value = false;
  }
}

function clearSearch() {
  searchRequest++;
  searchLoading.value = false;
  query.value = '';
  hits.value = [];
  retrieval.value = null;
  totalHits.value = 0;
  staleHits.value = 0;
  searchPerformed.value = false;
  rightPane.value = null;
  if (mobilePane.value === 'results') mobilePane.value = selectedPath.value ? 'code' : 'tree';
}

function openHit(hit: UnifiedSearchHit) {
  if (hit.sourceType === 'KNOWLEDGE' && hit.knowledgeCardId) {
    openKnowledge(hit.knowledgeCardId);
    return;
  }
  rightPane.value = 'context';
  mobilePane.value = 'code';
  void openFile(hit.filePath, hit.startLine, hit.endLine, true, hit.symbolName);
}

function fileName(path: string) {
  return path.split('/').pop() ?? path;
}

function channelLabel(channel: string) {
  return ({
    CODE_KEYWORD: '关键词',
    CODE_SEMANTIC: '语义向量',
    CODE_CHARACTER_SIMILARITY: '字符向量',
    HEURISTIC_CALL_REFERENCE: '启发式关系',
  } as Record<string, string>)[channel] ?? channel;
}

function excerpt(content: string) {
  return content.replace(/\s+/g, ' ').trim().slice(0, 150);
}

watch(() => [repositories.selectedRepositoryId, repository.value?.snapshotId] as const,
  ([repositoryId]) => loadSnapshot(repositoryId), { immediate: true });
onScopeDispose(() => { snapshotRequest++; fileRequest++; searchRequest++; });
function routeNumber(value: unknown) {
  const parsed = typeof value === 'string' ? Number.parseInt(value, 10) : Number.NaN;
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

onMounted(async () => {
  if (!repositories.repositories.length) await repositories.loadRepositories();
});
function openKnowledge(knowledgeId: string) {
  void router.push({ name: 'knowledge', query: { cardId: knowledgeId } });
}

function createKnowledgeForFile() {
  if (!selectedPath.value) return;
  void router.push({
    name: 'knowledge',
    query: {
      create: '1',
      path: selectedPath.value,
      snapshotId: snapshot.value?.snapshotId,
      symbol: selectedSymbol.value ?? undefined,
    },
  });
}

watch(
  () => [route.query.path, route.query.startLine, route.query.endLine, route.query.q, route.query.symbol, route.query.relation, route.query.analyze, route.query.snapshotId] as const,
  ([path, startLine, endLine, routeQuery, routeSymbol]) => {
    if (typeof routeQuery === 'string' && routeQuery !== query.value) {
      query.value = routeQuery;
      void search();
    }
    if (typeof route.query.snapshotId === 'string' && route.query.snapshotId !== snapshot.value?.snapshotId) {
      void loadSnapshot(repositories.selectedRepositoryId);
      return;
    }
    if (typeof path === 'string' && snapshot.value?.files.some(file => file.path === path)) {
      rightPane.value = 'context';
      void openFile(
        path,
        routeNumber(startLine),
        routeNumber(endLine),
        true,
        typeof routeSymbol === 'string' ? routeSymbol : undefined,
      );
    } else if (typeof path === 'string' && snapshot.value) {
      fileRequest++;
      selectedPath.value = path;
      selectedFile.value = null;
      fileLoading.value = false;
      previewError.value = '当前快照中找不到该文件，文件可能已删除或重命名。请从目录重新选择。';
      mobilePane.value = 'code';
    } else if (typeof routeSymbol === 'string' && selectedPath.value) {
      selectedSymbol.value = routeSymbol;
      rightPane.value = 'context';
    }
  },
);
</script>

<template>
  <section class="code-workbench">
    <section v-if="!workbenchReady" class="workbench-gate">
      <Search class="workbench-gate-icon" aria-hidden="true" />
      <h1>{{ gateCopy.title }}</h1>
      <p>{{ gateCopy.detail }}</p>
      <el-button type="primary" @click="resolveGate">{{ gateCopy.action }}</el-button>
    </section>

    <template v-else>
    <header class="workbench-command">
      <div class="snapshot-context">
        <strong>{{ repository?.name ?? '未选择仓库' }}</strong>
        <span>{{ snapshot?.branch ?? repository?.branch ?? '无分支' }}</span>
        <span class="mono">{{ shortCommit }}</span>
        <span>{{ snapshot?.files.length ?? 0 }} 个文件</span>
      </div>
      <div class="workbench-search">
        <el-input
          v-model="query"
          :prefix-icon="Search"
          clearable
          placeholder="搜索代码、设计说明、业务知识或文件路径"
          @clear="clearSearch"
          @keyup.enter="search"
        />
        <el-button type="primary" :loading="searchLoading" @click="search">检索</el-button>
        <el-button
          v-if="searchPerformed"
          plain
          :type="rightPane === 'results' ? 'primary' : 'default'"
          @click="rightPane = rightPane === 'results' ? null : 'results'"
        >
          结果 {{ hits.length }}
        </el-button>
        <el-button
          v-if="selectedPath"
          plain
          :type="rightPane === 'context' ? 'primary' : 'default'"
          @click="rightPane = rightPane === 'context' ? null : 'context'"
        >
          文件关联证据
        </el-button>
      </div>
      <div v-if="retrieval" class="retrieval-diagnostics" :data-degraded="retrieval.degraded">
        <span>快照 {{ retrieval.snapshotId?.slice(0, 8) ?? '不可用' }}</span>
        <span>{{ retrieval.retrievalCapability === 'SEMANTIC_EMBEDDING' ? '语义向量' : retrieval.retrievalCapability === 'CHARACTER_HASH' ? '字符相似度' : '无向量能力' }}</span>
        <span v-for="channel in retrieval.enabledChannels" :key="channel">{{ channelLabel(channel) }}</span>
        <strong v-if="retrieval.degraded">降级：{{ retrieval.degradationReasons.join('、') || retrieval.unavailableChannels.map(item => item.reason).join('、') }}</strong>
      </div>
      <div class="mobile-pane-switch" role="tablist" aria-label="源码检索面板">
        <button :class="{ active: mobilePane === 'tree' }" @click="mobilePane = 'tree'">目录</button>
        <button :class="{ active: mobilePane === 'code' }" :disabled="!selectedPath" @click="mobilePane = 'code'">代码</button>
        <button
          :class="{ active: mobilePane === 'results' }"
          :disabled="!searchPerformed"
          @click="mobilePane = 'results'; rightPane = 'results'"
        >
          结果 {{ hits.length }}
        </button>
        <button
          :class="{ active: rightPane === 'context' }"
          :disabled="!selectedPath"
          @click="rightPane = rightPane === 'context' ? null : 'context'"
        >
          证据
        </button>
      </div>
    </header>

    <div
      class="workbench-grid"
      :class="{
        'side-open': rightPane === 'results',
        'results-open': rightPane === 'results',
      }"
      :data-mobile-pane="mobilePane"
    >
      <RepositoryFileTree
        class="workbench-tree"
        :files="snapshot?.files ?? []"
        :selected-path="selectedPath"
        :loading="filesLoading"
        @select="openFile"
      />

      <RepositoryFilePreview
        class="workbench-preview"
        :file="selectedFile"
        :loading="fileLoading"
        :error="previewError"
        :focus-line="focusLine"
        :focus-end-line="focusEndLine"
        :focus-version="focusVersion"
      />

      <aside v-if="rightPane === 'results'" class="workbench-results">
        <header class="results-head">
          <div>
            <b>检索结果</b>
            <span>{{ resultSummary }}</span>
          </div>
          <el-button :icon="Close" link title="收起检索结果" @click="rightPane = null" />
        </header>
        <div class="search-hit-list">
          <el-empty
            v-if="!searchLoading && !hits.length"
            :image-size="56"
            description="当前快照没有匹配结果"
          />
          <button
            v-for="hit in hits"
            :key="`${hit.sourceType}:${hit.chunkId ?? hit.knowledgeCardId}`"
            :class="{ active: hit.sourceType === 'CODE' && selectedPath === hit.filePath && focusLine === hit.startLine }"
            :data-source="hit.sourceType"
            @click="openHit(hit)"
          >
            <span class="hit-title">
              <b>{{ hit.sourceType === 'KNOWLEDGE' ? hit.title : fileName(hit.filePath) }}</b>
              <i>{{ hit.sourceType === 'KNOWLEDGE' ? '知识' : (hit.symbolKind ?? '代码') }}</i>
              <em v-if="hit.sourceType === 'CODE'">第 {{ hit.startLine ?? 1 }} 行</em>
            </span>
            <small class="mono">{{ hit.sourceType === 'KNOWLEDGE' ? `关联 ${hit.codeReferences.length} 处代码` : hit.filePath }}</small>
            <p>{{ excerpt(hit.content) }}</p>
            <span class="hit-channels">{{ hit.channels.map(channelLabel).join(' + ') }}</span>
          </button>
        </div>
      </aside>

    </div>

    <el-drawer
      v-model="evidenceDrawerOpen"
      class="file-evidence-drawer"
      direction="rtl"
      size="860px"
      :with-header="false"
      :modal="false"
      :lock-scroll="false"
      :destroy-on-close="false"
    >
      <CodeEvidencePanel
        class="drawer-context"
        :repository-id="repositories.selectedRepositoryId"
        :file-path="selectedPath"
        :initial-symbol="selectedSymbol"
        :can-build-graph="repository?.capabilities.canBuildCodeGraph ?? false"
        :snapshot-id="snapshot?.snapshotId ?? null"
        :initial-depth="routeNumber(route.query.depth) ?? 3"
        :auto-analyze="route.query.relation === '1' || route.query.analyze === '1'"
        :can-maintain-knowledge="repository?.capabilities.canUpdate ?? false"
        @close="evidenceDrawerOpen = false"
        @open-file="openFile"
        @open-knowledge="openKnowledge"
        @create-knowledge="createKnowledgeForFile"
      />
    </el-drawer>
    </template>
  </section>
</template>

<style scoped>
.code-workbench {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  height: 100%;
  padding: 0;
  overflow: hidden;
}

.workbench-gate {
  display: grid;
  grid-row: 1 / -1;
  min-height: 360px;
  place-content: center;
  justify-items: center;
  padding: 32px;
  color: var(--app-text-muted);
  border: 1px dashed var(--app-border-strong);
  border-radius: 8px;
  background: #fff;
  text-align: center;
}
.workbench-gate-icon {
  width: 44px;
  height: 44px;
  padding: 9px;
  color: var(--app-color-action);
  border-radius: 12px;
  background: var(--app-color-action-soft);
}
.workbench-gate h1 { margin: 12px 0 4px; color: var(--app-text-primary); font-size: 18px; }
.workbench-gate p { max-width: 500px; margin: 0 0 16px; font-size: 13px; line-height: 1.6; }

.workbench-command {
  display: grid;
  grid-template-columns: minmax(220px, auto) minmax(340px, 720px);
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 64px;
  padding: 8px 12px;
  background: #fff;
  border: 1px solid #dedee3;
  border-bottom: 0;
  border-radius: 7px 7px 0 0;
}

.snapshot-context {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  color: #73737a;
  font-size: 13px;
  white-space: nowrap;
}

.snapshot-context strong {
  max-width: 180px;
  overflow: hidden;
  color: #303036;
  font-size: 15px;
  text-overflow: ellipsis;
}

.snapshot-context span {
  padding-left: 8px;
  border-left: 1px solid #dedee3;
}

.workbench-search {
  display: flex;
  min-width: 0;
  gap: 8px;
}

.workbench-search .el-input {
  min-width: 0;
}

.retrieval-diagnostics {
  display: flex;
  grid-column: 1 / -1;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  margin-top: -4px;
  padding-top: 6px;
  color: #356d57;
  border-top: 1px solid #eef0f2;
  font-size: 12px;
}

.retrieval-diagnostics > span {
  padding: 3px 5px;
  border: 1px solid #c7ddd3;
  border-radius: 3px;
  background: #f1f8f5;
}

.retrieval-diagnostics[data-degraded='true'] {
  color: #8b5a20;
}

.retrieval-diagnostics[data-degraded='true'] > span {
  border-color: #e0cfb8;
  background: #fbf6ef;
}

.retrieval-diagnostics strong {
  color: #a1493f;
  font-weight: 650;
}

.workbench-grid {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.workbench-grid.results-open {
  grid-template-columns: 250px minmax(360px, 1fr) 340px;
}

.workbench-preview {
  border-right: 1px solid #dedee3;
}

.workbench-results {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border-block: 1px solid #dedee3;
  border-right: 1px solid #dedee3;
  border-radius: 0 0 7px 0;
}

.drawer-context {
  width: 100%;
  height: 100%;
  border: 0;
  border-radius: 0;
}

:global(.file-evidence-drawer.el-drawer) {
  max-width: calc(100vw - 24px);
  border-left: 1px solid #cfd9df;
  box-shadow: -14px 0 36px rgb(28 48 64 / 16%);
}

:global(.file-evidence-drawer .el-drawer__body) {
  min-height: 0;
  padding: 0;
  overflow: hidden;
}

.results-head {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #ececef;
}

.results-head > div {
  display: grid;
  gap: 3px;
}

.results-head b {
  color: #303036;
  font-size: 15px;
}

.results-head span {
  color: var(--app-text-muted);
  font-size: 13px;
}

.search-hit-list {
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
}

.search-hit-list > button {
  display: grid;
  gap: 6px;
  width: 100%;
  padding: 12px;
  color: #3d3d43;
  text-align: left;
  background: #fff;
  border: 0;
  border-bottom: 1px solid #ededf0;
}

.search-hit-list > button:hover {
  background: #f7f9fb;
}

.search-hit-list > button.active {
  background: #edf5fd;
  box-shadow: inset 3px 0 var(--app-color-action);
}

.search-hit-list > button[data-source='KNOWLEDGE'] {
  background: #fffdf8;
  box-shadow: inset 3px 0 #b3843d;
}

.search-hit-list > button[data-source='KNOWLEDGE']:hover {
  background: #fbf5ea;
}

.search-hit-list > button[data-source='KNOWLEDGE'] .hit-title i {
  color: #805820;
  background: #f4e7ce;
}

.hit-title {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 8px;
}

.hit-title b {
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hit-title i {
  margin-left: auto;
  padding: 2px 5px;
  color: #5c6d78;
  border-radius: 3px;
  background: #e8edf0;
  font: 13px Consolas, monospace;
}

.hit-title em {
  flex: none;
  color: var(--app-color-success);
  font-size: 13px;
  font-style: normal;
}

.search-hit-list small {
  overflow: hidden;
  color: var(--app-text-muted);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-hit-list p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #5d5d64;
  font-size: 13px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.hit-channels {
  color: #356d57;
  font-size: 12px;
  font-weight: 650;
}

.mobile-pane-switch {
  display: none;
}

@media (max-width: 1120px) {
  .snapshot-context span:last-child {
    display: none;
  }

  .workbench-grid.side-open {
    grid-template-columns: 220px minmax(330px, 1fr) 270px;
  }

}

@media (max-width: 900px) {
  .workbench-command {
    grid-template-columns: 1fr;
  }

  .snapshot-context {
    display: none;
  }

  .workbench-grid.side-open {
    grid-template-columns: minmax(0, 1fr) 270px;
  }

  .workbench-grid.side-open > .workbench-tree {
    display: none;
  }
}

@media (max-width: 760px) {
  .code-workbench {
    min-height: calc(100vh - 110px);
  }

  .workbench-command {
    position: sticky;
    top: 96px;
    z-index: 4;
    align-content: start;
    border-bottom: 1px solid #dedee3;
  }

  .workbench-search {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
  }

  .mobile-pane-switch {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 3px;
    padding: 3px;
    background: #f0f1f3;
    border-radius: 6px;
  }

  .mobile-pane-switch button {
    min-height: 30px;
    color: #626269;
    background: transparent;
    border: 0;
    border-radius: 4px;
    font-size: 13px;
  }

  .mobile-pane-switch button.active {
    color: #005eb8;
    font-weight: 600;
    background: #fff;
    box-shadow: 0 1px 3px rgb(24 39 58 / 12%);
  }

  .mobile-pane-switch button:disabled {
    cursor: not-allowed;
    opacity: .45;
  }

  .workbench-grid,
  .workbench-grid.side-open {
    display: block;
    min-height: 620px;
  }

  .workbench-grid > * {
    display: none;
    height: 620px;
    border: 1px solid #dedee3;
    border-radius: 0 0 7px 7px;
  }

  .workbench-grid[data-mobile-pane="tree"] > .workbench-tree,
  .workbench-grid[data-mobile-pane="code"] > .workbench-preview,
  .workbench-grid[data-mobile-pane="results"] > .workbench-results {
    display: grid;
  }
}
</style>
