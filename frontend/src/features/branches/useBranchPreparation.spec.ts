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
