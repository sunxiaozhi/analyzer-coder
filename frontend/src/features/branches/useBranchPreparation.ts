import { computed, onScopeDispose, shallowRef, watch } from 'vue';
import { branchesApi, type BranchPreparationJob } from '@/api/branches';

export function useBranchPreparation(repositoryId: () => string, onCompleted: () => Promise<void | boolean>, branchId?: () => string | null) {
  const jobs = shallowRef<BranchPreparationJob[]>([]);
  const stateError = shallowRef(''), refreshError = shallowRef('');
  const refreshPending = shallowRef(false);
  const pendingCompletions = new Set<string>();
  const error = computed(() => stateError.value || refreshError.value);
  let generation = 0, scopeVersion = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false, completing = false;

  async function completePending() {
    if (stopped || completing || !pendingCompletions.size) return;
    const scope = scopeVersion, ids = [...pendingCompletions];
    completing = true;
    try {
      const applied = await onCompleted();
      if (stopped || scope !== scopeVersion) return;
      if (applied !== false) {
        ids.forEach(id => pendingCompletions.delete(id));
        refreshError.value = '';
      }
    } catch (cause) {
      if (!stopped && scope === scopeVersion) refreshError.value = cause instanceof Error ? cause.message : '页面状态刷新失败，请重试';
    } finally {
      if (scope === scopeVersion) { completing = false; refreshPending.value = pendingCompletions.size > 0; }
    }
  }

  async function refresh() {
    const version = ++generation;
    clearTimeout(timer);
    const id = repositoryId(), selectedBranch = branchId?.();
    if (!id || branchId && !selectedBranch) { jobs.value = []; stateError.value = ''; return; }
    try {
      const result = await branchesApi.preparationJobs(id);
      if (stopped || version !== generation || id !== repositoryId() || selectedBranch !== branchId?.()) return;
      const rows = branchId ? result.filter(job => job.branchId === selectedBranch) : result;
      const previous = jobs.value;
      jobs.value = rows;
      stateError.value = '';
      rows.forEach(job => {
        if (job.status === 'SUCCEEDED' && previous.some(old => old.id === job.id && old.status !== 'SUCCEEDED')) pendingCompletions.add(job.id);
      });
      refreshPending.value = pendingCompletions.size > 0;
      await completePending();
    } catch (cause) {
      if (!stopped && version === generation) stateError.value = cause instanceof Error ? cause.message : '任务状态读取失败';
    } finally {
      if (!stopped && version === generation) timer = setTimeout(refresh, 2500);
    }
  }

  function accepted(job: BranchPreparationJob) {
    jobs.value = [...jobs.value.filter(item => item.branchId !== job.branchId || item.kind !== job.kind), job];
    if (job.status === 'SUCCEEDED') { pendingCompletions.add(job.id); refreshPending.value = true; }
    void refresh();
  }

  watch(() => [repositoryId(), branchId?.()], () => {
    scopeVersion++; completing = false; pendingCompletions.clear();
    jobs.value = []; refreshPending.value = false; stateError.value = ''; refreshError.value = '';
    void refresh();
  }, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { stopped = true; ++generation; ++scopeVersion; clearTimeout(timer); });
  return { jobs, error, refreshPending, refreshError, accepted, refresh };
}
