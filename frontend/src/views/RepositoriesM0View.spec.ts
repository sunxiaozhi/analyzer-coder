import { flushPromises, shallowMount } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import RepositoriesM0View from './RepositoriesM0View.vue';
import ProjectSelectionList from '@/features/repositories/ProjectSelectionList.vue';
import BranchWorkspace from '@/features/branches/BranchWorkspace.vue';
import ProjectSettingsPanel from '@/features/repositories/ProjectSettingsPanel.vue';
import RepositoryFormDialog from '@/features/repositories/RepositoryFormDialog.vue';
import { listRepositoryPage } from '@/api/repositories';
import { projectDraftsApi, type ProjectDraft } from '@/api/projectDrafts';
import { sourceImportsApi } from '@/api/sourceImports';
import type { Repository } from '@/types/api';

const project = (id: string, sourceType = 'LOCAL_GIT') => ({ id, name: id, sourceType, capabilities: { canUpdate: true, canConfigure: false } } as Repository);
let store: any;
let branches: any;
const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => store }));
vi.mock('@/stores/branchContextStore', () => ({ useBranchContextStore: () => branches }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: navigation.push, replace: navigation.replace }) }));
vi.mock('@/api/repositories', () => ({ listRepositoryPage: vi.fn(), updateRepository: vi.fn() }));
vi.mock('@/api/projectDrafts', () => ({ projectDraftsApi: { list: vi.fn(), remove: vi.fn(), create: vi.fn(), configure: vi.fn(), complete: vi.fn() } }));
vi.mock('@/api/sourceImports', () => ({ sourceImportsApi: { remoteJob: vi.fn(), job: vi.fn() } }));
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal<typeof import('element-plus')>(),
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}));
beforeEach(() => {
  vi.resetAllMocks();
  branches = reactive({ selectedBranchId: 'main', context: null, refresh: vi.fn(), select: vi.fn(async (id: string) => { branches.selectedBranchId = id; branches.context = { contextId: 'ctx-' + id, branchId: id, branchName: id }; }) });
  vi.mocked(projectDraftsApi.list).mockResolvedValue([]);
  vi.mocked(projectDraftsApi.remove).mockResolvedValue(undefined);
  vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>);
  const rows = [project('first'), project('second', 'REMOTE_GIT')];
  store = reactive({ repositories: rows, selectedRepositoryId: 'first', selectedRepository: rows[0],
    error: null, loadRepositories: vi.fn().mockResolvedValue(undefined),
    selectRepository: vi.fn(async (id: string) => { store.selectedRepositoryId = id; store.selectedRepository = rows.find(row => row.id === id); }),
  });
  vi.mocked(listRepositoryPage).mockResolvedValue({ items: rows, total: 2, pageNum: 1, pageSize: 15 } as Awaited<ReturnType<typeof listRepositoryPage>>);
});
const mountView = () => shallowMount(RepositoriesM0View, { global: { stubs: { ElInput: true, ElButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot /></button>' }, ElAlert: true, ElEmpty: true, ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue"><slot /><slot name="footer" /></section>' } } } });

const draft = (id: string, lifecycleStatus: ProjectDraft['lifecycleStatus'] = 'FAILED'): ProjectDraft => ({
  id, name: id, description: '', sourceType: 'GITLAB', sourceLocation: 'https://git.example.com/project.git',
  credentialId: null, lifecycleStatus, resultRepositoryId: null, error: '导入失败', version: 3, updatedAt: '',
});

it('deletes only the confirmed draft and reveals the next pending draft without changing the project', async () => {
  const pending = ['failed-1', 'failed-2', 'failed-3', 'failed-4'].map(id => draft(id));
  vi.mocked(projectDraftsApi.list).mockResolvedValue(pending);
  let finishDelete!: () => void;
  vi.mocked(projectDraftsApi.remove).mockImplementation(() => new Promise(resolve => { finishDelete = resolve; }));
  const wrapper = mountView(); await flushPromises();
  await wrapper.get('.draft-row .draft-actions button:last-child').trigger('click');
  await flushPromises();
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('已接入项目和源码不受影响'), '删除接入草稿', expect.anything());
  expect(projectDraftsApi.remove).toHaveBeenCalledExactlyOnceWith(pending[0]);
  expect(wrapper.get('.draft-actions button').attributes('disabled')).toBeDefined();
  expect(wrapper.findAll('.draft-copy b').map(row => row.text())).toContain('failed-1');
  finishDelete(); await flushPromises();
  expect(wrapper.findAll('.draft-copy b').map(row => row.text())).toEqual(['failed-2', 'failed-3', 'failed-4']);
  expect(store.selectedRepositoryId).toBe('first');
  expect(store.loadRepositories).toHaveBeenCalledTimes(1);
  expect(ElMessage.success).toHaveBeenCalledWith('接入草稿已删除');
  wrapper.unmount();
});

it('keeps a draft when deletion is canceled', async () => {
  vi.mocked(projectDraftsApi.list).mockResolvedValue([draft('failed')]);
  vi.mocked(ElMessageBox.confirm).mockRejectedValue('cancel');
  const wrapper = mountView(); await flushPromises();
  await wrapper.get('.draft-actions button:last-child').trigger('click'); await flushPromises();
  expect(projectDraftsApi.remove).not.toHaveBeenCalled();
  expect(wrapper.findAll('.draft-row')).toHaveLength(1);
  expect(ElMessage.error).not.toHaveBeenCalled();
  expect(wrapper.get('.draft-actions button:last-child').attributes('disabled')).toBeUndefined();
  wrapper.unmount();
});

it('keeps the draft and reports deletion failure', async () => {
  vi.mocked(projectDraftsApi.list).mockResolvedValue([draft('failed')]);
  vi.mocked(projectDraftsApi.remove).mockRejectedValue(new Error('项目草稿已变化，请刷新后重试'));
  const wrapper = mountView(); await flushPromises();
  await wrapper.get('.draft-actions button:last-child').trigger('click'); await flushPromises();
  expect(wrapper.findAll('.draft-row')).toHaveLength(1);
  expect(ElMessage.error).toHaveBeenCalledWith('项目草稿已变化，请刷新后重试');
  expect(ElMessage.success).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('disables draft actions during import and preserves retry for failed drafts', async () => {
  const failed = draft('failed');
  vi.mocked(projectDraftsApi.list).mockResolvedValue([draft('running', 'IMPORTING'), failed, draft('ready', 'READY')]);
  const wrapper = mountView(); await flushPromises();
  const rows = wrapper.findAll('.draft-row');
  expect(rows).toHaveLength(2);
  for (const button of rows[0]!.findAll('button')) {
    expect(button.attributes('disabled')).toBeDefined();
    await button.trigger('click');
  }
  expect(ElMessageBox.confirm).not.toHaveBeenCalled();
  expect(projectDraftsApi.remove).not.toHaveBeenCalled();
  await rows[1]!.get('button').trigger('click');
  expect(wrapper.getComponent(RepositoryFormDialog).props('initialDraft')).toEqual(failed);
  expect(wrapper.getComponent(RepositoryFormDialog).props('modelValue')).toBe(true);
  wrapper.unmount();
});

it('reuses the same draft when an import fails and the user retries in the open dialog', async () => {
  const created = { ...draft('draft', 'DRAFT'), version: 1, error: null };
  const configured = { ...created, lifecycleStatus: 'SOURCE_CONFIGURED' as const, version: 2 };
  const failed = { ...created, lifecycleStatus: 'FAILED' as const, version: 3 };
  vi.mocked(projectDraftsApi.list).mockResolvedValueOnce([]).mockResolvedValue([failed]);
  vi.mocked(projectDraftsApi.create).mockResolvedValue(created);
  vi.mocked(projectDraftsApi.configure).mockResolvedValue(configured);
  vi.mocked(sourceImportsApi.remoteJob).mockResolvedValue({ id: 'job' } as Awaited<ReturnType<typeof sourceImportsApi.remoteJob>>);
  vi.mocked(sourceImportsApi.job).mockResolvedValue({ status: 'FAILED', errorMessage: '连接失败' } as Awaited<ReturnType<typeof sourceImportsApi.job>>);
  const wrapper = mountView(); await flushPromises();
  const payload = { sourceType: 'GITLAB', name: 'Example', description: '', path: '', url: 'https://git.example.com/example.git', branch: '', credentialId: '', file: null };
  const dialog = wrapper.getComponent(RepositoryFormDialog);
  dialog.vm.$emit('submit', payload); await flushPromises();
  dialog.vm.$emit('submit', payload); await flushPromises();
  expect(projectDraftsApi.create).toHaveBeenCalledTimes(1);
  expect(projectDraftsApi.configure).toHaveBeenNthCalledWith(2, failed, 'GITLAB', payload.url, undefined);
  wrapper.unmount();
});

it('links project selection to a freshly mounted, permission-scoped branch workspace', async () => {
  const wrapper = mountView();
  await flushPromises();
  const first = wrapper.findComponent(BranchWorkspace);
  expect(first.props('repositoryId')).toBe('first');
  expect(first.props('showBranchList')).toBe(true);
  expect(first.props('canManage')).toBe(false);
  wrapper.findComponent(ProjectSelectionList).vm.$emit('select', store.repositories[1]);
  await flushPromises();
  const second = wrapper.findComponent(BranchWorkspace);
  expect(second.props('repositoryId')).toBe('second');
  expect(second.props('remoteSource')).toBe(true);
  expect(second.vm).not.toBe(first.vm);
  expect(wrapper.findComponent(ProjectSelectionList).props('selectedId')).toBe('second');
  wrapper.unmount();
});

it('maps ZIP projects to their fixed WORKSPACE branch', async () => {
  store.selectedRepository = project('archive', 'ZIP');
  store.selectedRepositoryId = 'archive';
  const wrapper = mountView();
  await flushPromises();
  const workspace = wrapper.getComponent(BranchWorkspace);
  expect(workspace.props('repositoryId')).toBe('archive');
  expect(workspace.props('defaultBranch')).toBe('WORKSPACE');
  expect(workspace.props('canTrack')).toBe(false);
  expect(workspace.props('remoteSource')).toBe(false);
  wrapper.unmount();
});


it('switches reading without navigation and keeps explicit code links available', async () => {
  const wrapper = mountView(); await flushPromises();
  wrapper.getComponent(BranchWorkspace).vm.$emit('read', 'release');
  await flushPromises();
  expect(branches.select).toHaveBeenCalledWith('release');
  expect(navigation.push).not.toHaveBeenCalled();
  expect(navigation.replace).toHaveBeenCalledWith({ query: { branchId: 'release', contextId: 'ctx-release' } });
  wrapper.getComponent(BranchWorkspace).vm.$emit('read', 'release', 'atlas');
  await flushPromises();
  expect(navigation.push).toHaveBeenCalledWith({ name: 'atlas', query: { branchId: 'release', contextId: 'ctx-release' } });
  wrapper.unmount();
});
it('opens a project menu settings dialog without changing the selected project', async () => {
  const wrapper = mountView(); await flushPromises();
  wrapper.getComponent(ProjectSelectionList).vm.$emit('settings', store.repositories[1]);
  await flushPromises();
  expect(wrapper.getComponent(ProjectSettingsPanel).props('repository').id).toBe('second');
  expect(store.selectedRepositoryId).toBe('first');
  expect(store.selectRepository).not.toHaveBeenCalled();
  wrapper.unmount();
});
