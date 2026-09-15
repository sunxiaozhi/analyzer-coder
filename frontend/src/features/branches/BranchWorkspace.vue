<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue';
import { GitBranch, RefreshCw } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { branchesApi, type BranchCodeOperation, type BranchContext, type RemoteBranch, type RepositoryBranch } from '@/api/branches';
import RemoteBranchDiscoveryPanel from './RemoteBranchDiscoveryPanel.vue';
import { useBranchPreparation } from './useBranchPreparation';
import BranchListTable from './BranchListTable.vue';
import BranchIndexPanel from './BranchIndexPanel.vue';
import { useBranchIndexes } from './useBranchIndexes';

const props = withDefaults(defineProps<{ repositoryId: string; canMaintain: boolean; canManage?: boolean; remoteSource?: boolean; showBranchList?: boolean; readingBranchId?: string | null; initialBranchId?: string }>(), {
  remoteSource: false,
  canManage: false,
  showBranchList: false,
});
const emit = defineEmits<{ changed: []; read: [branchId: string, target?: 'search' | 'atlas'] }>();
const branches = shallowRef<RepositoryBranch[]>([]);
const remoteBranches = shallowRef<RemoteBranch[]>([]);
const selectedId = shallowRef(props.initialBranchId ?? '');
const context = shallowRef<BranchContext | null>(null);
const router = useRouter();
const name = shallowRef('');
const busy = shallowRef(false);
const discovering = shallowRef(false);
const error = shallowRef('');
let sequence = 0;
let alive = true;
const selected = computed(() => branches.value.find(branch => branch.id === selectedId.value));
const trackedNames = computed(() => branches.value.map(branch => branch.name));
const indexes = useBranchIndexes(() => props.repositoryId);
const preparation = useBranchPreparation(() => props.repositoryId, async () => { await reload(); emit('changed'); });
const selectedJobs = computed(() => preparation.jobs.value.filter(job => job.branchId === selectedId.value));
const selectedStatus = computed(() => indexes.statuses.value.find(status => status.branchId === selectedId.value));
const vectorJob = computed(() => preparation.jobs.value.find(job => job.branchId === selectedId.value && job.kind === 'VECTORS'));
const vectorBusy = computed(() => ['QUEUED', 'RUNNING'].includes(vectorJob.value?.status ?? ''));
function message(cause: unknown) { return cause instanceof Error ? cause.message : '操作失败，请重试'; }
async function reload() {
  busy.value = true;
  error.value = '';
  try {
    const rows = await branchesApi.list(props.repositoryId);
    if (!alive) return;
    branches.value = rows;
    await indexes.refresh();
    if (!alive) return;
    const target = rows.find(branch => branch.id === selectedId.value && branch.trackingStatus === 'ACTIVE')
      ?? rows.find(branch => branch.trackingStatus === 'ACTIVE' && branch.snapshotId)
      ?? rows.find(branch => branch.trackingStatus === 'ACTIVE');
    if (target && (selectedId.value !== target.id || context.value?.snapshotId !== target.snapshotId)) await select(target.id);
  }
  catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function select(branchId: string) {
  const version = ++sequence;
  selectedId.value = branchId;
  context.value = null;
  error.value = '';
  if (!branches.value.find(branch => branch.id === branchId)?.snapshotId) return;
  try {
    const resolved = await branchesApi.context(props.repositoryId, branchId);
    if (alive && version === sequence) context.value = resolved;
  } catch (cause) { if (alive && version === sequence) error.value = message(cause); }
}
async function discoverRemote() {
  if (!props.remoteSource || discovering.value) return;
  discovering.value = true;
  error.value = '';
  try {
    const rows = await branchesApi.discover(props.repositoryId);
    if (alive) remoteBranches.value = rows;
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) discovering.value = false; }
}
async function trackBranch(branchName: string) {
  const normalized = branchName.trim();
  if (!normalized || busy.value) return;
  busy.value = true; error.value = '';
  try {
    const branch = await branchesApi.track(props.repositoryId, normalized);
    if (!alive) return;
    if (name.value.trim() === normalized) name.value = '';
    await reload();
    await select(branch.id);
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function operate(kind: BranchCodeOperation) {
  if (!props.canMaintain || !selected.value || selected.value.trackingStatus !== 'ACTIVE' || busy.value) return;
  const branchId = selected.value.id;
  busy.value = true; error.value = '';
  try {
    const prepared = await branchesApi.codeOperation(props.repositoryId, branchId, kind,
      kind === 'CONTENT' || kind === 'GRAPH' ? context.value?.contextId : undefined);
    if (!alive) return;
    preparation.accepted(prepared);
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function prepareVectors() {
  if (!context.value || !props.canMaintain || busy.value || vectorBusy.value) return;
  busy.value = true; error.value = '';
  try {
    const job = await branchesApi.prepareVectors(context.value);
    if (alive) preparation.accepted(job);
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function archive(branchId: string) {
  if (!props.canManage || busy.value) return;
  busy.value = true; error.value = '';
  try {
    await branchesApi.archive(props.repositoryId, branchId);
    if (selectedId.value === branchId) {
      selectedId.value = '';
      context.value = null;
    }
    await reload();
    emit('changed');
    ElMessage.success('分支已逻辑归档，历史快照和引用仍保留');
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function restoreBranch(branchId: string) {
  if (!props.canManage || busy.value) return;
  busy.value = true; error.value = '';
  try {
    await branchesApi.restore(props.repositoryId, branchId);
    await reload();
    emit('changed');
    ElMessage.success('分支已恢复跟踪');
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function copyCoordinates() {
  if (!context.value) return;
  try {
    await navigator.clipboard.writeText(JSON.stringify({ repositoryId: context.value.repositoryId, branchId: context.value.branchId }, null, 2));
    ElMessage.success('已复制 MCP 分支参数');
  } catch { ElMessage.error('复制失败，请检查浏览器剪贴板权限'); }
}
onMounted(reload);
onBeforeUnmount(() => { alive = false; ++sequence; });
</script>

<template>
  <div class="branch-workspace">
    <BranchListTable v-if="showBranchList" :branches="branches" :selected-id="selectedId" :reading-branch-id="readingBranchId" :disabled="busy" :can-manage="canManage" :indexes="indexes.statuses.value" @select="select" @archive="archive" @restore="restoreBranch" />
    <div class="branch-controls">
      <GitBranch :size="18" />
      <el-select v-if="!showBranchList" :model-value="selectedId" placeholder="选择管理分支" filterable :disabled="busy" @change="select">
        <el-option v-for="branch in branches.filter(item => item.trackingStatus === 'ACTIVE')" :key="branch.id" :value="branch.id" :label="branch.name" />
      </el-select>
      <strong v-else>管理分支：{{ selected?.name ?? '尚未选择' }}</strong>
      <el-button :disabled="busy" aria-label="刷新分支" @click="reload"><RefreshCw :size="15" /></el-button>
      <el-button link @click="router.push({ path: '/indexing', query: { section: 'branches', repositoryId, branchId: selectedId } })">任务详情</el-button>
    </div>
    <p class="hint">这里的选择用于管理分支；点击“打开代码”才切换其他页面的阅读分支。</p>
    <BranchIndexPanel v-if="selected" :branch="selected" :context="context" :status="selectedStatus"
      :jobs="selectedJobs" :disabled="busy" :can-maintain="canMaintain"
      @operate="operate" @vectors="prepareVectors" @refresh="select(selectedId)" @read="target => emit('read',selectedId,target)" @copy="copyCoordinates" />
    <details v-if="canMaintain" class="branch-add">
      <summary>添加跟踪分支</summary>
      <RemoteBranchDiscoveryPanel v-if="remoteSource" :branches="remoteBranches" :tracked-names="trackedNames" :loading="discovering" :disabled="busy" @refresh="discoverRemote" @track="trackBranch" />
      <form class="branch-controls" @submit.prevent="trackBranch(name)">
        <el-input v-model="name" placeholder="已有分支名称，如 release/1.0" aria-label="新增分支名称" :maxlength="200" :disabled="busy" />
        <el-button native-type="submit" :disabled="busy || !name.trim()">添加跟踪</el-button>
      </form>
      <p class="hint">仅跟踪已有分支，不创建 Git 分支，也不切换源仓库工作目录。</p>
    </details>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="indexes.error.value || preparation.error.value" :title="indexes.error.value || preparation.error.value" type="warning" :closable="false" />
    <el-alert v-if="selected?.error" :title="selected.error" type="warning" :closable="false" />
    <el-empty v-if="!selected" description="选择或添加一个分支" />
  </div>
</template>

<style scoped>
.branch-workspace { display: flex; flex-direction: column; gap: 14px; }
.branch-controls { display: flex; align-items: center; gap: 8px; }
.branch-controls .el-select, .branch-controls .el-input { flex: 1; min-width: 0; }
.hint { margin: 0; color: #68778a; font-size: 12px; line-height: 1.7; }
.branch-add { border-top: 1px solid #dbe3ec; padding-top: 12px; }
.branch-add summary { cursor: pointer; color: #2563eb; margin-bottom: 12px; }
.branch-add form { margin-top: 12px; }
</style>
