<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { branchesApi, type BranchPreparationJob, type RepositoryBranch } from '@/api/branches';
import AppPagination from '@/components/AppPagination.vue';
import BranchTaskDetailDialog from './BranchTaskDetailDialog.vue';
import type { Repository } from '@/types/api';

const props = defineProps<{ repositories: Repository[]; initialRepositoryId?: string; initialBranchId?: string }>();
const router = useRouter();
const repositoryId = shallowRef('');
const branchId = shallowRef('');
const branches = shallowRef<RepositoryBranch[]>([]);
const jobs = shallowRef<BranchPreparationJob[]>([]);
const selectedId = shallowRef('');
const pageNum = shallowRef(1);
const pageSize = shallowRef(15);
const total = shallowRef(0);
const loading = shallowRef(false);
const error = shallowRef('');
let version = 0;
let stopped = false;
let timer: ReturnType<typeof setTimeout> | undefined;
const projects = computed(() => props.repositories.filter(project => ['LOCAL_GIT', 'REMOTE_GIT', 'GITLAB'].includes(project.sourceType)));
const rows = computed(() => jobs.value.filter(job => !branchId.value || job.branchId === branchId.value));
const selected = computed(() => rows.value.find(job => job.id === selectedId.value));
const detailOpen = computed({ get: () => Boolean(selected.value), set: (value: boolean) => { if (!value) selectedId.value = ''; } });
const kinds: Record<string, string> = { SYNC: '同步代码', CONTENT: '内容索引', GRAPH: '代码图谱', PREPARE: '一键准备', VECTORS: '向量索引' };
const states: Record<string, string> = { QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败' };
const stages: Record<string, string> = { QUEUED: '等待执行', RESOLVING: '确认目标版本', SYNC: '同步代码', PUBLISHING: '发布分支代码', INDEXING: '构建内容索引', GRAPH: '构建图谱', EMBEDDING: '构建向量', COMPLETED: '已完成', FAILED: '失败' };
function branchName(id: string) { return branches.value.find(branch => branch.id === id)?.name ?? id; }
async function refresh() {
  clearTimeout(timer);
  const current = ++version;
  const target = repositoryId.value;
  if (!target) return;
  loading.value = true;
  try {
    const [loadedBranches, loadedJobs] = await Promise.all([branchesApi.list(target), branchesApi.preparationHistory(target, pageNum.value, pageSize.value, branchId.value || undefined)]);
    if (stopped || current !== version || target !== repositoryId.value) return;
    branches.value = loadedBranches;
    jobs.value = loadedJobs.items;
    total.value = loadedJobs.total;
    error.value = '';
  } catch (cause) {
    if (!stopped && current === version) error.value = cause instanceof Error ? cause.message : '加载分支任务失败';
  } finally {
    if (!stopped && current === version) {
      loading.value = false;
      timer = setTimeout(refresh, 2500);
    }
  }
}
watch(() => [props.initialRepositoryId, projects.value.map(project => project.id).join('|')], () => {
  const requested = projects.value.find(project => project.id === props.initialRepositoryId);
  repositoryId.value = requested?.id ?? projects.value.find(project => project.id === repositoryId.value)?.id ?? projects.value[0]?.id ?? '';
}, { immediate: true });
watch(repositoryId, () => {
  ++version;
  clearTimeout(timer);
  pageNum.value = 1; total.value = 0;
  jobs.value = []; branches.value = []; selectedId.value = ''; error.value = '';
  branchId.value = repositoryId.value === props.initialRepositoryId ? props.initialBranchId ?? '' : '';
  void refresh();
}, { immediate: true, flush: 'sync' });
watch([branchId, pageSize], () => { pageNum.value = 1; selectedId.value = ''; jobs.value = []; void refresh(); });
watch(pageNum, () => { selectedId.value = ''; jobs.value = []; void refresh(); });
watch(() => props.initialBranchId, value => { branchId.value = value ?? ''; });
onBeforeUnmount(() => { stopped = true; ++version; clearTimeout(timer); });
</script>

<template>
  <section class="branch-tasks" aria-label="分支任务">
    <div class="task-filters">
      <label>项目<select v-model="repositoryId" aria-label="任务所属项目"><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label>
      <label>分支<select v-model="branchId" aria-label="任务所属分支"><option value="">全部分支</option><option v-for="branch in branches" :key="branch.id" :value="branch.id">{{ branch.name }}</option></select></label>
      <el-button :disabled="loading || !repositoryId" @click="refresh">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-if="!projects.length" description="没有可读取的 Git 项目" />
    <template v-else>
      <div class="branch-task-table">
        <table><thead><tr><th>分支</th><th>任务</th><th>状态</th><th>阶段</th><th>内容版本</th><th>操作</th></tr></thead>
          <tbody><tr v-for="job in rows" :key="job.id" :class="{ selected: selected?.id === job.id }">
            <td>{{ branchName(job.branchId) }}</td><td>{{ kinds[job.kind] }}</td><td>{{ states[job.status] }}</td><td>{{ stages[job.stage] ?? job.stage }}</td><td><code>{{ job.contentVersion?.slice(0, 8) ?? '等待锁定' }}</code></td>
            <td><button type="button" @click="selectedId = job.id">查看</button></td>
          </tr><tr v-if="!rows.length"><td colspan="6">{{ loading ? '正在加载任务' : '当前筛选下没有分支任务' }}</td></tr></tbody>
        </table>
      </div>
      <AppPagination :page-num="pageNum" :page-size="pageSize" :total="total" :disabled="loading" @page-change="value => pageNum = value" @size-change="value => pageSize = value" />
      <BranchTaskDetailDialog v-if="selected" v-model="detailOpen" :job="selected"
        :project-name="projects.find(project => project.id === repositoryId)?.name ?? repositoryId"
        :branch-name="branchName(selected.branchId)" :kind-label="kinds[selected.kind] ?? selected.kind"
        :status-label="states[selected.status] ?? selected.status" :stage-label="stages[selected.stage] ?? selected.stage"
        @manage="router.push({ path: '/repositories', query: { repositoryId, branchId: selected.branchId } })" />
    </template>
  </section>
</template>

<style scoped>
.branch-tasks { display: flex; flex-direction: column; gap: 20px; padding: 20px; overflow: auto; }
.task-filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; }
.task-filters label { display: grid; gap: 6px; min-width: 180px; font-size: 13px; color: #68778a; }
.task-filters select { padding: 8px; border: 1px solid #dbe3ec; border-radius: 4px; color: #334155; background: #fff; }
.branch-task-table { flex: 1; min-height: 0; overflow: auto; } table { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; color: #334155; }
th, td { padding: 16px 14px; border-bottom: 1px solid #dbe3ec; } thead { background: #f5f7fa; } .selected { background: #eff6ff; }
td button { background: none; border: 0; color: #2563eb; cursor: pointer; }
code { overflow-wrap: anywhere; }
@media (max-width: 760px) { .branch-task-table { flex: none; min-height: 160px; } table { min-width: 660px; } .task-filters label { min-width: 0; flex: 1 1 160px; } }
</style>
