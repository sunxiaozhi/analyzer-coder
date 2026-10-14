import { effectScope } from 'vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { sourceImportsApi, type RepositoryImportJob } from '@/api/sourceImports';
import { useRepositoryImportMonitor } from './useRepositoryImportMonitor';
vi.mock('@/api/sourceImports', () => ({ sourceImportsApi: { job: vi.fn() } }));
const running: RepositoryImportJob = { id: 'job', status: 'RUNNING', currentStep: 'clone_repository', projectDraftId: 'draft', errorMessage: null, resultRepositoryId: null };
beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers(); sessionStorage.clear(); });
afterEach(() => vi.useRealTimers());

it('continues a long accepted import in the background and refreshes projects when it finishes', async () => {
  vi.mocked(sourceImportsApi.job).mockResolvedValue(running);
  const settled = vi.fn().mockResolvedValue(undefined);
  const scope = effectScope();
  const monitor = scope.run(() => useRepositoryImportMonitor('imports', settled))!;
  monitor.track('Project', running);
  const foreground = monitor.wait('job');
  await vi.advanceTimersByTimeAsync(120000);
  expect(await foreground).toBeNull();
  expect(monitor.tasks.value[0].job.status).toBe('RUNNING');
  expect(monitor.tasks.value[0].error).toBe('');
  expect(sourceImportsApi.job).toHaveBeenCalledTimes(120);
  vi.mocked(sourceImportsApi.job).mockResolvedValue({ ...running, status: 'SUCCEEDED', resultRepositoryId: 'project' });
  await vi.advanceTimersByTimeAsync(5000);
  expect(monitor.tasks.value[0].job.resultRepositoryId).toBe('project');
  expect(settled).toHaveBeenCalledOnce();
  expect(JSON.parse(sessionStorage.getItem('imports')!)).toEqual([]);
  scope.stop();
});

it('restores an active import after reopening and retries progress lookup without marking the import failed', async () => {
  const first = effectScope();
  first.run(() => useRepositoryImportMonitor('imports', vi.fn()))!.track('Project', running);
  first.stop();
  vi.mocked(sourceImportsApi.job).mockRejectedValueOnce(new Error('临时断网')).mockResolvedValue({ ...running, status: 'FAILED', errorMessage: '认证失败' });
  const settled = vi.fn().mockResolvedValue(undefined);
  const second = effectScope();
  const monitor = second.run(() => useRepositoryImportMonitor('imports', settled))!;
  await monitor.refresh();
  expect(monitor.tasks.value[0].job.status).toBe('RUNNING');
  expect(monitor.tasks.value[0].error).toBe('临时断网');
  await monitor.refresh();
  expect(monitor.tasks.value[0].job.status).toBe('FAILED');
  expect(monitor.tasks.value[0].job.errorMessage).toBe('认证失败');
  expect(settled).toHaveBeenCalledOnce();
  second.stop();
});

it('reports an actual foreground task failure instead of treating it as a slow import', async () => {
  vi.mocked(sourceImportsApi.job).mockResolvedValue({ ...running, status: 'FAILED', errorMessage: '仓库不存在' });
  const scope = effectScope();
  const monitor = scope.run(() => useRepositoryImportMonitor('imports', vi.fn()))!;
  monitor.track('Project', running);
  await expect(monitor.wait('job')).rejects.toThrow('仓库不存在');
  expect(monitor.tasks.value[0].job.status).toBe('FAILED');
  scope.stop();
});
