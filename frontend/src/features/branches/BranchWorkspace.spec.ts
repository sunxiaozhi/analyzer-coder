import { shallowMount, flushPromises } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import BranchWorkspace from './BranchWorkspace.vue';
import BranchListTable from './BranchListTable.vue';
import BranchDetailsDialog from './BranchDetailsDialog.vue';
import BranchTrackDialog from './BranchTrackDialog.vue';
import { branchesApi, type RepositoryBranch } from '@/api/branches';
vi.mock('@/api/branches', () => ({ branchesApi: { list: vi.fn(), context: vi.fn(), indexStatuses: vi.fn(), preparationJobs: vi.fn(), codeOperation: vi.fn(), discover: vi.fn() } }));
const branch = (id: string): RepositoryBranch => ({ id, name: id, contentVersion: 's-' + id, commitSha: 'abc', status: 'READY', error: null, generation: 1, trackingStatus: 'ACTIVE', archivedAt: null });
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(branchesApi.list).mockResolvedValue([branch('main'), branch('release')]);
  vi.mocked(branchesApi.indexStatuses).mockResolvedValue([]);
  vi.mocked(branchesApi.preparationJobs).mockResolvedValue([]);
  vi.mocked(branchesApi.context).mockResolvedValue({ contextId: 'ctx-release', repositoryId: 'p', branchId: 'release', branchName: 'release', contentVersion: 's-release', commitSha: 'abc', expiresAt: '' });
});
it('keeps the table primary and opens a row-specific details dialog on demand', async () => {
  const wrapper = shallowMount(BranchWorkspace, { props: { repositoryId: 'p', canMaintain: true, defaultBranch: 'main' } });
  await flushPromises();
  expect(wrapper.getComponent(BranchDetailsDialog).props('modelValue')).toBe(false);
  expect(wrapper.getComponent(BranchTrackDialog).props('modelValue')).toBe(false);
  expect(wrapper.find('.branch-controls').exists()).toBe(false);
  wrapper.getComponent(BranchListTable).vm.$emit('select', 'release', 'tasks');
  await flushPromises();
  expect(wrapper.getComponent(BranchDetailsDialog).props('modelValue')).toBe(true);
  expect(wrapper.getComponent(BranchDetailsDialog).props('tab')).toBe('tasks');
  expect(wrapper.getComponent(BranchDetailsDialog).props('branch')?.name).toBe('release');
  expect(branchesApi.context).toHaveBeenCalledWith('p', 'release');
  wrapper.unmount();
});
it('switches the reading branch through the table without requesting page navigation', async () => {
  const wrapper = shallowMount(BranchWorkspace, { props: { repositoryId: 'p', canMaintain: false } });
  await flushPromises();
  wrapper.getComponent(BranchListTable).vm.$emit('read', 'main');
  expect(wrapper.emitted('read')).toEqual([['main', undefined]]);
  expect(wrapper.findComponent(BranchTrackDialog).exists()).toBe(false);
  wrapper.unmount();
});
