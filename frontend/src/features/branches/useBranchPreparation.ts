import { onBeforeUnmount, shallowRef, watch } from 'vue';
import { branchesApi, type BranchPreparationJob } from '@/api/branches';

export function useBranchPreparation(repositoryId: () => string, onCompleted: () => Promise<void>) {
  const jobs = shallowRef<BranchPreparationJob[]>([]);
  const error = shallowRef('');
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;

  async function refresh() {
    const version = ++generation;
    clearTimeout(timer);
    try {
      const rows = await branchesApi.preparationJobs(repositoryId());
      if (stopped || version !== generation) return;
      const previous = jobs.value;
      jobs.value = rows;
      error.value = '';
      if (rows.some(job => job.status === 'SUCCEEDED' && previous.some(old => old.id === job.id && old.status !== 'SUCCEEDED'))) {
        await onCompleted();
      }
    } catch (cause) {
      if (!stopped && version === generation) error.value = cause instanceof Error ? cause.message : '任务状态读取失败';
    } finally {
      if (!stopped && version === generation) timer = setTimeout(refresh, 2500);
    }
  }

  function accepted(job: BranchPreparationJob) {
    jobs.value = [...jobs.value.filter(item => item.branchId !== job.branchId || item.kind !== job.kind), job];
    void refresh();
  }

  watch(repositoryId, () => { jobs.value = []; void refresh(); }, { immediate: true });
  onBeforeUnmount(() => { stopped = true; ++generation; clearTimeout(timer); });
  return { jobs, error, accepted };
}
