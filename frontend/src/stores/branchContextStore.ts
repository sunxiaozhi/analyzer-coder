import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';
import {
  branchesApi,
  type BranchContext,
  type RepositoryBranch,
} from '@/api/branches';

const storageKey = (repositoryId: string) => `analyzer-coder:branch:${repositoryId}`;

export const useBranchContextStore = defineStore('branch-context', () => {
  const repositoryId = shallowRef<string | null>(null);
  const branches = ref<RepositoryBranch[]>([]);
  const selectedBranchId = shallowRef<string | null>(null);
  const context = shallowRef<BranchContext | null>(null);
  const loading = shallowRef(false);
  const error = shallowRef<string | null>(null);
  let requestVersion = 0;

  const activeBranches = computed(() => branches.value.filter(item => item.trackingStatus === 'ACTIVE'));
  const selectedBranch = computed(() =>
    branches.value.find(item => item.id === selectedBranchId.value) ?? null,
  );
  const ready = computed(() => Boolean(context.value && selectedBranch.value?.status === 'READY'));
  const identity = computed(() => context.value
    ? `${context.value.branchId}:${context.value.contentVersion}:${context.value.commitSha}`
    : `${repositoryId.value ?? 'no-project'}:${selectedBranchId.value ?? 'no-branch'}:${loading.value ? 'loading' : 'pending'}:${error.value ?? ''}`);

  function clear() {
    requestVersion++;
    repositoryId.value = null;
    branches.value = [];
    selectedBranchId.value = null;
    context.value = null;
    loading.value = false;
    error.value = null;
  }

  async function load(nextRepositoryId: string | null, preferredBranchId?: string | null, defaultBranch?: string | null) {
    const version = ++requestVersion;
    repositoryId.value = nextRepositoryId;
    branches.value = [];
    selectedBranchId.value = null;
    context.value = null;
    error.value = null;
    loading.value = false;
    if (!nextRepositoryId) return;
    loading.value = true;
    try {
      const loaded = await branchesApi.list(nextRepositoryId);
      if (version !== requestVersion || repositoryId.value !== nextRepositoryId) return;
      branches.value = loaded;
      const active = loaded.filter(item => item.trackingStatus === 'ACTIVE');
      let persisted: string | null = null;
      try { persisted = localStorage.getItem(storageKey(nextRepositoryId)); }
      catch { /* Preferences are optional when browser storage is unavailable. */ }
      const target = active.find(item => item.id === preferredBranchId)
        ?? active.find(item => item.id === persisted)
        ?? active.find(item => item.name === defaultBranch)
        ?? active.find(item => item.status === 'READY')
        ?? active[0]
        ?? null;
      if (target) await select(target.id, version);
    } catch (exception) {
      if (version === requestVersion) {
        error.value = exception instanceof Error ? exception.message : '加载分支失败';
      }
    } finally {
      if (version === requestVersion) loading.value = false;
    }
  }

  async function select(branchId: string, parentVersion?: number) {
    const currentRepositoryId = repositoryId.value;
    if (!currentRepositoryId) return;
    const branch = branches.value.find(item => item.id === branchId && item.trackingStatus === 'ACTIVE');
    if (!branch) throw new Error('该分支未跟踪或已归档');
    const version = parentVersion ?? ++requestVersion;
    selectedBranchId.value = branchId;
    context.value = null;
    error.value = null;
    loading.value = false;
    try { localStorage.setItem(storageKey(currentRepositoryId), branchId); }
    catch { /* Keep the in-memory selection and create the server read context. */ }
    if (branch.status !== 'READY') return;
    loading.value = true;
    try {
      const created = await branchesApi.context(currentRepositoryId, branchId);
      if (version !== requestVersion || repositoryId.value !== currentRepositoryId) return;
      context.value = created;
    } catch (exception) {
      if (version === requestVersion) {
        error.value = exception instanceof Error ? exception.message : '锁定分支内容版本失败';
      }
      throw exception;
    } finally {
      if (version === requestVersion) loading.value = false;
    }
  }

  /** Read in the background and replace the pin only when the caller can safely apply it. */
  async function refreshCurrent(canApply: () => boolean = () => true) {
    const id = repositoryId.value, branchId = selectedBranchId.value;
    if (!id || !branchId || loading.value || !canApply()) return false;
    const version = ++requestVersion;
    const isCurrent = () => version === requestVersion && id === repositoryId.value && branchId === selectedBranchId.value;
    const loaded = await branchesApi.list(id);
    if (!isCurrent()) return false;
    const target = loaded.find(item => item.id === branchId && item.trackingStatus === 'ACTIVE');
    if (!target || target.status !== 'READY') throw new Error('当前分支尚未就绪，请稍后刷新');
    const created = await branchesApi.context(id, branchId);
    if (!isCurrent() || !canApply()) return false;
    branches.value = loaded;
    context.value = created;
    error.value = null;
    return true;
  }

  async function refresh() {
    await load(repositoryId.value, selectedBranchId.value);
  }

  return {
    repositoryId,
    branches,
    activeBranches,
    selectedBranchId,
    selectedBranch,
    context,
    loading,
    error,
    ready,
    identity,
    load,
    select,
    refresh,
    refreshCurrent,
    clear,
  };
});
