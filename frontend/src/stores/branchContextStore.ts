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
    ? `${context.value.branchId}:${context.value.snapshotId}:${context.value.commitSha}`
    : 'no-branch-context');

  function clear() {
    requestVersion++;
    repositoryId.value = null;
    branches.value = [];
    selectedBranchId.value = null;
    context.value = null;
    loading.value = false;
    error.value = null;
  }

  async function load(nextRepositoryId: string | null, preferredBranchId?: string | null) {
    const version = ++requestVersion;
    repositoryId.value = nextRepositoryId;
    branches.value = [];
    selectedBranchId.value = null;
    context.value = null;
    error.value = null;
    if (!nextRepositoryId) return;
    loading.value = true;
    try {
      const loaded = await branchesApi.list(nextRepositoryId);
      if (version !== requestVersion || repositoryId.value !== nextRepositoryId) return;
      branches.value = loaded;
      const active = loaded.filter(item => item.trackingStatus === 'ACTIVE');
      const persisted = localStorage.getItem(storageKey(nextRepositoryId));
      const target = active.find(item => item.id === preferredBranchId)
        ?? active.find(item => item.id === persisted)
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
    localStorage.setItem(storageKey(currentRepositoryId), branchId);
    if (branch.status !== 'READY') return;
    loading.value = true;
    try {
      const created = await branchesApi.context(currentRepositoryId, branchId);
      if (version !== requestVersion || repositoryId.value !== currentRepositoryId) return;
      context.value = created;
    } catch (exception) {
      if (version === requestVersion) {
        error.value = exception instanceof Error ? exception.message : '锁定分支快照失败';
      }
      throw exception;
    } finally {
      if (version === requestVersion) loading.value = false;
    }
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
    clear,
  };
});
