<script setup lang="ts">
import { Connection, Plus, Search } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppPagination from '@/components/AppPagination.vue';
import RepositoryFormDialog from '@/features/repositories/RepositoryFormDialog.vue';
import RepositoryEditDialog from '@/features/repositories/RepositoryEditDialog.vue';
import RepositoryGovernanceDialog from '@/features/repositories/RepositoryGovernanceDialog.vue';
import RepositoryTable from '@/features/repositories/RepositoryTable.vue';
import ProjectSelectionList from '@/features/repositories/ProjectSelectionList.vue';
import BranchWorkspace from '@/features/branches/BranchWorkspace.vue';
import EngineeringProjectsDialog from '@/features/repositories/EngineeringProjectsDialog.vue';
import { sourceImportsApi } from '@/api/sourceImports';
import { projectDraftsApi, type ProjectDraft } from '@/api/projectDrafts';
import { listRepositoryPage, syncRemoteRepository, updateRepository } from '@/api/repositories';
import { intelligenceApi } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { Repository } from '@/types/api';

const store = useRepositoryStore();
const router = useRouter();
const route = useRoute();
const rows = shallowRef<Repository[]>([]);
const projectDrafts = shallowRef<ProjectDraft[]>([]);
const retryingDraft = shallowRef<ProjectDraft | null>(null);
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
const managementOpen = shallowRef(false);
const selectingProject = shallowRef(false);
const selectedProject = computed(() => store.selectedRepository);
const gitProject = computed(() => selectedProject.value && ['LOCAL_GIT', 'REMOTE_GIT', 'GITLAB'].includes(selectedProject.value.sourceType));
let pageVersion = 0;
let alive = true;
let searchTimer: number | undefined;

type Input = { sourceType: 'LOCAL_GIT' | 'REMOTE_GIT' | 'GITLAB' | 'ZIP'; name: string; description: string; path: string; url: string; branch: string; credentialId: string; file: File | null };
const unfinishedDrafts = computed(() => projectDrafts.value.filter(draft => draft.lifecycleStatus !== 'READY'));

async function loadPage() {
  const version = ++pageVersion;
  pageLoading.value = true;
  pageError.value = null;
  try {
    const result = await listRepositoryPage({ query: query.value, pageNum: pageNum.value, pageSize: pageSize.value });
    if (!alive || version !== pageVersion) return;
    rows.value = result.items;
    total.value = result.total;
    if (!result.items.length && pageNum.value > 1) {
      pageNum.value -= 1;
      await loadPage();
    }
  } catch (error) {
    if (alive && version === pageVersion) pageError.value = error instanceof Error ? error.message : '仓库列表加载失败';
  } finally { if (alive && version === pageVersion) pageLoading.value = false; }
}
async function selectProject(project: Repository) {
  if (selectingProject.value || project.id === store.selectedRepositoryId) return;
  selectingProject.value = true;
  try { await store.selectRepository(project.id); managementOpen.value = false; }
  catch (error) { if (alive) ElMessage.error(`项目选择保存失败：${error instanceof Error ? error.message : '请稍后重试'}`); }
  finally { if (alive) selectingProject.value = false; }
}
async function reloadAll() {
  const [, , drafts] = await Promise.all([
    store.loadRepositories(),
    loadPage(),
    projectDraftsApi.list().catch(() => []),
  ]);
  projectDrafts.value = drafts;
}
async function changePage(value: number) { pageNum.value = value; await loadPage(); }
async function changePageSize(value: number) { pageSize.value = value; pageNum.value = 1; await loadPage(); }
async function create(input: Input) {
  if (importing.value) return;
  importing.value = true;
  try {
    let draft = retryingDraft.value
      ?? await projectDraftsApi.create(input.name, input.description);
    const sourceLocation = input.sourceType === 'LOCAL_GIT'
      ? input.path
      : input.sourceType === 'ZIP'
        ? input.file?.name ?? ''
        : input.url;
    draft = await projectDraftsApi.configure(
      draft, input.sourceType, sourceLocation, input.credentialId || undefined,
    );
    if (input.sourceType === 'LOCAL_GIT') {
      const repository = await store.createRepository({ name: input.name, path: input.path });
      await projectDraftsApi.complete(draft.id, repository);
    } else if (input.sourceType === 'ZIP') {
      if (!input.file) throw new Error('请选择 ZIP 文件');
      const repository = await sourceImportsApi.zip(input.name, input.file);
      await projectDraftsApi.complete(draft.id, repository);
    } else {
      const job = await sourceImportsApi.remoteJob({
        name: input.name,
        url: input.url,
        branch: input.branch,
        sourceType: input.sourceType,
        credentialId: input.credentialId || undefined,
        projectDraftId: draft.id,
      });
      await waitForImport(job.id);
    }
    dialogOpen.value = false;
    retryingDraft.value = null;
    pageNum.value = 1;
    await reloadAll();
    ElMessage.success('仓库代码版本已验证并发布');
  } catch (error) {
    projectDrafts.value = await projectDraftsApi.list().catch(() => projectDrafts.value);
    ElMessage.error(error instanceof Error ? error.message : '导入失败');
  }
  finally { importing.value = false; }
}
async function waitForImport(id:string){for(let attempt=0;attempt<120;attempt++){const job=await sourceImportsApi.job(id);if(job.status==='SUCCEEDED')return job;if(job.status==='FAILED'||job.status==='CANCELED')throw new Error(job.errorMessage??'仓库导入未完成');await new Promise(resolve=>window.setTimeout(resolve,1000));}throw new Error('仓库导入仍在后台运行，请稍后刷新列表')}
function retryDraft(draft: ProjectDraft) { retryingDraft.value = draft; dialogOpen.value = true; }
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
onBeforeUnmount(() => { alive = false; ++pageVersion; window.clearTimeout(searchTimer); });
</script>

<template>
  <section class="page repository-design">
    <aside class="surface repository-list-surface" aria-label="项目管理">
      <div class="repository-list-header">
        <h2 class="project-list-title">项目</h2>
        <div class="toolbar project-list-toolbar"><el-input v-model="query" :prefix-icon="Search" placeholder="搜索项目" aria-label="搜索项目" clearable /><el-button type="primary" :icon="Plus" :loading="importing" @click="retryingDraft=null; dialogOpen=true">接入项目</el-button></div>
        <el-alert v-if="pageError || store.error" :title="pageError ?? store.error ?? ''" type="error" :closable="false" />
        <div v-if="unfinishedDrafts.length" class="draft-stack">
          <div v-for="draft in unfinishedDrafts.slice(0, 3)" :key="draft.id" class="draft-row">
            <span><b>{{ draft.name }}</b><small>{{ draft.error ?? '等待配置代码来源' }}</small></span>
            <el-button link type="primary" @click="retryDraft(draft)">继续接入</el-button>
          </div>
        </div>
      </div>
      <div class="repository-table-region">
        <ProjectSelectionList :rows="rows" :selected-id="store.selectedRepositoryId" :loading="pageLoading" :disabled="selectingProject" @select="selectProject" />
      </div>
      <AppPagination :page-num="pageNum" :page-size="pageSize" :total="total" :disabled="pageLoading" compact @page-change="changePage" @size-change="changePageSize" />
    </aside>
    <section class="surface project-branches" aria-label="当前项目分支">
      <template v-if="selectedProject">
        <header class="project-branch-header">
          <div><span class="project-eyebrow">当前项目</span><h2 class="project-title">{{ selectedProject.name }}</h2><p class="project-description">{{ selectedProject.description || '选择下方分支，准备并查看该分支的代码与适用知识。' }}</p></div>
          <el-button @click="managementOpen=true">项目管理操作</el-button>
        </header>
        <BranchWorkspace v-if="gitProject" :key="selectedProject.id" :repository-id="selectedProject.id"
          :can-maintain="selectedProject.capabilities.canUpdate" :can-manage="selectedProject.capabilities.canConfigure"
          :remote-source="['REMOTE_GIT', 'GITLAB'].includes(selectedProject.sourceType)" show-branch-list />
        <el-empty v-else description="此项目为非 Git 来源，使用单版本管理，不提供 Git 分支切换。">
          <el-button type="primary" @click="openOverview(selectedProject)">查看项目版本</el-button>
        </el-empty>
      </template>
      <el-empty v-else description="从左侧选择项目，或先接入一个项目" />
    </section>
    <el-dialog v-model="managementOpen" title="项目管理操作（当前默认版本）" width="min(1200px, 96vw)">
      <p>这些操作维护项目资料或原默认版本；分支准备请使用右侧分支列表。</p>
      <RepositoryTable :rows="selectedProject ? [selectedProject] : []" :loading="pageLoading" :rescanning-id="rescanningId" :building-id="buildingId" @overview="openOverview" @edit="openEdit" @index="startIndex" @rescan="rescan" @codegraph="buildCodeGraph" @govern="govern" @remove="remove" />
      <template #footer><el-button :icon="Connection" @click="engineeringProjectsOpen=true">跨仓工程项目</el-button><el-button @click="managementOpen=false">关闭</el-button></template>
    </el-dialog>
    <RepositoryFormDialog v-model="dialogOpen" :busy="importing" :initial-draft="retryingDraft" @submit="create" />
    <RepositoryEditDialog v-model="editOpen" :repository="editing" :busy="editBusy" @submit="saveEdit" />
    <RepositoryGovernanceDialog v-model="governanceOpen" :repository="governedRepository" @changed="governanceChanged" />
    <EngineeringProjectsDialog v-model="engineeringProjectsOpen" :repositories="store.repositories" />
  </section>
</template>
<style scoped>
.repository-design {
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  overflow: hidden;
}
.project-list-title { margin: 14px 14px 8px; font-size: 16px; }
.project-list-toolbar { padding: 0 12px 10px; flex-wrap: wrap; }
.project-list-toolbar .el-input { flex: 1 1 150px; min-width: 100px; }
.draft-stack { display: grid; gap: 6px; margin: 0 12px 10px; }
.draft-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px; color: #566577; border: 1px solid #ead7b8; border-radius: 5px; background: #fffaf0; font-size: 12px; }
.draft-row span { display: grid; min-width: 0; gap: 2px; }
.draft-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-branches { min-width: 0; min-height: 0; overflow: auto; padding: 20px; }
.project-branch-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; padding-bottom: 20px; }
.project-eyebrow, .project-description { color: #68778a; font-size: 12px; line-height: 1.7; }
.project-title { margin: 4px 0; font-size: 22px; color: #334155; overflow-wrap: anywhere; }
.project-description { margin: 0; }
.repository-list-surface :deep(.app-pagination) { flex-wrap: wrap; }

.repository-list-surface {
  min-width: 0;
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
    grid-template-columns: minmax(0, 1fr);
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
