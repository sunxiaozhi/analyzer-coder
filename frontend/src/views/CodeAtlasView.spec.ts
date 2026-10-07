import { mount, flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import { ElSelect } from 'element-plus';
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
vi.mock('@/api/codeAtlas', async importOriginal => ({ ...await importOriginal<typeof import('@/api/codeAtlas')>(), getCodeAtlas: vi.fn() }));
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
  expect(wrapper.get('.render-panel').text()).toContain('CodeGraph 符号');
  wrapper.unmount();
});

it('starts with dependency cards and recovers when optional 3D fails', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView, { global: { stubs: {
    CodeAtlas3D: { template: '<button data-no-webgl @click="$emit(\'unavailable\', \'模拟上下文失败\')">No WebGL</button>' },
  } } });
  await flushPromises();
  expect(wrapper.find('[data-view-2d]').attributes('aria-pressed')).toBe('true');
  expect(wrapper.findAll('.card-surface')).toHaveLength(1);
  await wrapper.get('[data-view-3d]').trigger('click');
  await flushPromises();
  expect(wrapper.find('[data-view-2d]').attributes('aria-pressed')).toBe('false');
  await wrapper.get('[data-no-webgl]').trigger('click');
  expect(wrapper.get('[role="status"]').text()).toContain('已切换到依赖画布');
  expect(wrapper.get('.diagnostic-reason').text()).toContain('模拟上下文失败');
  expect(wrapper.findAll('[data-node]')).toHaveLength(1);
  wrapper.unmount();
});

it('returns to dependency cards when optional WebGL needs compatibility', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView, { global: { stubs: {
    CodeAtlas3D: { template: '<button data-compatible @click="$emit(\'degraded\', \'WebGL 创建失败\')">Compatible</button>' },
  } } });
  await flushPromises();
  await wrapper.get('[data-view-3d]').trigger('click');
  await flushPromises();
  await wrapper.get('[data-compatible]').trigger('click');
  expect(wrapper.find('[data-view-2d]').attributes('aria-pressed')).toBe('true');
  expect(wrapper.get('[role="status"]').text()).toContain('已切换到依赖画布');
  expect(wrapper.text()).toContain('WebGL 不可用');
  wrapper.unmount();
});

it('keeps the manually chosen compatibility 3D mode available', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView, { global: { stubs: {
    CodeAtlas3D: { template: '<button data-compatible @click="$emit(\'degraded\', \'已选择兼容渲染\')">Compatible</button>' },
  } } });
  await flushPromises();
  await wrapper.get('[data-render-compatible]').trigger('click');
  await wrapper.get('[data-compatible]').trigger('click');
  expect(wrapper.find('[data-view-2d]').attributes('aria-pressed')).toBe('false');
  expect(wrapper.get('[role="status"]').text()).toContain('已启用兼容 3D');
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
  await wrapper.get('.source-action').trigger('click');
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

it('shows real relations immediately and exposes neighbor actions on selection', async () => {
  const graph = view();
  graph.nodes.push({ ...graph.nodes[0], id: 'other', label: 'caller' });
  graph.edges = [{ source: 'other', target: 'n', kind: 'CALL', count: 1 }];
  vi.mocked(getCodeAtlas).mockResolvedValue(graph);
  const wrapper = mount(CodeAtlasView, { global: { stubs: { CodeAtlas3D: true } } });
  await flushPromises();
  expect(wrapper.find('[data-view-2d]').attributes('aria-pressed')).toBe('true');
  await wrapper.get('[data-view-2d]').trigger('click');
  expect(wrapper.findAll('.atlas-link')).toHaveLength(1);
  await wrapper.get('[data-node]').trigger('click');
  expect(wrapper.findAll('.atlas-link')).toHaveLength(1);
  expect(wrapper.get('.selection-actions').text()).toContain('入向');
  expect(wrapper.find('.atlas-stage .atlas-detail').exists()).toBe(false);
  wrapper.unmount();
});

it('opens source beside the canvas and keeps long source lines readable', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const longLine = 'const path = "' + '长路径/'.repeat(80) + '";';
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: longLine + '\nreturn path;' } as Awaited<ReturnType<typeof getRepositoryFile>>);
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  await flushPromises();
  await wrapper.get('[data-node]').trigger('click');
  await wrapper.get('.source-action').trigger('click');
  await flushPromises();
  expect(wrapper.find('.atlas-detail').exists()).toBe(false);
  expect(wrapper.get('.atlas-drawer').element.parentElement).toBe(wrapper.get('.atlas-workbench').element);
  expect(wrapper.get('.atlas-source code').text()).toBe(longLine);
  expect(wrapper.find('.render-status').exists()).toBe(false);
  await wrapper.get('[aria-label="关闭抽屉"]').trigger('click');
  expect(wrapper.find('.atlas-drawer').exists()).toBe(false);
  expect(wrapper.find('.selection-bar').exists()).toBe(true);
  wrapper.unmount();
});

it('does not fetch source until the source action is requested', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click');
  await flushPromises();
  await wrapper.get('[data-node]').trigger('click');
  expect(getRepositoryFile).not.toHaveBeenCalled();
  expect(wrapper.find('.atlas-drawer').exists()).toBe(false);
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
  expect(wrapper.text()).toContain('影响路径');
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


it('expands selected nodes by direction while preserving current context and nodes', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValueOnce(view()).mockResolvedValueOnce({ ...view(), nodes: [view().nodes[0], { ...view().nodes[0], id: 'caller', label: 'externalCaller', module: 'backend' }], edges: [{ source: 'caller', target: 'n', kind: 'calls', count: 2, sourceLines: [8, 12] }] });
  const wrapper = mount(CodeAtlasView);
  await wrapper.get('[data-view-2d]').trigger('click'); await flushPromises();
  await wrapper.get('[data-node]').trigger('click');
  await wrapper.findAll('.selection-actions button').find(b => b.text().includes('入向'))!.trigger('click');
  await flushPromises();
  expect(getCodeAtlas).toHaveBeenLastCalledWith('a', '', '', 'ctx-main', { focusId: 'n', direction: 'in', depth: 1, limit: 240 });
  expect(wrapper.findAll('[data-node]')).toHaveLength(2);
  expect(wrapper.get('.selection-symbol').text()).toContain('save');
  wrapper.unmount();
});

it('opens the source occurrence when a relation is clicked', async () => {
  const graph = view(); graph.nodes.push({ ...graph.nodes[0], id: 'callee', label: 'helper' });
  graph.edges = [{ source: 'n', target: 'callee', kind: 'calls', count: 1, sourceLines: [8] }];
  vi.mocked(getCodeAtlas).mockResolvedValue(graph);
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: Array.from({length: 20}, (_, i) => 'line ' + (i + 1)).join('\n') } as any);
  const wrapper = mount(CodeAtlasView); await wrapper.get('[data-view-2d]').trigger('click'); await flushPromises();
  await wrapper.get('[data-edge]').trigger('click'); await flushPromises();
  expect(wrapper.get('.atlas-source .marked').text()).toContain('line 8');
  expect(wrapper.get('.drawer-path').text()).toContain(':8');
  wrapper.unmount();
});

it('filters relations and restores all relations through the shared selector', async () => {
  const graph = view();
  graph.nodes.push({ ...graph.nodes[0], id: 'other', label: 'caller' });
  graph.edges = [
    { source: 'other', target: 'n', kind: 'CALL', count: 1 },
    { source: 'n', target: 'other', kind: 'IMPORT', count: 1 },
  ];
  vi.mocked(getCodeAtlas).mockResolvedValue(graph);
  const wrapper = mount(CodeAtlasView);
  await flushPromises();
  expect(wrapper.get('.relation-select').text()).toContain('全部关系');
  expect(wrapper.findAll('.atlas-link')).toHaveLength(2);
  const relation = wrapper.findAllComponents(ElSelect)[0];
  relation.vm.$emit('update:modelValue', 'CALL');
  await flushPromises();
  expect(wrapper.findAll('.atlas-link')).toHaveLength(1);
  relation.vm.$emit('update:modelValue', '');
  await flushPromises();
  expect(wrapper.findAll('.atlas-link')).toHaveLength(2);
  expect(wrapper.get('.relation-select').text()).toContain('全部关系');
  wrapper.unmount();
});

it('passes the selected numeric depth to graph expansion', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  const wrapper = mount(CodeAtlasView);
  await flushPromises();
  wrapper.findAllComponents(ElSelect)[1].vm.$emit('update:modelValue', 3);
  await wrapper.get('[data-node]').trigger('click');
  await wrapper.findAll('.selection-actions button').find(button => button.text().includes('出向'))!.trigger('click');
  await flushPromises();
  expect(getCodeAtlas).toHaveBeenLastCalledWith('a', '', '', 'ctx-main', {
    focusId: 'n', direction: 'out', depth: 3, limit: 240,
  });
  wrapper.unmount();
});

function fileGraph(): AtlasView {
  const graph = view();
  graph.nodes.push(
    { ...graph.nodes[0], id: 'helper', label: 'helper', startLine: 20, endLine: 25 },
    { ...graph.nodes[0], id: 'caller', label: 'caller', filePath: 'api/controller.ts', module: 'api' },
    { ...graph.nodes[0], id: 'unrelated', label: 'unrelated', filePath: 'other/job.ts', module: 'other' },
  );
  graph.edges = [{ source: 'caller', target: 'n', kind: 'calls', count: 1, sourceLines: [8] }];
  return graph;
}

it('starts with file cards, drills into a file and keeps only its symbols and direct neighbors', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(fileGraph());
  const wrapper = mount(CodeAtlasView); await flushPromises();
  expect(wrapper.findAll('[data-card-kind="FILE"]')).toHaveLength(3);
  await wrapper.get('[aria-label="搜索符号或文件"]').setValue('draft search');
  expect(wrapper.findAll('[data-card-kind="FILE"]')).toHaveLength(3);
  await wrapper.get('[aria-label="搜索符号或文件"]').setValue('');
  await wrapper.findAll('[data-node]').find(n => n.attributes('aria-label')?.includes('src/a.ts'))!.trigger('click');
  expect(wrapper.findAll('[data-node]')).toHaveLength(3);
  expect(wrapper.get('.atlas-stage').text()).not.toContain('unrelated');
  expect(wrapper.get('[aria-label="图谱路径"]').text()).toContain('a.ts');
  expect(getCodeAtlas).toHaveBeenCalledTimes(1);
  await wrapper.get('[aria-label="退出文件范围"]').trigger('click');
  expect(wrapper.findAll('[data-card-kind="FILE"]')).toHaveLength(3);
  expect(wrapper.find('.scope-hint').exists()).toBe(false);
  wrapper.unmount();
});

it('keeps the file scope and source panel when expanding the selected symbol', async () => {
  const graph = fileGraph();
  const expanded = { ...graph, nodes: [...graph.nodes, { ...graph.nodes[0], id: 'neighbor', label: 'newNeighbor', filePath: 'new/n.ts' }],
    edges: [...graph.edges, { source: 'n', target: 'neighbor', kind: 'calls', count: 1 }] };
  vi.mocked(getCodeAtlas).mockResolvedValueOnce(graph).mockResolvedValue(expanded);
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: 'save()' } as any);
  const wrapper = mount(CodeAtlasView); await flushPromises();
  await wrapper.findAll('.tree-file').find(n => n.attributes('title') === 'src/a.ts')!.trigger('click');
  await wrapper.findAll('[data-node]').find(n => n.attributes('aria-label')?.startsWith('save ·'))!.trigger('click');
  await wrapper.get('.source-action').trigger('click'); await flushPromises();
  await wrapper.findAll('.selection-actions button').find(b => b.text().includes('出向'))!.trigger('click'); await flushPromises();
  expect(wrapper.get('[aria-label="图谱路径"]').text()).toContain('a.ts');
  expect(wrapper.find('.atlas-drawer').exists()).toBe(true);
  expect(wrapper.get('.atlas-stage').text()).toContain('newNeighbor');
  expect(wrapper.get('.atlas-stage').text()).not.toContain('unrelated');
  wrapper.unmount();
});

it('follows symbol selection in the open inspector without fetching the same file again', async () => {
  const graph = view(); graph.nodes.push({ ...graph.nodes[0], id: 'helper', label: 'helper', startLine: 20, endLine: 22 });
  vi.mocked(getCodeAtlas).mockResolvedValue(graph);
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: Array.from({ length: 30 }, (_, i) => 'line ' + (i + 1)).join('\n') } as any);
  const wrapper = mount(CodeAtlasView); await flushPromises();
  await wrapper.findAll('[data-node]').find(n => n.attributes('aria-label')?.startsWith('save ·'))!.trigger('click');
  await wrapper.get('.source-action').trigger('click'); await flushPromises();
  await wrapper.findAll('[data-node]').find(n => n.attributes('aria-label')?.startsWith('helper ·'))!.trigger('click'); await flushPromises();
  expect(wrapper.get('.inspector-symbol').text()).toContain('helper');
  expect(wrapper.get('.atlas-source .marked').text()).toContain('line 20');
  expect(getRepositoryFile).toHaveBeenCalledTimes(1);
  wrapper.unmount();
});

it('uses original source evidence when a projected file relationship is opened', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(fileGraph());
  vi.mocked(getRepositoryFile).mockResolvedValue({ contentVersion: 'contentVersion', content: Array.from({ length: 20 }, (_, i) => 'line ' + (i + 1)).join('\n') } as any);
  const wrapper = mount(CodeAtlasView); await flushPromises();
  await wrapper.get('[data-edge]').trigger('click'); await flushPromises();
  expect(getRepositoryFile).toHaveBeenLastCalledWith('a', 'api/controller.ts', 'ctx-main');
  expect(wrapper.get('.atlas-source .marked').text()).toContain('line 8');
  expect(wrapper.findAll('[data-card-kind="FILE"]')).toHaveLength(3);
  wrapper.unmount();
});

it('opens module scope through the navigation tree with the pinned branch context', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(fileGraph());
  const wrapper = mount(CodeAtlasView); await flushPromises();
  await wrapper.findAll('.tree-module').find(n => n.get('summary').text().includes('api'))!.get('.module-overview').trigger('click');
  await flushPromises();
  expect(getCodeAtlas).toHaveBeenLastCalledWith('a', 'api', '', 'ctx-main');
  expect(wrapper.get('[aria-label="图谱路径"]').text()).toContain('api');
  wrapper.unmount();
});
