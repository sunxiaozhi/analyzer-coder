import { onScopeDispose, shallowRef } from 'vue';
import { sourceImportsApi, type RepositoryImportJob } from '@/api/sourceImports';

export interface TrackedImport { name: string; job: RepositoryImportJob; error: string }
const active = (job: RepositoryImportJob) => job.status === 'QUEUED' || job.status === 'RUNNING';

/** Keep accepted imports visible after the foreground wait ends and across page reloads. */
export function useRepositoryImportMonitor(storageKey: string, onSettled: () => Promise<void>) {
  const tasks = shallowRef<TrackedImport[]>([]);
  const refreshing = shallowRef(false);
  const foreground = new Set<string>();
  let stopped = false, timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const saved: unknown = JSON.parse(sessionStorage.getItem(storageKey) ?? '[]');
    if (Array.isArray(saved)) tasks.value = saved.filter(item => typeof item?.name === 'string' && typeof item?.job?.id === 'string' && active(item.job));
  } catch { /* A damaged local progress cache must not prevent opening project management. */ }

  function persist() {
    try { sessionStorage.setItem(storageKey, JSON.stringify(tasks.value.filter(item => active(item.job)))); }
    catch { /* Import execution is independent from browser storage availability. */ }
  }
  function track(name: string, job: RepositoryImportJob) {
    tasks.value = [...tasks.value.filter(item => item.job.id !== job.id), { name, job, error: '' }];
    persist();
  }
  async function read(id: string) {
    const result = await sourceImportsApi.job(id);
    if (stopped) return result;
    tasks.value = tasks.value.map(item => item.job.id === id ? { ...item, job: result, error: '' } : item);
    persist();
    return result;
  }
  function schedule() {
    clearTimeout(timer);
    if (!stopped && tasks.value.some(item => active(item.job) && !foreground.has(item.job.id))) timer = setTimeout(refresh, 5000);
  }
  async function refresh() {
    if (stopped || refreshing.value) return;
    clearTimeout(timer); refreshing.value = true;
    let settled = false;
    try {
      await Promise.all(tasks.value.filter(item => active(item.job) && !foreground.has(item.job.id)).map(async item => {
        try { const job = await read(item.job.id); settled ||= !active(job); }
        catch (cause) {
          if (!stopped) tasks.value = tasks.value.map(row => row.job.id === item.job.id
            ? { ...row, error: cause instanceof Error ? cause.message : '进度暂不可用，请重试' } : row);
        }
      }));
      if (settled && !stopped) await onSettled();
    } finally { refreshing.value = false; schedule(); }
  }
  async function wait(id: string, attempts = 120) {
    foreground.add(id);
    try {
      for (let attempt = 0; attempt < attempts && !stopped; attempt++) {
        let job: RepositoryImportJob;
        try { job = await read(id); }
        catch (cause) {
          if (!stopped) tasks.value = tasks.value.map(row => row.job.id === id
            ? { ...row, error: cause instanceof Error ? cause.message : '进度暂不可用，请重试' } : row);
          return null;
        }
        if (job.status === 'SUCCEEDED') return job;
        if (!active(job)) throw new Error(job.errorMessage ?? '项目接入未完成');
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
      return null;
    } finally { foreground.delete(id); schedule(); }
  }
  function dismiss(id: string) {
    tasks.value = tasks.value.filter(item => item.job.id !== id || active(item.job));
  }
  if (tasks.value.length) schedule();
  onScopeDispose(() => { stopped = true; clearTimeout(timer); });
  return { tasks, refreshing, track, wait, refresh, dismiss };
}
