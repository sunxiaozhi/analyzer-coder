import { computed, onBeforeUnmount, readonly, ref, shallowRef } from 'vue';
import { cancelTask, listTasks, retryTask } from '@/api/indexJobs';
import type { Task, TaskStatus } from '@/types/tasks';

export function useIndexJobs() {
  const jobs = ref<Task[]>([]);
  const selectedJobId = shallowRef<string | null>(null);
  const loading = shallowRef(false);
  const error = shallowRef<string | null>(null);
  const pageNum = shallowRef(1);
  const pageSize = shallowRef(20);
  const total = shallowRef(0);
  let refreshTimer: number | undefined;

  const selectedJob = computed(() => jobs.value.find(job => job.id === selectedJobId.value) ?? jobs.value[0] ?? null);
  const counts = computed(() => {
    const result: Record<TaskStatus, number> = { QUEUED: 0, RUNNING: 0, CANCEL_REQUESTED: 0, SUCCEEDED: 0, FAILED: 0, CANCELED: 0 };
    jobs.value.forEach(job => { result[job.status] += 1; });
    return result;
  });

  async function refresh(options: { silent?: boolean } = {}) {
    if (!options.silent) loading.value = true;
    error.value = null;
    try {
      const result = await listTasks({ pageNum: pageNum.value, pageSize: pageSize.value });
      jobs.value = result.items;
      total.value = result.total;
      if (!result.items.length && pageNum.value > 1) {
        pageNum.value -= 1;
        await refresh(options);
        return;
      }
      if (!selectedJobId.value || !jobs.value.some(job => job.id === selectedJobId.value)) {
        selectedJobId.value = jobs.value[0]?.id ?? null;
      }
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '加载任务失败';
    } finally { loading.value = false; }
  }
  async function changePage(value: number) { pageNum.value = value; selectedJobId.value = null; await refresh(); }
  async function changePageSize(value: number) { pageSize.value = value; pageNum.value = 1; selectedJobId.value = null; await refresh(); }
  function selectJob(taskId: string) { selectedJobId.value = taskId; }
  async function cancel(taskId: string) { const updated = await cancelTask(taskId); replaceOrInsert(updated); }
  async function retry(taskId: string) { const created = await retryTask(taskId); pageNum.value = 1; selectedJobId.value = created.id; await refresh(); }
  function replaceOrInsert(task: Task) { const index = jobs.value.findIndex(item => item.id === task.id); jobs.value = index < 0 ? [task, ...jobs.value] : jobs.value.map(item => item.id === task.id ? task : item); }
  function startPolling() { stopPolling(); refreshTimer = window.setInterval(() => void refresh({ silent: true }), 2000); }
  function stopPolling() { if (refreshTimer !== undefined) { window.clearInterval(refreshTimer); refreshTimer = undefined; } }
  onBeforeUnmount(stopPolling);

  return {
    jobs: readonly(jobs), selectedJobId: readonly(selectedJobId), selectedJob, counts,
    loading: readonly(loading), error: readonly(error), pageNum: readonly(pageNum),
    pageSize: readonly(pageSize), total: readonly(total), refresh, changePage, changePageSize,
    selectJob, cancel, retry, startPolling, stopPolling,
  };
}