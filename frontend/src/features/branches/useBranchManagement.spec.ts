import { defineComponent, h, shallowRef } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import { branchesApi, type BranchContext, type BranchIndexStatus, type BranchPreparationJob, type RepositoryBranch } from '@/api/branches';
import { useBranchManagement } from './useBranchManagement';
let indexes: any, preparation: any, maintain: boolean, manage: boolean;
vi.mock('@/api/branches', () => ({ branchesApi: { list: vi.fn(), context: vi.fn(), codeOperation: vi.fn(), prepareVectors: vi.fn(), track: vi.fn(), discover: vi.fn(), archive: vi.fn(), restore: vi.fn() } }));
vi.mock('./useBranchIndexes', () => ({ useBranchIndexes: () => indexes }));
vi.mock('./useBranchPreparation', () => ({ useBranchPreparation: () => preparation }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn() }, ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) } }));
const branch = (id: string): RepositoryBranch => ({ id, name: id, contentVersion: 's-' + id, commitSha: id, status: 'READY', error: null, generation: 1, trackingStatus: 'ACTIVE', archivedAt: null });
const context = (id: string): BranchContext => ({ repositoryId: 'p', branchId: id, branchName: id, contentVersion: 's-' + id, commitSha: id, contextId: 'ctx-' + id, expiresAt: '' });
beforeEach(() => {
  vi.resetAllMocks(); maintain = true; manage = false;
  indexes = { statuses: shallowRef<BranchIndexStatus[]>([]), refresh: vi.fn().mockResolvedValue(undefined), error: shallowRef('') };
  preparation = { jobs: shallowRef<BranchPreparationJob[]>([]), accepted: vi.fn(), error: shallowRef('') };
  vi.mocked(branchesApi.list).mockResolvedValue([branch('main'), branch('release')]);
  vi.mocked(branchesApi.context).mockImplementation(async (_, id) => context(id));
  vi.mocked(branchesApi.codeOperation).mockResolvedValue({ id: 'j', branchId: 'release', kind: 'GRAPH', status: 'QUEUED', stage: 'QUEUED', contentVersion: 's-release', error: null });
});
function setup() {
  let result!: ReturnType<typeof useBranchManagement>;
  const wrapper = mount(defineComponent({ setup() { result = useBranchManagement({ repositoryId: () => 'p', canMaintain: () => maintain, canManage: () => manage, remoteSource: () => true, initialBranchId: () => undefined, changed: vi.fn() }); return () => h('div'); } }));
  return { result, wrapper };
}
it('does not lock a branch just to display the table', async () => {
  const { result, wrapper } = setup(); await flushPromises();
  expect(result.branches.value).toHaveLength(2);
  expect(branchesApi.context).not.toHaveBeenCalled();
  wrapper.unmount();
});
it('pins the row branch for rebuilding without using the detail branch context', async () => {
  const { result, wrapper } = setup(); await flushPromises();
  await result.select('main');
  await result.operate('release', 'GRAPH');
  expect(branchesApi.context).toHaveBeenLastCalledWith('p', 'release');
  expect(branchesApi.codeOperation).toHaveBeenCalledWith('p', 'release', 'GRAPH', 'ctx-release');
  expect(result.selectedId.value).toBe('main');
  expect(result.context.value?.branchId).toBe('main');
  expect(preparation.accepted).toHaveBeenCalled();
  wrapper.unmount();
});
it('allows one-click preparation without requiring a published contentVersion', async () => {
  const { result, wrapper } = setup(); await flushPromises();
  result.branches.value = [{ ...branch('release'), contentVersion: null, status: 'PENDING' }];
  await result.operate('release', 'PREPARE');
  expect(branchesApi.context).not.toHaveBeenCalled();
  expect(branchesApi.codeOperation).toHaveBeenCalledWith('p', 'release', 'PREPARE', undefined);
  wrapper.unmount();
});
it('respects maintenance rights and active jobs when submitting a row operation', async () => {
  const { result, wrapper } = setup(); await flushPromises();
  maintain = false; await result.operate('main', 'SYNC');
  maintain = true;
  preparation.jobs.value = [{ id: 'j', branchId: 'main', kind: 'SYNC', status: 'RUNNING', stage: 'SYNC', contentVersion: null, error: null }];
  await result.operate('main', 'SYNC');
  expect(branchesApi.codeOperation).not.toHaveBeenCalled();
  await result.archive('main'); await result.restoreBranch('main');
  expect(branchesApi.archive).not.toHaveBeenCalled();
  expect(branchesApi.restore).not.toHaveBeenCalled();
  wrapper.unmount();
});
it('ignores a slow detail context after selecting another branch', async () => {
  let resolve!: (value: BranchContext) => void;
  vi.mocked(branchesApi.context).mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValueOnce(context('release'));
  const { result, wrapper } = setup(); await flushPromises();
  const old = result.select('main');
  await result.select('release');
  resolve(context('main')); await old;
  expect(result.context.value?.branchId).toBe('release');
  wrapper.unmount();
});
