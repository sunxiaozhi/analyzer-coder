import { mount, flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import CodeAtlasView from './CodeAtlasView.vue';
import { getCodeAtlas, type AtlasView } from '@/api/codeAtlas';
import { getRepositoryFile } from '@/api/repositories';
import { useBranchContextStore } from '@/stores/branchContextStore';

const atlasRoute = reactive({ query: {} as Record<string, string> });
const store = reactive({ selectedRepositoryId: 'a', selectedRepository: { name: 'Project', sourceType: '' }, repositories: [{}] });
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => store }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => atlasRoute }));
vi.mock('@/api/codeAtlas', () => ({ getCodeAtlas: vi.fn() }));
vi.mock('@/api/repositories', () => ({ getRepositoryFile: vi.fn() }));
const view = (repo = 'a'): AtlasView => ({ repositoryId: repo, contentVersion: 'contentVersion', level: 'SYMBOL', nodes: [{ id: 'n', label: 'save', kind: 'method', filePath: 'src/a.ts', module: 'src', startLine: 1, endLine: 2, count: 1 }], edges: [], totalNodes: 1, totalEdges: 0, partial: false });
beforeEach(() => {
  vi.resetAllMocks(); atlasRoute.query = {}; setActivePinia(createPinia());
  store.selectedRepositoryId = 'a'; store.selectedRepository.sourceType = '';
  const branches = useBranchContextStore();
  branches.repositoryId = 'a';
  branches.selectedBranchId = 'main';
  branches.context = { contextId: 'ctx-main', repositoryId: 'a', branchId: 'main', branchName: 'main', contentVersion: 'contentVersion', commitSha: 'abc', expiresAt: '' };
});

it('never loads default graph data while a Git context is missing or expired', async () => {
  store.selectedRepository.sourceType = 'LOCAL_GIT';
  const branches = useBranchContextStore();
  branches.repositoryId = 'a';
  branches.selectedBranchId = 'release';
  branches.context = null;
  branches.error = 'CONTEXT_EXPIRED';
  const wrapper = mount(CodeAtlasView, { global: { stubs: { CodeAtlas3D: true } } });
  await flushPromises();
  expect(getCodeAtlas).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain('CONTEXT_EXPIRED');
  wrapper.unmount();
});

it('ignores an old branch response after switching to another pinned contentVersion', async () => {
  store.selectedRepository.sourceType = 'LOCAL_GIT';
  const branches = useBranchContextStore();
  branches.repositoryId = 'a';
  branches.selectedBranchId = 'main';
  branches.context = { contextId: 'ctx-main', repositoryId: 'a', branchId: 'main', branchName: 'main', contentVersion: 'contentVersion', commitSha: 'abc', expiresAt: '' };
  let resolve!: (value: AtlasView) => void;
  vi.mocked(getCodeAtlas).mockImplementationOnce(() => new Promise(done => { resolve = done; }))
    .mockResolvedValue({ ...view(), contentVersion: 'release-contentVersion', nodes: [{ ...view().nodes[0], label: 'release-symbol' }] });
  const wrapper = mount(CodeAtlasView, { global: { stubs: { CodeAtlas3D: true } } });
  await flushPromises();
  branches.selectedBranchId = 'release';
  branches.context = null;
  await flushPromises();
  branches.context = { contextId: 'ctx-release', repositoryId: 'a', branchId: 'release', branchName: 'release', contentVersion: 'release-contentVersion', commitSha: 'def', expiresAt: '' };
  await flushPromises();
  resolve({ ...view(), nodes: [{ ...view().nodes[0], label: 'old-main-symbol' }] });
  await flushPromises();
  await wrapper.get('[data-view-2d]').trigger('click');
  expect(wrapper.text()).toContain('release-symbol');
  expect(wrapper.text()).not.toContain('old-main-symbol');
  expect(getCodeAtlas).toHaveBeenLastCalledWith('a', '', '', 'ctx-release');
  wrapper.unmount();
});

it('keeps view controls and search in one toolbar, with context outside the canvas', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  await flushPromises();
  expect(wrapper.find('.atlas-toolbar .view-switch').exists()).toBe(true);
  expect(wrapper.find('.atlas-toolbar .atlas-search').exists()).toBe(true);
  expect(wrapper.find('.atlas-brand').exists()).toBe(false);
  expect(wrapper.find('.atlas-stage .atlas-caption').exists()).toBe(false);
  expect(wrapper.find('.file-type-legend').exists()).toBe(true);
  wrapper.unmount();
});

it('starts in 3D and falls back to planar interaction when WebGL fails', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView, { global: { stubs: {
    CodeAtlas3D: { template: '<button data-no-webgl @click="$emit(\'unavailable\', \'模拟上下文失败\')">No WebGL</button>' },
  } } });
  await flushPromises();
  expect(wrapper.find('[data-view-2d]').attributes('aria-pressed')).toBe('false');
  await wrapper.get('[data-no-webgl]').trigger('click');
  expect(wrapper.get('[role="status"]').text()).toContain('已切换到平面模式');
  expect(wrapper.get('.diagnostic-reason').text()).toContain('模拟上下文失败');
  expect(wrapper.findAll('[data-node]')).toHaveLength(1);
  wrapper.unmount();
});

it('stays in 3D when the renderer activates compatibility mode', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView, { global: { stubs: {
    CodeAtlas3D: { template: '<button data-compatible @click="$emit(\'degraded\', \'WebGL 创建失败\')">Compatible</button>' },
  } } });
  await flushPromises();
  await wrapper.get('[data-compatible]').trigger('click');
  expect(wrapper.find('[data-view-2d]').attributes('aria-pressed')).toBe('false');
  expect(wrapper.get('[role="status"]').text()).toContain('已启用兼容 3D');
  expect(wrapper.text()).toContain('兼容 3D');
  wrapper.unmount();
});

it('ignores a graph response from a previous repository', async () => {
  let resolve!: (value: AtlasView) => void;
  vi.mocked(getCodeAtlas).mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValue(view('b'));
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  const branches = useBranchContextStore();
  store.selectedRepositoryId = 'b';
  branches.repositoryId = 'b';
  branches.selectedBranchId = 'main-b';
  branches.context = { contextId: 'ctx-b', repositoryId: 'b', branchId: 'main-b', branchName: 'main', contentVersion: 'contentVersion', commitSha: 'def', expiresAt: '' };
  await flushPromises();
  resolve({ ...view(), nodes: [{ ...view().nodes[0], label: 'stale-symbol' }] });
  await flushPromises();
  expect(wrapper.text()).not.toContain('stale-symbol');
  expect(wrapper.findAll('[data-node]')).toHaveLength(1);
  wrapper.unmount();
});

it('rejects source content from a different contentVersion', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'new-contentVersion', content: 'wrong-source' } as Awaited<ReturnType<typeof getRepositoryFile>>);
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  await flushPromises();
  await wrapper.get('[data-node]').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('源码版本已更新');
  expect(wrapper.text()).not.toContain('wrong-source');
  wrapper.unmount();
});

it('expands a module using its exact scope', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue({ ...view(), level: 'MODULE', nodes: [{ ...view().nodes[0], kind: 'MODULE', filePath: '' }] });
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  await flushPromises();
  await wrapper.get('[data-node]').trigger('dblclick');
  await flushPromises();
  expect(getCodeAtlas).toHaveBeenLastCalledWith('a', 'src', '', 'ctx-main');
  wrapper.unmount();
});

it('keeps reading defaults quiet and shows local relations on selection', async () => {
  const graph = view();
  graph.nodes.push({ ...graph.nodes[0], id: 'other', label: 'caller' });
  graph.edges = [{ source: 'other', target: 'n', kind: 'CALL', count: 1 }];
  vi.mocked(getCodeAtlas).mockResolvedValue(graph);
  const wrapper = mount(CodeAtlasView, { global: { stubs: { CodeAtlas3D: true } } });
  await flushPromises();
  expect((wrapper.get('.atlas-toolbar input[type="checkbox"]').element as HTMLInputElement).checked).toBe(false);
  await wrapper.get('[data-view-2d]').trigger('click');
  expect(wrapper.findAll('.atlas-link')).toHaveLength(0);
  await wrapper.get('[data-node]').trigger('click');
  expect(wrapper.findAll('.atlas-link')).toHaveLength(1);
  expect(wrapper.get('.relation-legend').text()).toContain('入向');
  expect(wrapper.find('.atlas-stage .atlas-detail').exists()).toBe(false);
  wrapper.unmount();
});

it('uses a full-height sibling panel with wrapped source cells and collapsible relations', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const longLine = 'const path = "' + '长路径/'.repeat(80) + '";';
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: longLine + '\nreturn path;' } as Awaited<ReturnType<typeof getRepositoryFile>>);
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  await flushPromises();
  await wrapper.get('[data-node]').trigger('click');
  await flushPromises();
  expect(wrapper.classes()).toContain('has-detail');
  expect(wrapper.get('.atlas-detail').element.parentElement).toBe(wrapper.element);
  expect(wrapper.get('.relations').attributes('open')).toBeUndefined();
  expect(wrapper.get('.relations').classes()).not.toContain('relations-fill');
  expect(wrapper.get('.atlas-source code').text()).toBe(longLine);
  expect(wrapper.find('.render-status').exists()).toBe(false);
  await wrapper.get('[aria-label="关闭详情"]').trigger('click');
  expect(wrapper.find('.atlas-detail').exists()).toBe(false);
  wrapper.unmount();
});

it('lets module relations fill the unused source area', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue({ ...view(), level: 'MODULE', nodes: [{ ...view().nodes[0], kind: 'MODULE', filePath: '' }] });
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  await flushPromises();
  await wrapper.get('[data-node]').trigger('click');
  expect(wrapper.get('.relations').classes()).toContain('relations-fill');
  expect(wrapper.get('.relations').attributes('open')).toBeDefined();
  expect(wrapper.find('.source-section').exists()).toBe(false);
  wrapper.unmount();
});

it('locates the file and symbol passed from unified search', async () => {
  atlasRoute.query = { path: 'src/a.ts', symbol: 'save', contentVersion: 'contentVersion' };
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: 'save()' } as any);
  const wrapper = mount(CodeAtlasView, { global: { stubs: { CodeAtlas3D: true } } });
  await flushPromises();
  expect(getCodeAtlas).toHaveBeenCalledWith('a', '', 'save', 'ctx-main');
  expect(wrapper.get('[aria-label="节点详情"]').text()).toContain('src/a.ts');
  expect(wrapper.text()).toContain('分析此符号的影响范围');
  wrapper.unmount();
});

it('falls back to the file when the search-result symbol is absent from the graph', async () => {
  atlasRoute.query = { path: 'src/a.ts', symbol: 'unmappedSymbol', contentVersion: 'contentVersion' };
  vi.mocked(getCodeAtlas).mockResolvedValueOnce({ ...view(), nodes: [] }).mockResolvedValueOnce(view());
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: 'save()' } as any);
  const wrapper = mount(CodeAtlasView, { global: { stubs: { CodeAtlas3D: true } } });
  await flushPromises();
  expect(getCodeAtlas).toHaveBeenLastCalledWith('a', '', 'src/a.ts', 'ctx-main');
  expect(wrapper.get('[aria-label="节点详情"]').text()).toContain('src/a.ts');
  wrapper.unmount();
});

it('rejects a graph from a different contentVersion than the search-result link', async () => {
  atlasRoute.query = { path: 'src/a.ts', symbol: 'save', contentVersion: 'old-contentVersion' };
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView, { global: { stubs: { CodeAtlas3D: true } } });
  await flushPromises();
  expect(wrapper.text()).toContain('目标文件与当前分支内容版本不一致');
  expect(getRepositoryFile).not.toHaveBeenCalled();
  expect(wrapper.find('[aria-label="节点详情"]').exists()).toBe(false);
  wrapper.unmount();
});
