import { mount, flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import CodeAtlasView from './CodeAtlasView.vue';
import { getCodeAtlas, type AtlasView } from '@/api/codeAtlas';
import { getRepositoryFile } from '@/api/repositories';

const store = reactive({ selectedRepositoryId: 'a', selectedRepository: { name: 'Project' }, repositories: [{}] });
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => store }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/api/codeAtlas', () => ({ getCodeAtlas: vi.fn() }));
vi.mock('@/api/repositories', () => ({ getRepositoryFile: vi.fn() }));
const view = (repo = 'a'): AtlasView => ({ repositoryId: repo, snapshotId: 'snapshot', level: 'SYMBOL', nodes: [{ id: 'n', label: 'save', kind: 'method', filePath: 'src/a.ts', module: 'src', startLine: 1, endLine: 2, count: 1 }], edges: [], totalNodes: 1, totalEdges: 0, partial: false });
beforeEach(() => { vi.resetAllMocks(); setActivePinia(createPinia()); store.selectedRepositoryId = 'a'; });

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
  store.selectedRepositoryId = 'b';
  await flushPromises();
  resolve({ ...view(), nodes: [{ ...view().nodes[0], label: 'stale-symbol' }] });
  await flushPromises();
  expect(wrapper.text()).not.toContain('stale-symbol');
  expect(wrapper.findAll('[data-node]')).toHaveLength(1);
  wrapper.unmount();
});

it('rejects source content from a different snapshot', async () => {
  vi.mocked(getCodeAtlas).mockResolvedValue(view());
  vi.mocked(getRepositoryFile).mockResolvedValue({ snapshotId: 'new-snapshot', content: 'wrong-source' } as Awaited<ReturnType<typeof getRepositoryFile>>);
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
  expect(getCodeAtlas).toHaveBeenLastCalledWith('a', 'src', '');
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
  vi.mocked(getRepositoryFile).mockResolvedValue({ snapshotId: 'snapshot', content: longLine + '\nreturn path;' } as Awaited<ReturnType<typeof getRepositoryFile>>);
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
