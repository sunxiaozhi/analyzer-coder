
import { reactive } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ChunksM0View from './ChunksM0View.vue';
import RepositoryFileTree from '@/components/RepositoryFileTree.vue';
import RepositoryFilePreview from '@/components/RepositoryFilePreview.vue';
import CodeEvidencePanel from '@/features/code/CodeEvidencePanel.vue';

const api = vi.hoisted(() => ({ files: vi.fn(), file: vi.fn(), search: vi.fn(), push: vi.fn() }));
let route: { query: Record<string, string> };
let store: { selectedRepositoryId: string; selectedRepository: { snapshotId: string; capabilities: object }; repositories: object[] };
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: api.push }) }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => store }));
vi.mock('@/api/repositories', () => ({ listRepositoryFiles: api.files, getRepositoryFile: api.file }));
vi.mock('@/api/intelligence', () => ({ intelligenceApi: { search: api.search } }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
function file(path: string) {
  return { snapshotId: 's1', path, content: path, lineCount: 1, language: 'typescript', name: path, sizeBytes: 10 };
}
function mountCode() {
  return shallowMount(ChunksM0View, { global: { stubs: { ElInput: true, ElButton: true, ElEmpty: true } } });
}
describe('code browsing continuity', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    route = reactive({ query: {} });
    store = reactive({ selectedRepositoryId: 'r1', selectedRepository: { snapshotId: 's1', capabilities: {} }, repositories: [{}] });
    api.files.mockResolvedValue({ snapshotId: 's1', files: [{ path: 'a.ts' }, { path: 'b.ts' }] });
    api.file.mockImplementation(async (_repository, path) => file(path));
  });

  it('keeps a cached file selected when an older network request finishes', async () => {
    const wrapper = mountCode();
    await flushPromises();
    const pending = deferred<ReturnType<typeof file>>();
    api.file.mockReturnValueOnce(pending.promise);
    wrapper.findComponent(RepositoryFileTree).vm.$emit('select', 'b.ts');
    await flushPromises();
    wrapper.findComponent(RepositoryFileTree).vm.$emit('select', 'a.ts');
    await flushPromises();
    pending.resolve(file('b.ts'));
    await flushPromises();
    expect(wrapper.findComponent(RepositoryFilePreview).props('file')!.path).toBe('a.ts');
    expect(wrapper.findComponent(RepositoryFilePreview).props('loading')).toBe(false);
    wrapper.unmount();
  });

  it('does not replace a missing linked file with an unrelated default file', async () => {
    route.query.path = 'deleted.ts';
    const wrapper = mountCode();
    await flushPromises();
    expect(api.file).not.toHaveBeenCalled();
    expect(wrapper.findComponent(RepositoryFilePreview).props('error')).toContain('找不到该文件');
    wrapper.unmount();
  });

  it('does not open current source for a historical citation', async () => {
    route.query = { path: 'a.ts', snapshotId: 'old' };
    const wrapper = mountCode();
    await flushPromises();
    expect(api.file).not.toHaveBeenCalled();
    expect(wrapper.findComponent(RepositoryFilePreview).props('error')).toContain('历史快照');
    wrapper.unmount();
  });

  it('discards old search results after switching repositories', async () => {
    const pending = deferred<object>();
    api.search.mockReturnValue(pending.promise);
    const wrapper = mountCode();
    await flushPromises();
    route.query.q = 'caller';
    await flushPromises();
    store.selectedRepositoryId = 'r2';
    store.selectedRepository.snapshotId = 's2';
    route.query = {};
    api.files.mockResolvedValue({ snapshotId: 's2', files: [] });
    await flushPromises();
    pending.resolve({ retrieval: { snapshotId: 's1' }, hits: [] });
    await flushPromises();
    expect(wrapper.find('.workbench-results').exists()).toBe(false);
    wrapper.unmount();
  });

  it('opens the specific immutable review from file evidence', async () => {
    route.query = { path: 'a.ts', symbol: 'caller', relation: '1' };
    const wrapper = mountCode();
    await flushPromises();
    wrapper.findComponent(CodeEvidencePanel).vm.$emit('openReview', 'review-42');
    expect(api.push).toHaveBeenCalledWith({ name: 'change-impact', query: { reviewId: 'review-42' } });
    wrapper.unmount();
  });
});
