import { flushPromises, shallowMount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { reactive } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import WorkspaceShell from './WorkspaceShell.vue';
import KnowledgeView from '@/views/KnowledgeView.vue';
import AskView from '@/views/AskView.vue';
import AskConversationPanel from '@/features/ask/AskConversationPanel.vue';
import { branchesApi, type RepositoryBranch } from '@/api/branches';
import { intelligenceApi } from '@/api/intelligence';
import { useBranchContextStore } from '@/stores/branchContextStore';
import type { Repository } from '@/types/api';

let repositories: {
  selectedRepositoryId: string | null;
  selectedRepository: Repository | null;
  repositories: Repository[];
  loadRepositories: ReturnType<typeof vi.fn>;
};
let route: { name: string; fullPath: string; meta: Record<string, string>; query: Record<string, string> };
const navigation = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => repositories }));
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal<typeof import('vue-router')>(),
  useRoute: () => route, useRouter: () => navigation,
}));
vi.mock('@/api/branches', () => ({ branchesApi: {
  list: vi.fn(), context: vi.fn(), validations: vi.fn(), contentVersionIndexStatus: vi.fn(),
} }));
vi.mock('@/api/intelligence', () => ({ intelligenceApi: {
  cards: vi.fn(), markdownSources: vi.fn(), sourceDrift: vi.fn(), history: vi.fn(), askModels: vi.fn(),
} }));

const project = (id: string, sourceType = 'KNOWLEDGE') => ({
  id, name: id, sourceType, branch: sourceType === 'KNOWLEDGE' || sourceType === 'ZIP' ? 'WORKSPACE' : 'main',
  capabilities: { canRead: true, canUpdate: true, canConfigure: true },
} as Repository);
function select(project: Repository | null) {
  repositories.selectedRepository = project;
  repositories.selectedRepositoryId = project?.id ?? null;
}
const workspace: RepositoryBranch = {
  id: 'workspace', name: 'WORKSPACE', status: 'READY', trackingStatus: 'ACTIVE',
  contentVersion: null, commitSha: null, generation: 0, error: null, archivedAt: null,
};
const stubs = {
  ElSelect: true, ElOption: true, ElInput: true, ElButton: true, ElEmpty: true, ElAlert: true,
  ElTag: true, ElDialog: true, ElTimeline: true, ElTimelineItem: true, ElCard: true,
  RouterLink: { template: '<a><slot /></a>' }, RouterView: true,
};
const mountShell = () => shallowMount(WorkspaceShell, { global: { stubs } });

beforeEach(() => {
  vi.resetAllMocks();
  setActivePinia(createPinia());
  localStorage.clear();
  route = reactive({ name: 'repositories', fullPath: '/repositories', meta: {}, query: {} });
  repositories = reactive({ selectedRepositoryId: null, selectedRepository: null, repositories: [], loadRepositories: vi.fn() });
  vi.mocked(branchesApi.list).mockResolvedValue([workspace]);
  vi.mocked(branchesApi.context).mockImplementation(async (repositoryId, branchId) => ({
    contextId: `context-${repositoryId}`, repositoryId, branchId, branchName: 'WORKSPACE',
    contentVersion: null, commitSha: null, expiresAt: '',
  }));
  vi.mocked(intelligenceApi.cards).mockResolvedValue([]);
  vi.mocked(intelligenceApi.history).mockResolvedValue([]);
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([]);
});

it('automatically selects the virtual branch of a new knowledge project for knowledge management and questions', async () => {
  const shell = mountShell();
  select(project('new-knowledge'));
  const knowledge = shallowMount(KnowledgeView, { global: { stubs, directives: { loading: () => {} } } });
  const ask = shallowMount(AskView, { global: { stubs } });
  await flushPromises();
  expect(branchesApi.list).toHaveBeenCalledWith('new-knowledge');
  expect(branchesApi.context).toHaveBeenCalledWith('new-knowledge', 'workspace');
  expect(intelligenceApi.cards).toHaveBeenCalledWith('new-knowledge', 'context-new-knowledge');
  expect(intelligenceApi.history).toHaveBeenCalledWith('new-knowledge', 50, 0, 'context-new-knowledge');
  expect(ask.getComponent(AskConversationPanel).props('disabled')).toBe(false);
  expect(intelligenceApi.markdownSources).not.toHaveBeenCalled();
  expect(branchesApi.validations).not.toHaveBeenCalled();
  expect(branchesApi.contentVersionIndexStatus).not.toHaveBeenCalled();
  expect(shell.find('.global-branch-switcher').exists()).toBe(true);
  expect(shell.text()).toContain('虚拟分支 · 无代码');
  expect(shell.text()).not.toContain('提交 null');
  expect(navigation.replace).toHaveBeenCalledWith({ query: { branchId: 'workspace', contextId: 'context-new-knowledge' } });
  expect(knowledge.text()).not.toContain('请选择分支');
  knowledge.unmount(); ask.unmount(); shell.unmount();
});

it('replaces stale Git branch coordinates with the knowledge virtual branch when reopening a project', async () => {
  route.query = { branchId: 'old-git-branch', contextId: 'old-context', cardId: 'knowledge-card' };
  select(project('knowledge'));
  const shell = mountShell();
  await flushPromises();
  expect(branchesApi.context).toHaveBeenCalledExactlyOnceWith('knowledge', 'workspace');
  expect(navigation.replace).toHaveBeenCalledExactlyOnceWith({ query: { cardId: 'knowledge-card', branchId: 'workspace', contextId: 'context-knowledge' } });
  expect(useBranchContextStore().context?.repositoryId).toBe('knowledge');
  expect(shell.find('.global-branch-switcher').exists()).toBe(true);
  shell.unmount();
});

it('clears a previous project context while automatically loading the knowledge virtual branch', async () => {
  const branches = useBranchContextStore();
  branches.selectedBranchId = 'git-main';
  branches.context = { contextId: 'old-context', repositoryId: 'git', branchId: 'git-main', branchName: 'main', contentVersion: 'version', commitSha: 'commit', expiresAt: '' };
  let finish!: (value: RepositoryBranch[]) => void;
  vi.mocked(branchesApi.list).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  select(project('knowledge'));
  const shell = mountShell();
  expect(branches.context).toBeNull();
  expect(branches.loading).toBe(true);
  expect(shell.find('.global-branch-switcher').exists()).toBe(true);
  finish([workspace]);
  await flushPromises();
  expect(branches.context?.repositoryId).toBe('knowledge');
  shell.unmount();
});

it('preserves explicit Git branch selection and its context in navigation', async () => {
  route.query = { branchId: 'release' };
  vi.mocked(branchesApi.list).mockResolvedValue([
    { ...workspace, id: 'main', name: 'main', contentVersion: 'main-version' },
    { ...workspace, id: 'release', name: 'release', contentVersion: 'release-version' },
  ]);
  select(project('git', 'REMOTE_GIT'));
  const shell = mountShell();
  await flushPromises();
  expect(branchesApi.context).toHaveBeenCalledExactlyOnceWith('git', 'release');
  expect(shell.find('.global-branch-switcher').exists()).toBe(true);
  shell.unmount();
});

it('automatically initializes the fixed workspace for ZIP projects too', async () => {
  select(project('zip', 'ZIP'));
  const shell = mountShell();
  await flushPromises();
  expect(branchesApi.context).toHaveBeenCalledExactlyOnceWith('zip', 'workspace');
  expect(shell.find('.global-branch-switcher').exists()).toBe(false);
  expect(navigation.replace).not.toHaveBeenCalled();
  shell.unmount();
});

it('clears context when no project is selected without requesting a branch', async () => {
  const shell = mountShell();
  await flushPromises();
  expect(branchesApi.list).not.toHaveBeenCalled();
  expect(branchesApi.context).not.toHaveBeenCalled();
  expect(useBranchContextStore().context).toBeNull();
  shell.unmount();
});

it('redirects old knowledge search links to management with keywords and fresh virtual branch coordinates', async () => {
  route.name = 'search';
  route.query = { q: '退款期限', path: 'old.ts', branchId: 'old', contextId: 'expired' };
  select(project('knowledge'));
  const shell = mountShell();
  await flushPromises();
  expect(navigation.replace).toHaveBeenLastCalledWith({ name: 'knowledge', query: {
    q: '退款期限', searchScope: 'PUBLISHED', branchId: 'workspace', contextId: 'context-knowledge',
  } });
  shell.unmount();
});
