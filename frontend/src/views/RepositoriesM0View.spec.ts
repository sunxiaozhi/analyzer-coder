import { flushPromises, shallowMount } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import RepositoriesM0View from './RepositoriesM0View.vue';
import ProjectSelectionList from '@/features/repositories/ProjectSelectionList.vue';
import BranchWorkspace from '@/features/branches/BranchWorkspace.vue';
import ProjectSettingsPanel from '@/features/repositories/ProjectSettingsPanel.vue';
import SingleVersionOperations from '@/features/repositories/SingleVersionOperations.vue';
import { listRepositoryPage } from '@/api/repositories';
import { projectDraftsApi } from '@/api/projectDrafts';
import type { Repository } from '@/types/api';

const project = (id: string, sourceType = 'LOCAL_GIT') => ({ id, name: id, sourceType, capabilities: { canUpdate: true, canConfigure: false } } as Repository);
let store: any;
let branches: any;
const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => store }));
vi.mock('@/stores/branchContextStore', () => ({ useBranchContextStore: () => branches }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: navigation.push, replace: navigation.replace }) }));
vi.mock('@/api/repositories', () => ({ listRepositoryPage: vi.fn(), updateRepository: vi.fn() }));
vi.mock('@/api/projectDrafts', () => ({ projectDraftsApi: { list: vi.fn() } }));
beforeEach(() => {
  vi.resetAllMocks();
  branches = reactive({ selectedBranchId: 'main', context: null, refresh: vi.fn(), select: vi.fn(async (id: string) => { branches.selectedBranchId = id; branches.context = { contextId: 'ctx-' + id, branchId: id, branchName: id }; }) });
  vi.mocked(projectDraftsApi.list).mockResolvedValue([]);
  const rows = [project('first'), project('second', 'REMOTE_GIT')];
  store = reactive({ repositories: rows, selectedRepositoryId: 'first', selectedRepository: rows[0],
    error: null, loadRepositories: vi.fn().mockResolvedValue(undefined),
    selectRepository: vi.fn(async (id: string) => { store.selectedRepositoryId = id; store.selectedRepository = rows.find(row => row.id === id); }),
  });
  vi.mocked(listRepositoryPage).mockResolvedValue({ items: rows, total: 2, pageNum: 1, pageSize: 15 } as Awaited<ReturnType<typeof listRepositoryPage>>);
});
const mountView = () => shallowMount(RepositoriesM0View, { global: { stubs: { ElInput: true, ElButton: true, ElAlert: true, ElEmpty: true, ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue"><slot /><slot name="footer" /></section>' } } } });

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

it('does not offer Git branch operations for ZIP projects', async () => {
  store.selectedRepository = project('archive', 'ZIP');
  store.selectedRepositoryId = 'archive';
  const wrapper = mountView();
  await flushPromises();
  expect(wrapper.findComponent(BranchWorkspace).exists()).toBe(false);
  expect(wrapper.findComponent(SingleVersionOperations).exists()).toBe(true);
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
