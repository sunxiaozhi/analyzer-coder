<script setup lang="ts">
import { computed, onMounted, shallowRef, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Close, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import RepositoryFilePreview from '@/components/RepositoryFilePreview.vue';
import RepositoryFileTree from '@/components/RepositoryFileTree.vue';
import {
  getRepositoryFile,
  listChunks,
  listRepositoryFiles,
} from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type {
  CodeChunk,
  RepositoryFileContent,
  RepositorySnapshotFiles,
} from '@/types/api';

type MobilePane = 'tree' | 'code' | 'results';

const repositories = useRepositoryStore();
const route = useRoute();
const snapshot = shallowRef<RepositorySnapshotFiles | null>(null);
const selectedPath = shallowRef<string | null>(null);
const selectedFile = shallowRef<RepositoryFileContent | null>(null);
const previewError = shallowRef<string | null>(null);
const filesLoading = shallowRef(false);
const fileLoading = shallowRef(false);
const query = shallowRef('');
const hits = shallowRef<CodeChunk[]>([]);
const totalHits = shallowRef(0);
const staleHits = shallowRef(0);
const searchLoading = shallowRef(false);
const resultsVisible = shallowRef(false);
const searchPerformed = shallowRef(false);
const focusLine = shallowRef<number | null>(null);
const focusEndLine = shallowRef<number | null>(null);
const focusVersion = shallowRef(0);
const mobilePane = shallowRef<MobilePane>('tree');
const fileCache = new Map<string, RepositoryFileContent>();
let snapshotRequest = 0;
let fileRequest = 0;

const repository = computed(() => repositories.selectedRepository);
const shortCommit = computed(() => snapshot.value?.commit?.slice(0, 8) ?? '无提交');
const resultSummary = computed(() => {
  if (!searchPerformed.value) return '输入关键词检索当前代码快照';
  if (staleHits.value) return `当前快照 ${hits.value.length} 条，忽略旧快照 ${staleHits.value} 条`;
  return `当前快照命中 ${totalHits.value} 个代码片段`;
});

async function loadSnapshot(repositoryId: string | null) {
  const requestId = ++snapshotRequest;
  snapshot.value = null;
  selectedPath.value = null;
  selectedFile.value = null;
  previewError.value = null;
  hits.value = [];
  totalHits.value = 0;
  staleHits.value = 0;
  searchPerformed.value = false;
  resultsVisible.value = false;
  fileCache.clear();
  if (!repositoryId) return;
  filesLoading.value = true;
  try {
    const result = await listRepositoryFiles(repositoryId);
    if (requestId !== snapshotRequest) return;
    snapshot.value = result;
    const routePath = typeof route.query.path === 'string' ? route.query.path : null;
    const preferred = result.files.find(file => file.path === routePath) ?? result.files.find(file =>
      /\.(vue|tsx?|jsx?|java|kt|py|go|rs|md)$/i.test(file.path),
    ) ?? result.files[0];
    const startLine = routePath ? routeNumber(route.query.startLine) : null;
    const endLine = routePath ? routeNumber(route.query.endLine) : null;
    if (preferred) await openFile(preferred.path, startLine, endLine, Boolean(routePath));
  } catch (error) {
    if (requestId === snapshotRequest) {
      ElMessage.error(error instanceof Error ? error.message : '代码快照加载失败');
    }
  } finally {
    if (requestId === snapshotRequest) filesLoading.value = false;
  }
}

async function openFile(
  path: string,
  line: number | null = null,
  endLine: number | null = null,
  switchPane = true,
) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  selectedPath.value = path;
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

  const requestId = ++fileRequest;
  selectedFile.value = null;
  fileLoading.value = true;
  try {
    const file = await getRepositoryFile(repositoryId, path);
    if (requestId !== fileRequest || repositoryId !== repositories.selectedRepositoryId) return;
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
  searchLoading.value = true;
  try {
    const result = await listChunks(repositoryId, { q: keyword, limit: 50 });
    const current = result.chunks.filter(chunk => chunk.snapshotId === snapshot.value?.snapshotId);
    hits.value = current;
    totalHits.value = current.length === result.chunks.length ? result.total : current.length;
    staleHits.value = result.chunks.length - current.length;
    searchPerformed.value = true;
    resultsVisible.value = true;
    mobilePane.value = 'results';
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '代码检索失败');
  } finally {
    searchLoading.value = false;
  }
}

function clearSearch() {
  query.value = '';
  hits.value = [];
  totalHits.value = 0;
  staleHits.value = 0;
  searchPerformed.value = false;
  resultsVisible.value = false;
  if (mobilePane.value === 'results') mobilePane.value = selectedPath.value ? 'code' : 'tree';
}

function openHit(hit: CodeChunk) {
  void openFile(hit.filePath, hit.startLine, hit.endLine);
}

function fileName(path: string) {
  return path.split('/').pop() ?? path;
}

function excerpt(content: string) {
  return content.replace(/\s+/g, ' ').trim().slice(0, 150);
}

watch(() => repositories.selectedRepositoryId, loadSnapshot, { immediate: true });
function routeNumber(value: unknown) {
  const parsed = typeof value === 'string' ? Number.parseInt(value, 10) : Number.NaN;
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

onMounted(async () => {
  if (!repositories.repositories.length) await repositories.loadRepositories();
});
watch(
  () => [route.query.path, route.query.startLine, route.query.endLine] as const,
  ([path, startLine, endLine]) => {
    if (typeof path !== 'string' || !snapshot.value?.files.some(file => file.path === path)) return;
    void openFile(path, routeNumber(startLine), routeNumber(endLine));
  },
);
</script>

<template>
  <section class="code-workbench">
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
          placeholder="搜索代码、文件路径或符号"
          @clear="clearSearch"
          @keyup.enter="search"
        />
        <el-button type="primary" :loading="searchLoading" @click="search">检索</el-button>
        <el-button
          v-if="searchPerformed"
          plain
          :type="resultsVisible ? 'primary' : 'default'"
          @click="resultsVisible = !resultsVisible"
        >
          结果 {{ hits.length }}
        </el-button>
      </div>
      <div class="mobile-pane-switch" role="tablist" aria-label="代码工作台面板">
        <button :class="{ active: mobilePane === 'tree' }" @click="mobilePane = 'tree'">目录</button>
        <button :class="{ active: mobilePane === 'code' }" :disabled="!selectedPath" @click="mobilePane = 'code'">代码</button>
        <button
          :class="{ active: mobilePane === 'results' }"
          :disabled="!searchPerformed"
          @click="mobilePane = 'results'; resultsVisible = true"
        >
          结果 {{ hits.length }}
        </button>
      </div>
    </header>

    <div
      class="workbench-grid"
      :class="{ 'results-open': resultsVisible }"
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

      <aside v-if="resultsVisible" class="workbench-results">
        <header class="results-head">
          <div>
            <b>检索结果</b>
            <span>{{ resultSummary }}</span>
          </div>
          <el-button :icon="Close" link title="收起检索结果" @click="resultsVisible = false" />
        </header>
        <div class="search-hit-list">
          <el-empty
            v-if="!searchLoading && !hits.length"
            :image-size="56"
            description="当前快照没有匹配结果"
          />
          <button
            v-for="hit in hits"
            :key="hit.id"
            :class="{ active: selectedPath === hit.filePath && focusLine === hit.startLine }"
            @click="openHit(hit)"
          >
            <span class="hit-title">
              <b>{{ fileName(hit.filePath) }}</b>
              <em>第 {{ hit.startLine ?? 1 }} 行</em>
            </span>
            <small class="mono">{{ hit.filePath }}</small>
            <p>{{ excerpt(hit.content) }}</p>
          </button>
        </div>
      </aside>
    </div>
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
  font-size: 11px;
  white-space: nowrap;
}

.snapshot-context strong {
  max-width: 180px;
  overflow: hidden;
  color: #303036;
  font-size: 13px;
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

.workbench-grid {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.workbench-grid.results-open {
  grid-template-columns: 250px minmax(360px, 1fr) 310px;
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
  font-size: 13px;
}

.results-head span {
  color: #85858c;
  font-size: 10px;
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
  box-shadow: inset 3px 0 #0066cc;
}

.hit-title {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 8px;
}

.hit-title b {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hit-title em {
  flex: none;
  color: #16855b;
  font-size: 10px;
  font-style: normal;
}

.search-hit-list small {
  overflow: hidden;
  color: #7a7a81;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-hit-list p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #5d5d64;
  font-size: 11px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.mobile-pane-switch {
  display: none;
}

@media (max-width: 1120px) {
  .snapshot-context span:last-child {
    display: none;
  }

  .workbench-grid.results-open {
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

  .workbench-grid.results-open {
    grid-template-columns: minmax(0, 1fr) 270px;
  }

  .workbench-grid.results-open > .workbench-tree {
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
    grid-template-columns: repeat(3, 1fr);
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
    font-size: 11px;
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
  .workbench-grid.results-open {
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
