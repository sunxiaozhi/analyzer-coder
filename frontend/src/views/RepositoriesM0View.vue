<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppPagination from '@/components/AppPagination.vue';
import RepositoryFormDialog from '@/features/repositories/RepositoryFormDialog.vue';
import RepositoryEditDialog from '@/features/repositories/RepositoryEditDialog.vue';
import RepositoryGovernanceDialog from '@/features/repositories/RepositoryGovernanceDialog.vue';
import ProjectSettingsPanel from '@/features/repositories/ProjectSettingsPanel.vue';
import ProjectManagementHeader from '@/features/repositories/ProjectManagementHeader.vue';
import { useBranchContextStore } from '@/stores/branchContextStore';
import ProjectSelectionList from '@/features/repositories/ProjectSelectionList.vue';
import BranchWorkspace from '@/features/branches/BranchWorkspace.vue';
import { sourceImportsApi } from '@/api/sourceImports';
import { projectDraftsApi, type ProjectDraft } from '@/api/projectDrafts';
import { listRepositoryPage, updateRepository } from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { Repository } from '@/types/api';

const store = useRepositoryStore();
const branchContext = useBranchContextStore();
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
const importing = shallowRef(false);
const editOpen = shallowRef(false);
const editing = shallowRef<Repository | null>(null);
const editBusy = shallowRef(false);
const managementOpen = shallowRef(false);
const managedRepository = shallowRef<Repository | null>(null);
const readingBusy = shallowRef(false);
const readingBranchName = computed(() => branchContext.context?.branchName ?? branchContext.selectedBranch?.name ?? null);
const selectingProject = shallowRef(false);
const selectedProject = computed(() => store.selectedRepository);
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
async function branchesChanged() {
  await reloadAll();
  await branchContext.refresh();
}
async function readBranch(branchId: string, target?: 'search' | 'atlas') {
  if (readingBusy.value) return;
  const repositoryId = store.selectedRepositoryId;
  readingBusy.value = true;
  try {
    await branchContext.select(branchId);
    if (!alive || repositoryId !== store.selectedRepositoryId) return;
    if (!branchContext.context || branchContext.context.branchId !== branchId) throw new Error('当前分支尚未准备完成');
    if (target) await router.push({ name: target, query: { branchId, contextId: branchContext.context.contextId } });
    else {
      await router.replace({ query: { ...route.query, branchId, contextId: branchContext.context.contextId } });
      ElMessage.success('已切换阅读分支：' + branchContext.context.branchName);
    }
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '切换阅读分支失败'); }
  finally { if (alive) readingBusy.value = false; }
}
function openSettings(repository: Repository) { managedRepository.value = repository; managementOpen.value = true; }
function govern(repository: Repository) { governedRepository.value = repository; governanceOpen.value = true; }
async function governanceChanged() { await reloadAll(); governedRepository.value = store.repositories.find(item => item.id === governedRepository.value?.id) ?? null; }
async function remove(id: string, name: string) { await ElMessageBox.confirm(`删除平台中的“${name}”及其派生数据；本地原目录不会被修改。`, '删除仓库', { type: 'warning' }); await store.removeRepository(id); if (managedRepository.value?.id === id) managementOpen.value = false; await loadPage(); }

watch(query, () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => { pageNum.value = 1; void loadPage(); }, 300);
});
onMounted(async () => {
  await reloadAll();
  const requestedProject = typeof route.query.repositoryId === 'string' ? store.repositories.find(item => item.id === route.query.repositoryId) : undefined;
  if (requestedProject) await selectProject(requestedProject);
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
        <div class="project-list-heading"><div><h2 class="project-list-title">项目</h2><span class="project-list-count">{{ total }} 个项目</span></div><el-button type="primary" :icon="Plus" :loading="importing" @click="retryingDraft=null; dialogOpen=true">导入项目</el-button></div>
        <div class="project-list-toolbar"><el-input v-model="query" :prefix-icon="Search" placeholder="搜索项目名称" aria-label="搜索项目" clearable /></div>
        <el-alert v-if="pageError || store.error" :title="pageError ?? store.error ?? ''" type="error" :closable="false" />
        <div v-if="unfinishedDrafts.length" class="draft-stack">
          <div v-for="draft in unfinishedDrafts.slice(0, 3)" :key="draft.id" class="draft-row">
            <span><b>{{ draft.name }}</b><small>{{ draft.error ?? '等待配置代码来源' }}</small></span>
            <el-button link type="primary" @click="retryDraft(draft)">继续接入</el-button>
          </div>
        </div>
      </div>
      <div class="repository-table-region">
        <ProjectSelectionList :rows="rows" :selected-id="store.selectedRepositoryId" :loading="pageLoading" :disabled="selectingProject || readingBusy" :reading-branch-name="readingBranchName" @select="selectProject" @settings="openSettings" @govern="govern" @remove="project => remove(project.id, project.name)" />
      </div>
      <AppPagination :page-num="pageNum" :page-size="pageSize" :total="total" :disabled="pageLoading" compact @page-change="changePage" @size-change="changePageSize" />
    </aside>
    <section class="surface project-branches" aria-label="当前项目分支">
      <template v-if="selectedProject">
        <ProjectManagementHeader :repository="selectedProject" :reading-branch-name="readingBranchName" @settings="openSettings(selectedProject)" @govern="govern(selectedProject)" @remove="remove(selectedProject.id, selectedProject.name)" />
        <BranchWorkspace :key="selectedProject.id" :repository-id="selectedProject.id"
          :can-maintain="selectedProject.capabilities.canUpdate" :can-manage="selectedProject.capabilities.canConfigure"
          :can-track="selectedProject.sourceType !== 'ZIP'" :default-branch="selectedProject.branch || 'WORKSPACE'" :reading-busy="readingBusy" :remote-source="['REMOTE_GIT', 'GITLAB'].includes(selectedProject.sourceType)" :reading-branch-id="branchContext.selectedBranchId" :initial-branch-id="typeof route.query.branchId === 'string' ? route.query.branchId : undefined" show-branch-list @changed="branchesChanged" @read="readBranch" />
      </template>
      <el-empty v-else description="从左侧选择项目，或先接入一个项目" />
    </section>
    <el-dialog v-model="managementOpen" title="项目设置" width="min(680px, 96vw)">
      <ProjectSettingsPanel v-if="managedRepository" :repository="managedRepository"
        @edit="managementOpen=false; openEdit(managedRepository)"
        @remove="remove(managedRepository.id,managedRepository.name)" />
      <template #footer><el-button @click="managementOpen=false">关闭</el-button></template>
    </el-dialog>
    <RepositoryFormDialog v-model="dialogOpen" :busy="importing" :initial-draft="retryingDraft" @submit="create" />
    <RepositoryEditDialog v-model="editOpen" :repository="editing" :busy="editBusy" @submit="saveEdit" />
    <RepositoryGovernanceDialog v-model="governanceOpen" :repository="governedRepository" @changed="governanceChanged" />
  </section>
</template>
<style scoped>
.repository-design { display: grid; grid-template-columns: clamp(280px, 25%, 350px) minmax(0, 1fr); grid-template-rows: minmax(0, 1fr); gap: 22px; overflow: hidden; min-height: 0; }
.repository-list-surface { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; min-width: 0; min-height: 0; background: var(--app-surface); border-radius: 10px; }
.repository-list-header { padding: 22px 18px 0; border-bottom: 1px solid var(--app-border); }
.project-list-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.project-list-heading > div { display: flex; align-items: baseline; flex-wrap: wrap; gap: 9px; }
.project-list-title { margin: 0; font-size: 18px; font-weight: 600; color: var(--app-text-primary); }
.project-list-count { color: var(--app-text-muted); font-size: 12px; white-space: nowrap; }
.project-list-toolbar { padding-bottom: 20px; }.project-list-toolbar .el-input { width: 100%; }
.repository-table-region { min-height: 0; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; }
.repository-list-surface :deep(.app-pagination) { flex-wrap: wrap; padding: 15px 16px; background: var(--app-surface); }
.repository-list-surface :deep(.pagination-summary) { flex-wrap: wrap; gap: 8px 12px; font-size: 12px; }
.repository-list-surface :deep(.pagination-summary > span:nth-child(2)) { display: none; }
.repository-list-surface :deep(.pagination-controls) { width: 100%; }
.project-branches { display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: auto; padding: 0; border-radius: 10px; }
.project-branches > :deep(.el-empty) { flex: 1; padding: 60px 20px; }
.draft-stack { display: grid; gap: 8px; margin: 0 0 18px; }
.draft-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px; color: var(--app-text-regular); border: 1px solid #ead7b8; border-radius: 6px; background: var(--app-color-warning-soft); font-size: 12px; }
.draft-row span { display: grid; min-width: 0; gap: 4px; }.draft-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1100px) { .repository-design { grid-template-columns: 270px minmax(0, 1fr); gap: 16px; }.repository-list-header { padding: 20px 14px 0; } }
@media (max-width: 760px) { .repository-design { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto auto; height: auto; overflow: visible; gap: 18px; }.repository-list-surface { grid-template-rows: auto minmax(100px, 260px) auto; }.project-branches { overflow: visible; }.repository-table-region { overflow-y: auto; } }
</style>
