<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue';
import { GitBranch, RefreshCw } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { branchesApi, type BranchContext, type RemoteBranch, type RepositoryBranch } from '@/api/branches';
import type { UnifiedSearchResponse } from '@/api/intelligence';
import RemoteBranchDiscoveryPanel from './RemoteBranchDiscoveryPanel.vue';
import { useBranchPreparation } from './useBranchPreparation';
import BranchKnowledgeValidationPanel from './BranchKnowledgeValidationPanel.vue';
import BranchListTable from './BranchListTable.vue';

const props = withDefaults(defineProps<{ repositoryId: string; canMaintain: boolean; canManage?: boolean; remoteSource?: boolean; showBranchList?: boolean }>(), {
  remoteSource: false,
  canManage: false,
  showBranchList: false,
});
const emit = defineEmits<{ changed: [] }>();
const branches = shallowRef<RepositoryBranch[]>([]);
const remoteBranches = shallowRef<RemoteBranch[]>([]);
const selectedId = shallowRef('');
const context = shallowRef<BranchContext | null>(null);
const result = shallowRef<UnifiedSearchResponse | null>(null);
const name = shallowRef('');
const query = shallowRef('');
const busy = shallowRef(false);
const discovering = shallowRef(false);
const searching = shallowRef(false);
const error = shallowRef('');
let sequence = 0;
let searchSequence = 0;
let alive = true;
const selected = computed(() => branches.value.find(branch => branch.id === selectedId.value));
const trackedNames = computed(() => branches.value.map(branch => branch.name));
const preparation = useBranchPreparation(() => props.repositoryId, reload);
const selectedJob = computed(() => preparation.jobs.value.find(job => job.branchId === selectedId.value && job.kind === 'SNAPSHOT'));
const vectorJob = computed(() => preparation.jobs.value.find(job => job.branchId === selectedId.value && job.kind === 'VECTORS'));
const vectorBusy = computed(() => ['QUEUED', 'RUNNING'].includes(vectorJob.value?.status ?? ''));
const preparing = computed(() => ['QUEUED', 'RUNNING'].includes(selectedJob.value?.status ?? ''));
const stages: Record<string, string> = { QUEUED: '等待执行', RESOLVING: '确认分支提交', SNAPSHOT: '创建代码快照', INDEXING: '建立内容索引', PUBLISHING: '发布快照', COMPLETED: '准备完成', FAILED: '准备失败' };
const statusLabels = { PENDING: '未准备', BUILDING: '准备中', READY: '可查看', FAILED: '准备失败' };
function message(cause: unknown) { return cause instanceof Error ? cause.message : '操作失败，请重试'; }
async function reload() {
  busy.value = true;
  error.value = '';
  try { const rows = await branchesApi.list(props.repositoryId); if (alive) branches.value = rows; }
  catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function select(branchId: string) {
  const version = ++sequence;
  ++searchSequence;
  selectedId.value = branchId;
  context.value = null;
  result.value = null;
  searching.value = false;
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
async function prepare() {
  if (!selected.value || busy.value || preparing.value) return;
  const branchId = selected.value.id;
  busy.value = true; error.value = '';
  try {
    const prepared = await branchesApi.prepare(props.repositoryId, branchId);
    if (!alive) return;
    preparation.accepted(prepared);
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function search() {
  if (!context.value || !query.value.trim()) return;
  const version = ++searchSequence;
  searching.value = true; result.value = null; error.value = '';
  try {
    const response = await branchesApi.search(context.value, query.value.trim());
    if (alive && version === searchSequence) result.value = response;
  } catch (cause) { if (alive && version === searchSequence) error.value = message(cause); }
  finally { if (alive && version === searchSequence) searching.value = false; }
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
      result.value = null;
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
onBeforeUnmount(() => { alive = false; ++sequence; ++searchSequence; });
</script>

<template>
  <div class="branch-workspace">
    <BranchListTable v-if="showBranchList" :branches="branches" :selected-id="selectedId" :disabled="busy" @select="select" @archive="archive" @restore="restoreBranch" />
    <div class="branch-controls">
      <GitBranch :size="18" />
      <el-select v-if="!showBranchList" :model-value="selectedId" placeholder="选择分支" filterable :disabled="busy" aria-label="查看分支" @change="select">
        <el-option v-for="branch in branches" :key="branch.id" :value="branch.id" :label="`${branch.name} · ${statusLabels[branch.status]}`" />
      </el-select>
      <strong v-else>{{ selected?.name ?? '选择分支后操作' }}</strong>
      <el-button :disabled="busy" aria-label="刷新分支" @click="reload"><RefreshCw :size="15" /></el-button>
      <el-button v-if="canMaintain" :disabled="!selected || busy || preparing" :loading="busy" @click="prepare">{{ selectedJob?.status === 'FAILED' ? '重试准备' : selected?.snapshotId ? '更新分支快照' : '准备分支' }}</el-button>
      <el-button v-if="canManage && selected?.trackingStatus === 'ACTIVE'" :disabled="busy" @click="archive(selected.id)">归档跟踪</el-button>
    </div>
    <RemoteBranchDiscoveryPanel
      v-if="canMaintain && remoteSource"
      :branches="remoteBranches"
      :tracked-names="trackedNames"
      :loading="discovering"
      :disabled="busy"
      @refresh="discoverRemote"
      @track="trackBranch"
    />
    <form v-if="canMaintain" class="branch-controls" @submit.prevent="trackBranch(name)">
      <el-input v-model="name" placeholder="分支名称，如 release/1.0" aria-label="新增分支名称" :maxlength="200" :disabled="busy" />
      <el-button native-type="submit" :disabled="busy || !name.trim()">添加分支</el-button>
    </form>
    <p class="hint">本窗口独立查看分支，不切换工作目录，也不改变其他页面的当前快照。{{ remoteSource ? '远程分支需先显式添加，再按需准备快照。' : '分支必须已存在于服务端本地 Git 仓库。' }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="preparation.error.value" :title="preparation.error.value" type="warning" :closable="false" />
    <el-alert v-if="selectedJob" :title="stages[selectedJob.stage] ?? selectedJob.stage"
      :description="selectedJob.error ?? (preparing ? '任务在后台执行，关闭窗口后仍会继续。' : '点击刷新阅读版本查看最新快照。')"
      :type="selectedJob.status === 'FAILED' ? 'error' : selectedJob.status === 'SUCCEEDED' ? 'success' : 'info'" :closable="false" />
    <el-button v-if="!context && selected?.snapshotId" link type="primary" @click="select(selectedId)">打开已准备版本</el-button>
    <el-alert v-if="selected?.error" :title="selected.error" type="warning" :closable="false" />
    <div v-if="context" class="context-strip">
      <strong>{{ context.branchName }}</strong><code>{{ context.commitSha.slice(0, 12) }}</code>
      <el-button link type="primary" @click="select(selectedId)">刷新阅读版本</el-button>
      <el-button link type="primary" @click="copyCoordinates">复制 MCP 参数</el-button>
      <el-button v-if="canMaintain" :disabled="busy || vectorBusy" @click="prepareVectors">{{ vectorBusy ? '向量构建中' : '构建代码向量' }}</el-button>
    </div>
    <el-empty v-else :description="selected ? (selected.snapshotId ? '正在载入分支阅读版本' : '该分支尚未准备，不会显示其他分支的数据') : '选择或添加一个分支'" />
    <template v-if="context">
      <el-alert v-if="vectorJob" :title="`代码向量 · ${vectorJob.status === 'SUCCEEDED' ? '已完成' : vectorJob.status === 'FAILED' ? '失败，可重试' : vectorJob.status === 'QUEUED' ? '等待执行' : '构建中'}`"
        :description="vectorJob.error ?? (vectorJob.snapshotId === context.snapshotId ? '任务固定在当前阅读快照，不切换默认分支；模型可用性决定语义或字符相似度检索。' : '该任务属于其他快照，不能代表当前阅读版本的向量状态。')"
        :type="vectorJob.status === 'FAILED' ? 'error' : vectorJob.status === 'SUCCEEDED' ? 'success' : 'info'" :closable="false" />
      <BranchKnowledgeValidationPanel :context="context" :can-manage="canManage" />
      <form class="branch-controls" @submit.prevent="search">
        <el-input v-model="query" placeholder="检索该分支代码与适用知识" aria-label="分支检索词" :maxlength="1000" />
        <el-button native-type="submit" type="primary" :loading="searching" :disabled="!query.trim()">检索</el-button>
      </form>
      <p class="hint">带代码引用或执行要求的知识，需针对当前分支快照验证后才参与检索。</p>
      <el-alert v-if="result?.retrieval.degraded" title="部分检索通道不可用，以下仅展示可用通道的结果" type="warning" :closable="false" />
      <el-empty v-if="result && !result.evidence.length" description="该分支没有匹配的代码或适用知识" />
      <details v-for="(hit, index) in result?.evidence ?? []" :key="`${hit.sourceType}:${index}`" class="branch-hit">
        <summary><span>{{ hit.sourceType === 'CODE' ? '代码' : '知识' }}</span> {{ hit.title || hit.filePath }} <small v-if="hit.startLine">:{{ hit.startLine }}</small></summary>
        <pre>{{ hit.content }}</pre>
      </details>
    </template>
  </div>
</template>

<style scoped>
.branch-workspace { display: flex; flex-direction: column; gap: 14px; }
.branch-controls { display: flex; align-items: center; gap: 8px; }
.branch-controls .el-select, .branch-controls .el-input { flex: 1; min-width: 0; }
.hint { margin: 0; color: #68778a; font-size: 12px; line-height: 1.7; }
.context-strip { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px; background: #eff6ff; border-left: 3px solid #2563eb; color: #334155; }
code, pre { font-family: Consolas, monospace; }
.branch-hit { border: 1px solid #dbe3ec; border-radius: 6px; }
summary { cursor: pointer; padding: 12px; overflow-wrap: anywhere; font-size: 13px; }
summary span { color: #2563eb; margin-right: 8px; }
summary:focus-visible { outline: 2px solid #93c5fd; }
pre { margin: 0; padding: 14px; background: #f5f7fa; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.7; }
</style>
