<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppPagination from '@/components/AppPagination.vue';
import RepositoryFormDialog from '@/features/repositories/RepositoryFormDialog.vue';
import RepositoryEditDialog from '@/features/repositories/RepositoryEditDialog.vue';
import RepositoryGovernanceDialog from '@/features/repositories/RepositoryGovernanceDialog.vue';
import RepositoryTable from '@/features/repositories/RepositoryTable.vue';
import { sourceImportsApi } from '@/api/sourceImports';
import { listRepositoryPage, updateRepository } from '@/api/repositories';
import { intelligenceApi } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { Repository } from '@/types/api';

const store = useRepositoryStore();
const rows = shallowRef<Repository[]>([]);
const query = shallowRef('');
const pageNum = shallowRef(1);
const pageSize = shallowRef(20);
const total = shallowRef(0);
const pageLoading = shallowRef(false);
const pageError = shallowRef<string | null>(null);
const dialogOpen = shallowRef(false);
const governanceOpen = shallowRef(false);
const governedRepository = shallowRef<Repository | null>(null);
const rescanningId = shallowRef<string | null>(null);
const buildingId = shallowRef<string | null>(null);
const importing = shallowRef(false);
const editOpen = shallowRef(false);
const editing = shallowRef<Repository | null>(null);
const editBusy = shallowRef(false);
let searchTimer: number | undefined;

const summary = computed(() => ({
  total: store.repositories.length,
  owned: store.repositories.filter(item => item.relationship === 'OWNER').length,
  graphReady: store.repositories.filter(item => item.codeGraphDetected).length,
  graphPending: store.repositories.filter(item => !item.codeGraphDetected).length,
}));
type Input = { sourceType: 'LOCAL_GIT' | 'REMOTE_GIT' | 'GITLAB' | 'ZIP'; name: string; path: string; url: string; branch: string; file: File | null };

async function loadPage() {
  pageLoading.value = true;
  pageError.value = null;
  try {
    const result = await listRepositoryPage({ query: query.value, pageNum: pageNum.value, pageSize: pageSize.value });
    rows.value = result.items;
    total.value = result.total;
    if (!result.items.length && pageNum.value > 1) {
      pageNum.value -= 1;
      await loadPage();
    }
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '仓库列表加载失败';
  } finally { pageLoading.value = false; }
}
async function reloadAll() { await Promise.all([store.loadRepositories(), loadPage()]); }
async function changePage(value: number) { pageNum.value = value; await loadPage(); }
async function changePageSize(value: number) { pageSize.value = value; pageNum.value = 1; await loadPage(); }
async function create(input: Input) {
  importing.value = true;
  try {
    if (input.sourceType === 'LOCAL_GIT') await store.createRepository({ name: input.name, path: input.path });
    else if (input.sourceType === 'ZIP') { if (!input.file) throw new Error('请选择 ZIP 文件'); await sourceImportsApi.zip(input.name, input.file); }
    else await sourceImportsApi.remote({ name: input.name, url: input.url, branch: input.branch, sourceType: input.sourceType });
    dialogOpen.value = false;
    pageNum.value = 1;
    await reloadAll();
    ElMessage.success('仓库快照已验证并发布');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '导入失败'); }
  finally { importing.value = false; }
}
function openEdit(repository: Repository) { editing.value = repository; editOpen.value = true; }
async function saveEdit(input: { name: string; description: string; defaultBranch: string; version: number }) {
  if (!editing.value) return;
  editBusy.value = true;
  try { await updateRepository(editing.value.id, input); editOpen.value = false; await reloadAll(); ElMessage.success('仓库资料已更新'); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败'); }
  finally { editBusy.value = false; }
}
async function rescan(id: string) { rescanningId.value = id; try { const result = await store.rescanRepository(id); await loadPage(); ElMessage.success(result.changed ? '检测到代码变化，已发布新快照' : '代码版本无变化'); } finally { rescanningId.value = null; } }
async function startIndex(id: string) { await store.createIndexJob(id, 'FULL'); ElMessage.success('全量内容索引任务已进入队列'); }
async function buildCodeGraph(repository: Repository) { buildingId.value = repository.id; try { await intelligenceApi.buildGraph(repository.id); await reloadAll(); ElMessage.success('CodeGraph 产物已发布'); } finally { buildingId.value = null; } }
function govern(repository: Repository) { governedRepository.value = repository; governanceOpen.value = true; }
async function governanceChanged() { await reloadAll(); governedRepository.value = store.repositories.find(item => item.id === governedRepository.value?.id) ?? null; }
async function remove(id: string, name: string) { await ElMessageBox.confirm(`删除平台中的“${name}”及其派生数据；本地原目录不会被修改。`, '删除仓库', { type: 'warning' }); await store.removeRepository(id); await loadPage(); }

watch(query, () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => { pageNum.value = 1; void loadPage(); }, 300);
});
onMounted(() => void reloadAll());
onBeforeUnmount(() => window.clearTimeout(searchTimer));
</script>

<template>
  <section class="page repository-design">
    <div class="summary-strip">
      <div><span>授权仓库</span><b>{{ summary.total }}</b></div>
      <div><span>本人所有</span><b>{{ summary.owned }}</b></div>
      <div><span>CodeGraph 已发布</span><b>{{ summary.graphReady }}</b></div>
      <div><span>待构建</span><b>{{ summary.graphPending }}</b></div>
    </div>
    <div class="surface repository-list-surface">
      <div class="repository-list-header">
        <div class="toolbar"><el-input v-model="query" class="app-search-input" :prefix-icon="Search" placeholder="搜索名称、所有者、来源、路径或版本" clearable /><span class="spacer" /><el-button type="primary" :icon="Plus" :loading="importing" @click="dialogOpen=true">接入仓库</el-button></div>
        <el-alert v-if="pageError || store.error" :title="pageError ?? store.error ?? ''" type="error" :closable="false" />
      </div>
      <div class="repository-table-region">
        <RepositoryTable :rows="rows" :loading="pageLoading" :rescanning-id="rescanningId" :building-id="buildingId" @edit="openEdit" @index="startIndex" @rescan="rescan" @codegraph="buildCodeGraph" @govern="govern" @remove="remove" />
      </div>
      <AppPagination :page-num="pageNum" :page-size="pageSize" :total="total" :disabled="pageLoading" @page-change="changePage" @size-change="changePageSize" />
    </div>
    <RepositoryFormDialog v-model="dialogOpen" @submit="create" />
    <RepositoryEditDialog v-model="editOpen" :repository="editing" :busy="editBusy" @submit="saveEdit" />
    <RepositoryGovernanceDialog v-model="governanceOpen" :repository="governedRepository" @changed="governanceChanged" />
  </section>
</template>
<style scoped>
.repository-design {
  grid-template-rows: 80px minmax(0, 1fr);
  overflow: hidden;
}

.repository-list-surface {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-height: 0;
}

.repository-table-region {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

@media (max-width: 760px) {
  .repository-design {
    grid-template-rows: auto auto;
    height: auto;
    overflow: visible;
  }

  .repository-list-surface {
    display: block;
  }

  .repository-table-region {
    overflow: visible;
  }
}
</style>
