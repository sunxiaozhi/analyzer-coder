import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { branchesApi, type BranchCodeOperation, type BranchContext, type RemoteBranch, type RepositoryBranch } from '@/api/branches';
import { useBranchIndexes } from './useBranchIndexes';
import { useBranchPreparation } from './useBranchPreparation';

interface Options {
  repositoryId: () => string;
  canMaintain: () => boolean;
  canManage: () => boolean;
  remoteSource: () => boolean;
  initialBranchId: () => string | undefined;
  changed: () => void;
  externalBusy?: () => boolean;
}

export function useBranchManagement(options: Options) {
  const branches = shallowRef<RepositoryBranch[]>([]);
  const remoteBranches = shallowRef<RemoteBranch[]>([]);
  const selectedId = shallowRef(options.initialBranchId() ?? '');
  const context = shallowRef<BranchContext | null>(null);
  const loading = shallowRef(false);
  const acting = shallowRef(false);
  const resolving = shallowRef(false);
  const discovering = shallowRef(false);
  const error = shallowRef('');
  let listVersion = 0;
  let contextVersion = 0;
  let alive = true;
  const indexes = useBranchIndexes(options.repositoryId);
  const preparation = useBranchPreparation(options.repositoryId, async () => { await reload(); if (alive) options.changed(); });
  const busy = computed(() => loading.value || acting.value || resolving.value || Boolean(options.externalBusy?.()));
  const selected = computed(() => branches.value.find(branch => branch.id === selectedId.value));
  const selectedStatus = computed(() => indexes.statuses.value.find(status => status.branchId === selectedId.value && status.snapshotId === selected.value?.snapshotId));
  const selectedJobs = computed(() => preparation.jobs.value.filter(job => job.branchId === selectedId.value));
  const trackedNames = computed(() => branches.value.map(branch => branch.name));
  const message = (cause: unknown) => cause instanceof Error ? cause.message : '操作失败，请重试';
  function currentBranch(branchId: string) { return branches.value.find(branch => branch.id === branchId && branch.trackingStatus === 'ACTIVE'); }
  function running(branchId: string) { return preparation.jobs.value.some(job => job.branchId === branchId && ['QUEUED', 'RUNNING'].includes(job.status)); }

  async function reload() {
    const version = ++listVersion;
    loading.value = true; error.value = '';
    try {
      const rows = await branchesApi.list(options.repositoryId());
      if (!alive || version !== listVersion) return;
      branches.value = rows;
      await indexes.refresh();
      if (!alive || version !== listVersion) return;
      if (context.value && selected.value?.snapshotId !== context.value.snapshotId) await select(selectedId.value);
    } catch (cause) { if (alive && version === listVersion) error.value = message(cause); }
    finally { if (alive && version === listVersion) loading.value = false; }
  }

  async function select(branchId: string) {
    const version = ++contextVersion;
    selectedId.value = branchId; context.value = null; error.value = '';
    const branch = branches.value.find(item => item.id === branchId);
    if (!branch?.snapshotId || branch.trackingStatus !== 'ACTIVE' || branch.status !== 'READY') { resolving.value = false; return; }
    resolving.value = true;
    try {
      const resolved = await branchesApi.context(options.repositoryId(), branchId);
      if (alive && version === contextVersion) context.value = resolved;
    } catch (cause) { if (alive && version === contextVersion) error.value = message(cause); }
    finally { if (alive && version === contextVersion) resolving.value = false; }
  }

  async function perform(action: () => Promise<void>) {
    if (busy.value) return false;
    acting.value = true; error.value = '';
    try { await action(); return alive; }
    catch (cause) { if (alive) { error.value = message(cause); ElMessage.error(error.value); } return false; }
    finally { if (alive) acting.value = false; }
  }

  async function pin(branchId: string) {
    const pinned = await branchesApi.context(options.repositoryId(), branchId);
    if (alive && selectedId.value === branchId) context.value = pinned;
    return pinned;
  }

  async function operate(branchId: string, kind: BranchCodeOperation) {
    const branch = currentBranch(branchId);
    if (!options.canMaintain() || !branch || running(branchId) || branch.status === 'BUILDING') return;
    if ((kind === 'CONTENT' || kind === 'GRAPH') && (!branch.snapshotId || branch.status !== 'READY')) return;
    await perform(async () => {
      const pinned = kind === 'CONTENT' || kind === 'GRAPH' ? await pin(branchId) : null;
      if (!alive) return;
      const job = await branchesApi.codeOperation(options.repositoryId(), branchId, kind, pinned?.contextId);
      if (alive) { preparation.accepted(job); ElMessage.success('已提交“' + branch.name + '”的分支任务'); }
    });
  }

  async function prepareVectors(branchId: string) {
    const branch = currentBranch(branchId);
    const status = indexes.statuses.value.find(item => item.branchId === branchId && item.snapshotId === branch?.snapshotId);
    if (!options.canMaintain() || !branch?.snapshotId || branch.status !== 'READY' || !status?.contentReady || running(branchId)) return;
    await perform(async () => {
      const pinned = await pin(branchId);
      if (!alive) return;
      const job = await branchesApi.prepareVectors(pinned);
      if (alive) { preparation.accepted(job); ElMessage.success('已提交向量索引任务'); }
    });
  }

  async function discoverRemote() {
    if (!options.remoteSource() || discovering.value) return;
    discovering.value = true; error.value = '';
    try { const rows = await branchesApi.discover(options.repositoryId()); if (alive) remoteBranches.value = rows; }
    catch (cause) { if (alive) error.value = message(cause); }
    finally { if (alive) discovering.value = false; }
  }

  async function trackBranch(name: string) {
    const normalized = name.trim();
    if (!options.canMaintain() || !normalized) return false;
    return perform(async () => {
      await branchesApi.track(options.repositoryId(), normalized);
      if (!alive) return;
      await reload();
      if (alive) { options.changed(); ElMessage.success('已添加跟踪分支'); }
    });
  }

  async function archive(branchId: string) {
    const branch = currentBranch(branchId);
    if (!options.canManage() || !branch || busy.value || running(branchId)) return;
    try {
      await ElMessageBox.confirm('取消跟踪“' + branch.name + '”后将停止准备该分支；历史快照和引用会保留，可随时恢复跟踪。', '取消跟踪分支', { confirmButtonText: '取消跟踪', cancelButtonText: '保留跟踪', type: 'warning' });
    } catch { return; }
    await perform(async () => {
      await branchesApi.archive(options.repositoryId(), branchId);
      if (!alive) return;
      if (selectedId.value === branchId) { contextVersion++; context.value = null; }
      await reload();
      if (alive) { options.changed(); ElMessage.success('已取消跟踪该分支'); }
    });
  }

  async function restoreBranch(branchId: string) {
    if (!options.canManage()) return;
    await perform(async () => {
      await branchesApi.restore(options.repositoryId(), branchId);
      if (!alive) return;
      await reload();
      if (alive) { options.changed(); ElMessage.success('分支已恢复跟踪'); }
    });
  }

  async function copyCoordinates() {
    if (!context.value) return;
    try { await navigator.clipboard.writeText(JSON.stringify({ repositoryId: context.value.repositoryId, branchId: context.value.branchId }, null, 2)); ElMessage.success('已复制 MCP 分支参数'); }
    catch { ElMessage.error('复制失败，请检查浏览器剪贴板权限'); }
  }

  onMounted(reload);
  onBeforeUnmount(() => { alive = false; listVersion++; contextVersion++; });
  return { branches, remoteBranches, selectedId, selected, context, loading, busy, discovering, error,
    indexes, preparation, selectedStatus, selectedJobs, trackedNames,
    reload, select, operate, prepareVectors, discoverRemote, trackBranch, archive, restoreBranch, copyCoordinates };
}
