import { defineComponent, shallowRef } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { branchesApi, type BranchPreparationJob } from '@/api/branches';
import { useBranchPreparation } from './useBranchPreparation';

vi.mock('@/api/branches', () => ({ branchesApi: { preparationJobs: vi.fn() } }));
afterEach(() => { vi.useRealTimers(); vi.resetAllMocks(); });

it('refreshes branch metadata on completion and stops polling after closing', async () => {
  vi.useFakeTimers();
  const job: BranchPreparationJob = { id: 'job', branchId: 'branch', status: 'RUNNING', stage: 'INDEXING', error: null, kind: 'PREPARE', contentVersion: null };
  vi.mocked(branchesApi.preparationJobs).mockResolvedValueOnce([job])
    .mockResolvedValue([{ ...job, status: 'SUCCEEDED', stage: 'COMPLETED' }]);
  const completed = vi.fn().mockResolvedValue(undefined);
  const wrapper = mount(defineComponent({
    setup() { useBranchPreparation(() => 'repo', completed); return () => null; },
  }));
  await flushPromises();
  expect(completed).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(2500);
  expect(completed).toHaveBeenCalledTimes(1);
  wrapper.unmount();
  await vi.advanceTimersByTimeAsync(10000);
  expect(branchesApi.preparationJobs).toHaveBeenCalledTimes(2);
});

it('keeps branch task responses isolated during a rapid branch change', async () => {
  const branch = shallowRef<string | null>('old');
  let finish!: (rows: BranchPreparationJob[]) => void;
  const job = { id: 'new-job', branchId: 'new', status: 'RUNNING', stage: 'INDEXING', error: null, kind: 'PREPARE', contentVersion: 'new-version' } as const;
  vi.mocked(branchesApi.preparationJobs).mockReturnValueOnce(new Promise(resolve => { finish = resolve; })).mockResolvedValue([job]);
  const completed = vi.fn(); let tasks!: ReturnType<typeof useBranchPreparation>;
  const wrapper = mount(defineComponent({ setup() { tasks = useBranchPreparation(() => 'repo', completed, () => branch.value); return () => null; } }));
  branch.value = 'new'; await flushPromises();
  finish([{ ...job, id: 'old-job', branchId: 'old', status: 'SUCCEEDED' }]); await flushPromises();
  expect(tasks.jobs.value).toEqual([job]); expect(completed).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('retries failed completion work without changing the successful task status', async () => {
  vi.useFakeTimers();
  const job = { id: 'job', branchId: 'b', kind: 'PREPARE', status: 'RUNNING', stage: 'INDEXING', error: null, contentVersion: 'v' } as const;
  vi.mocked(branchesApi.preparationJobs).mockResolvedValueOnce([job]).mockResolvedValue([{ ...job, status: 'SUCCEEDED' }]);
  const completed = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValue(undefined);
  let tasks!: ReturnType<typeof useBranchPreparation>;
  const wrapper = mount(defineComponent({ setup() { tasks = useBranchPreparation(() => 'repo', completed); return () => null; } }));
  try {
    await flushPromises(); await tasks.refresh();
    expect(tasks.jobs.value[0].status).toBe('SUCCEEDED'); expect(tasks.refreshPending.value).toBe(true);
    expect(tasks.error.value).toBe('network');
    await tasks.refresh(); expect(completed).toHaveBeenCalledTimes(2);
    expect(tasks.refreshPending.value).toBe(false); expect(tasks.error.value).toBe('');
    await tasks.refresh(); expect(completed).toHaveBeenCalledTimes(2);
  } finally { wrapper.unmount(); }
});

it('clears deferred completion work when the user changes scope', async () => {
  const branch = shallowRef<string | null>('old');
  const job = { id: 'job', branchId: 'old', kind: 'PREPARE', status: 'RUNNING', stage: 'INDEXING', error: null, contentVersion: 'v' } as const;
  vi.mocked(branchesApi.preparationJobs).mockResolvedValueOnce([job]).mockResolvedValue([{ ...job, status: 'SUCCEEDED' }]);
  const completed = vi.fn().mockResolvedValue(false);
  let tasks!: ReturnType<typeof useBranchPreparation>;
  const wrapper = mount(defineComponent({ setup() { tasks = useBranchPreparation(() => 'repo', completed, () => branch.value); return () => null; } }));
  try {
    await flushPromises(); await tasks.refresh(); expect(tasks.refreshPending.value).toBe(true);
    branch.value = 'new'; await flushPromises();
    expect(tasks.refreshPending.value).toBe(false); expect(completed).toHaveBeenCalledTimes(1);
  } finally { wrapper.unmount(); }
});
