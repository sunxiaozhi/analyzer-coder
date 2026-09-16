
import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import BranchTaskHistory from './BranchTaskHistory.vue';
import { branchesApi } from '@/api/branches';
vi.mock('@/api/branches', () => ({ branchesApi: { preparationHistory: vi.fn() } }));
beforeEach(() => vi.resetAllMocks());
const history = (id: string) => ({ items: [{ id: 'j-' + id, branchId: id, kind: 'GRAPH' as const, status: 'FAILED' as const, stage: 'FAILED', snapshotId: 'old-' + id, error: id + ' graph build failed' }], total: 1, pageNum: 1, pageSize: 15, pages: 1 });
function panel() { return mount(BranchTaskHistory, { props: { repositoryId: 'p', branchId: 'release', revision: '' }, global: { stubs: { AppPagination: true, ElButton: true, ElAlert: true } } }); }
it('loads paginated history for the dialog branch and displays its failure reason', async () => {
  vi.mocked(branchesApi.preparationHistory).mockResolvedValue(history('release'));
  const wrapper = panel(); await flushPromises();
  expect(branchesApi.preparationHistory).toHaveBeenCalledWith('p', 1, 15, 'release');
  expect(wrapper.text()).toContain('release graph build failed');
  expect(wrapper.text()).toContain('old-rele');
  wrapper.unmount();
});
it('ignores old task history after switching the dialog to another branch', async () => {
  let resolve!: (value: ReturnType<typeof history>) => void;
  vi.mocked(branchesApi.preparationHistory).mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValueOnce(history('main'));
  const wrapper = panel();
  await wrapper.setProps({ branchId: 'main' }); await flushPromises();
  resolve(history('release')); await flushPromises();
  expect(wrapper.text()).toContain('main graph build failed');
  expect(wrapper.text()).not.toContain('release graph build failed');
  wrapper.unmount();
});
