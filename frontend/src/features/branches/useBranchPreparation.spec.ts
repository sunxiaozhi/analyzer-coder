import { defineComponent } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { branchesApi, type BranchPreparationJob } from '@/api/branches';
import { useBranchPreparation } from './useBranchPreparation';

vi.mock('@/api/branches', () => ({ branchesApi: { preparationJobs: vi.fn() } }));
afterEach(() => { vi.useRealTimers(); vi.resetAllMocks(); });

it('refreshes branch metadata on completion and stops polling after closing', async () => {
  vi.useFakeTimers();
  const job: BranchPreparationJob = { id: 'job', branchId: 'branch', status: 'RUNNING', stage: 'INDEXING', error: null, kind: 'SNAPSHOT', snapshotId: null };
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
