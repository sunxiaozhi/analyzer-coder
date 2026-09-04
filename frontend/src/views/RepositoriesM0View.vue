<script setup lang="ts">
import { Connection, Plus, Search } from '@element-plus/icons-vue';
import { onBeforeUnmount, onMounted, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppPagination from '@/components/AppPagination.vue';
import RepositoryFormDialog from '@/features/repositories/RepositoryFormDialog.vue';
import RepositoryEditDialog from '@/features/repositories/RepositoryEditDialog.vue';
import RepositoryGovernanceDialog from '@/features/repositories/RepositoryGovernanceDialog.vue';
import RepositoryTable from '@/features/repositories/RepositoryTable.vue';
import EngineeringProjectsDialog from '@/features/repositories/EngineeringProjectsDialog.vue';
import { sourceImportsApi } from '@/api/sourceImports';
import { listRepositoryPage, syncRemoteRepository, updateRepository } from '@/api/repositories';
import { intelligenceApi } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { Repository } from '@/types/api';

const store = useRepositoryStore();
const router = useRouter();
const route = useRoute();
const rows = shallowRef<Repository[]>([]);
const query = shallowRef('');
const pageNum = shallowRef(1);
const pageSize = shallowRef(15);
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
const engineeringProjectsOpen = shallowRef(false);
let searchTimer: number | undefined;

type Input = { sourceType: 'LOCAL_GIT' | 'REMOTE_GIT' | 'GITLAB' | 'ZIP'; name: string; path: string; url: string; branch: string; credentialId: string; file: File | null };

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
  if (importing.value) return;
  importing.value = true;
  try {
    if (input.sourceType === 'LOCAL_GIT') await store.createRepository({ name: input.name, path: input.path });
    else if (input.sourceType === 'ZIP') { if (!input.file) throw new Error('请选择 ZIP 文件'); await sourceImportsApi.zip(input.name, input.file); }
    else { const job=await sourceImportsApi.remoteJob({ name: input.name, url: input.url, branch: input.branch, sourceType: input.sourceType, credentialId: input.credentialId || undefined }); await waitForImport(job.id); }
    dialogOpen.value = false;
    pageNum.value = 1;
    await reloadAll();
    ElMessage.success('仓库代码版本已验证并发布');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '导入失败'); }
  finally { importing.value = false; }
}
async function waitForImport(id:string){for(let attempt=0;attempt<120;attempt++){const job=await sourceImportsApi.job(id);if(job.status==='SUCCEEDED')return;if(job.status==='FAILED'||job.status==='CANCELED')throw new Error(job.errorMessage??'仓库导入未完成');await new Promise(resolve=>window.setTimeout(resolve,1000));}throw new Error('仓库导入仍在后台运行，请稍后刷新列表')}
function openEdit(repository: Repository) { editing.value = repository; editOpen.value = true; }
async function saveEdit(input: { name: string; description: string; defaultBranch: string; version: number }) {
  if (!editing.value) return;
  editBusy.value = true;
  try { await updateRepository(editing.value.id, input); editOpen.value = false; await reloadAll(); ElMessage.success('仓库资料已更新'); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败'); }
  finally { editBusy.value = false; }
}
async function rescan(id: string) {
  rescanningId.value = id;
  try {
    const repository = rows.value.find(item => item.id === id);
    const result = repository && ['REMOTE_GIT', 'GITLAB'].includes(repository.sourceType)
      ? await syncRemoteRepository(id)
      : await store.rescanRepository(id);
    await reloadAll();
    ElMessage.success(result.changed ? '已同步更新；增量索引正在后台执行' : '代码版本无变化');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '仓库同步失败，请检查代码源和凭据');
  } finally {
    rescanningId.value = null;
  }
}
async function startIndex(id: string) {
  try {
    await store.createIndexJob(id, 'FULL');
    await store.selectRepository(id);
    ElMessage.success('全量内容索引已进入队列；可在项目总览查看准备进度');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '内容索引创建失败');
  }
}
async function buildCodeGraph(repository: Repository) {
  buildingId.value = repository.id;
  try {
    const task = await intelligenceApi.buildGraph(repository.id);
    await reloadAll();
    if (task.status === 'FAILED') ElMessage.error(task.errorMessage ?? '代码图谱构建失败');
    else ElMessage.success('代码图谱构建任务已提交；完成后会自动发布到当前快照');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '代码图谱构建任务提交失败');
  } finally {
    buildingId.value = null;
  }
}
async function openOverview(repository: Repository) {
  await store.selectRepository(repository.id);
  await router.push({ name: 'overview' });
}
function govern(repository: Repository) { governedRepository.value = repository; governanceOpen.value = true; }
async function governanceChanged() { await reloadAll(); governedRepository.value = store.repositories.find(item => item.id === governedRepository.value?.id) ?? null; }
async function remove(id: string, name: string) { await ElMessageBox.confirm(`删除平台中的“${name}”及其派生数据；本地原目录不会被修改。`, '删除仓库', { type: 'warning' }); await store.removeRepository(id); await loadPage(); }

watch(query, () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => { pageNum.value = 1; void loadPage(); }, 300);
});
onMounted(async () => {
  await reloadAll();
  const requestedId = typeof route.query.edit === 'string' ? route.query.edit : null;
  if (!requestedId) return;
  const requested = rows.value.find(item => item.id === requestedId)
    ?? store.repositories.find(item => item.id === requestedId);
  if (requested && (requested.capabilities.canEditRepository ?? requested.capabilities.canConfigure)) {
    openEdit(requested);
  }
});
onBeforeUnmount(() => window.clearTimeout(searchTimer));
</script>

<template>
  <section class="page repository-design">
    <div class="surface repository-list-surface">
      <div class="repository-list-header">
        <div class="toolbar"><el-input v-model="query" class="app-search-input" :prefix-icon="Search" placeholder="搜索仓库名称、描述、所有者或分支" clearable /><span class="spacer" /><el-button :icon="Connection" @click="engineeringProjectsOpen=true">跨仓工程项目</el-button><el-button type="primary" :icon="Plus" :loading="importing" @click="dialogOpen=true">接入仓库</el-button></div>
        <el-alert v-if="pageError || store.error" :title="pageError ?? store.error ?? ''" type="error" :closable="false" />
      </div>
      <div class="repository-table-region">
        <RepositoryTable :rows="rows" :loading="pageLoading" :rescanning-id="rescanningId" :building-id="buildingId" @overview="openOverview" @edit="openEdit" @index="startIndex" @rescan="rescan" @codegraph="buildCodeGraph" @govern="govern" @remove="remove" />
      </div>
      <AppPagination :page-num="pageNum" :page-size="pageSize" :total="total" :disabled="pageLoading" @page-change="changePage" @size-change="changePageSize" />
    </div>
    <RepositoryFormDialog v-model="dialogOpen" :busy="importing" @submit="create" />
    <RepositoryEditDialog v-model="editOpen" :repository="editing" :busy="editBusy" @submit="saveEdit" />
    <RepositoryGovernanceDialog v-model="governanceOpen" :repository="governedRepository" @changed="governanceChanged" />
    <EngineeringProjectsDialog v-model="engineeringProjectsOpen" :repositories="store.repositories" />
  </section>
</template>
<style scoped>
.repository-design {
  grid-template-rows: minmax(0, 1fr);
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
    grid-template-rows: auto;
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
