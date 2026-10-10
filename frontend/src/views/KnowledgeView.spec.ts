import { shallowMount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { reactive } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ApiError } from '@/api/http';
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock('element-plus', async importOriginal => ({ ...await importOriginal<typeof import('element-plus')>(),
  ElMessage: { success: vi.fn(), error: vi.fn() }, ElMessageBox: { confirm: vi.fn() },
}));
import KnowledgeView from './KnowledgeView.vue';
import KnowledgeCardDetailDialog from '@/features/knowledge/KnowledgeCardDetailDialog.vue';
import KnowledgeCardEditorDialog from '@/features/knowledge/KnowledgeCardEditorDialog.vue';
import KnowledgeCardListItem from '@/features/knowledge/KnowledgeCardListItem.vue';
import { intelligenceApi } from '@/api/intelligence';
import { branchesApi } from '@/api/branches';
import { useBranchContextStore } from '@/stores/branchContextStore';

let route: { name: string; query: Record<string, string> };
let repositories: {
  selectedRepositoryId: string;
  selectedRepository: { capabilities: { canUpdate: boolean; canConfigure: boolean } };
};
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => repositories }));
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => router,
}));
vi.mock('@/api/intelligence', () => ({
  intelligenceApi: { cards: vi.fn(), markdownSources: vi.fn(), sourceDrift: vi.fn(), updateCard: vi.fn(), createCard: vi.fn(), deleteCard: vi.fn() },
}));
vi.mock('@/api/branches', () => ({ branchesApi: { validations: vi.fn() } }));

describe('knowledge evidence access', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(ElMessageBox.confirm).mockResolvedValue(Object.assign('confirm', { value: '', action: 'confirm' as const }));
    setActivePinia(createPinia());
    route = reactive({ name: 'knowledge', query: { cardId: 'card-1' } });
    repositories = reactive({
      selectedRepositoryId: 'repo-1',
      selectedRepository: { capabilities: { canUpdate: false, canConfigure: false } },
    });
    vi.mocked(intelligenceApi.cards).mockResolvedValue([
      { id: 'card-1', title: 'Published rule', content: 'Rule body', publicationStatus: 'PUBLISHED', revision: 1, cardType: '规则', knowledgeKind: 'BUSINESS_RULE', tags: [] },
    ] as unknown as Awaited<ReturnType<typeof intelligenceApi.cards>>);
    vi.mocked(intelligenceApi.sourceDrift).mockResolvedValue(null);
    vi.mocked(branchesApi.validations).mockResolvedValue([]);
    vi.mocked(intelligenceApi.markdownSources).mockResolvedValue({
      contentVersion: 'contentVersion', counts: { total: 0, pending: 0, current: 0, stale: 0 }, items: [],
    });
    const branches = useBranchContextStore();
    branches.repositoryId = 'repo-1';
    branches.selectedBranchId = 'main';
    branches.context = { contextId: 'ctx-main', repositoryId: 'repo-1', branchId: 'main', branchName: 'main', contentVersion: 'contentVersion', commitSha: 'abc', expiresAt: '' };
  });

  function mountView() {
    return shallowMount(KnowledgeView, {
      global: {
        directives: { loading: () => {} },
        stubs: {
          ElInput: true, ElSelect: true, ElOption: true, ElButton: true, ElEmpty: true, ElAlert: true,
          ElDialog: true, ElTimeline: true, ElTimelineItem: true, ElCard: true,
        },
      },
    });
  }

  async function draftView() {
    repositories.selectedRepository.capabilities.canUpdate = true;
    repositories.selectedRepository.capabilities.canConfigure = true;
    const original = await intelligenceApi.cards('repo-1', 'ctx-main');
    vi.mocked(intelligenceApi.cards).mockResolvedValue(original.map(card => ({ ...card, publicationStatus: 'DRAFT' })));
    const wrapper = mountView(); await flushPromises(); return wrapper;
  }

  it('deletes the confirmed revision, closes its detail, and refreshes knowledge and Markdown sources', async () => {
    const wrapper = await draftView();
    const card = wrapper.findComponent(KnowledgeCardListItem).props('card');
    vi.mocked(intelligenceApi.cards).mockResolvedValue([]);
    vi.mocked(intelligenceApi.deleteCard).mockResolvedValue(undefined);
    vi.mocked(intelligenceApi.cards).mockClear();
    vi.mocked(intelligenceApi.markdownSources).mockClear();
    wrapper.findComponent(KnowledgeCardListItem).vm.$emit('delete', card);
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('原 Markdown 文件保留'), '删除知识草稿', expect.anything());
    expect(intelligenceApi.deleteCard).toHaveBeenCalledWith('repo-1', card.id, 1, 'ctx-main');
    expect(intelligenceApi.cards).toHaveBeenCalledWith('repo-1', 'ctx-main');
    expect(intelligenceApi.markdownSources).toHaveBeenCalledWith('repo-1', 'ctx-main');
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('modelValue')).toBe(false);
    expect(wrapper.findComponent(KnowledgeCardListItem).exists()).toBe(false);
    expect(router.replace).toHaveBeenCalledWith({ query: { cardId: undefined } });
    expect(ElMessage.success).toHaveBeenCalled(); wrapper.unmount();
  });

  it('leaves the draft untouched when deletion is canceled', async () => {
    const wrapper = await draftView();
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    wrapper.findComponent(KnowledgeCardListItem).vm.$emit('delete', wrapper.findComponent(KnowledgeCardListItem).props('card'));
    await flushPromises();
    expect(intelligenceApi.deleteCard).not.toHaveBeenCalled();
    expect(ElMessage.error).not.toHaveBeenCalled();
    expect(wrapper.findComponent(KnowledgeCardListItem).exists()).toBe(true); wrapper.unmount();
  });

  it('keeps the draft and displays the server error if its revision has changed', async () => {
    const wrapper = await draftView();
    vi.mocked(intelligenceApi.deleteCard).mockRejectedValueOnce(new ApiError(409, 'KNOWLEDGE_REVISION_CONFLICT', '知识已被修改，请刷新后重新确认删除'));
    wrapper.findComponent(KnowledgeCardListItem).vm.$emit('delete', wrapper.findComponent(KnowledgeCardListItem).props('card'));
    await flushPromises();
    expect(ElMessage.error).toHaveBeenCalledWith('知识已被修改，请刷新后重新确认删除');
    expect(wrapper.findComponent(KnowledgeCardListItem).exists()).toBe(true);
    expect(wrapper.findComponent(KnowledgeCardListItem).props('deleting')).toBe(false); wrapper.unmount();
  });

  it('never submits a confirmed deletion after switching project', async () => {
    const wrapper = await draftView();
    let confirm!: (value: any) => void;
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(new Promise(resolve => { confirm = resolve; }));
    wrapper.findComponent(KnowledgeCardListItem).vm.$emit('delete', wrapper.findComponent(KnowledgeCardListItem).props('card'));
    await flushPromises();
    route.query = {}; repositories.selectedRepositoryId = 'repo-2'; useBranchContextStore().clear();
    await flushPromises(); confirm('confirm'); await flushPromises();
    expect(intelligenceApi.deleteCard).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('ignores a deletion response after changing project and prevents duplicate submissions', async () => {
    const wrapper = await draftView();
    let finish!: () => void;
    vi.mocked(intelligenceApi.deleteCard).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    const item = wrapper.findComponent(KnowledgeCardListItem); const card = item.props('card');
    item.vm.$emit('delete', card); item.vm.$emit('delete', card); await flushPromises();
    expect(intelligenceApi.deleteCard).toHaveBeenCalledTimes(1);
    route.query = {}; repositories.selectedRepositoryId = 'repo-2'; useBranchContextStore().clear(); await flushPromises();
    finish(); await flushPromises();
    expect(ElMessage.success).not.toHaveBeenCalled();
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('card')).toBeNull(); wrapper.unmount();
  });

  it('does not submit deletion for published knowledge or without manage permission', async () => {
    const wrapper = mountView(); await flushPromises();
    const item = wrapper.findComponent(KnowledgeCardListItem);
    item.vm.$emit('delete', item.props('card')); await flushPromises();
    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(intelligenceApi.deleteCard).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('opens a published card and loads readable Markdown sources without maintenance tools', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(intelligenceApi.cards).toHaveBeenCalledWith('repo-1', 'ctx-main');
    expect(intelligenceApi.markdownSources).toHaveBeenCalledWith('repo-1', 'ctx-main');
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('modelValue')).toBe(true);
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('card')?.id).toBe('card-1');
    expect(wrapper.findComponent(KnowledgeCardListItem).props('canMaintain')).toBe(false);
    expect(wrapper.findComponent(KnowledgeCardEditorDialog).exists()).toBe(false);
    expect(wrapper.text()).toContain('Markdown 预备知识');
    wrapper.unmount();
  });

  it('keeps draft sources and the editor available to maintainers', async () => {
    repositories.selectedRepository.capabilities.canUpdate = true;
    const wrapper = mountView();
    await flushPromises();
    expect(intelligenceApi.markdownSources).toHaveBeenCalledWith('repo-1', 'ctx-main');
    expect(wrapper.findComponent(KnowledgeCardListItem).props('canMaintain')).toBe(true);
    expect(wrapper.findComponent(KnowledgeCardEditorDialog).exists()).toBe(true);
    wrapper.unmount();
  });
  it('does not apply an old drift response to the same card in another branch', async () => {
    let resolveDrift!: (value: any) => void;
    vi.mocked(intelligenceApi.sourceDrift).mockReturnValueOnce(new Promise(resolve => { resolveDrift = resolve; }));
    const wrapper = mountView();
    await flushPromises();
    const branches = useBranchContextStore();
    branches.selectedBranchId = 'legacy';
    branches.context = { ...branches.context!, contextId: 'ctx-legacy', branchId: 'legacy', branchName: 'legacy', contentVersion: 'legacy-version' };
    await flushPromises();
    resolveDrift({ id: 'old-main-event' });
    await flushPromises();
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('driftEvent')).toBeNull();
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('driftLoading')).toBe(false);
    wrapper.unmount();
  });

  it('ignores a completed save after the user switches project', async () => {
    repositories.selectedRepository.capabilities.canUpdate = true;
    let resolveSave!: (value: any) => void;
    vi.mocked(intelligenceApi.updateCard).mockReturnValueOnce(new Promise(resolve => { resolveSave = resolve; }));
    const wrapper = mountView();
    await flushPromises();
    const card = wrapper.findComponent(KnowledgeCardListItem).props('card');
    wrapper.findComponent(KnowledgeCardListItem).vm.$emit('edit', card);
    await flushPromises();
    wrapper.findComponent(KnowledgeCardEditorDialog).vm.$emit('submit', { title: 'Updated', content: 'New body' });
    await flushPromises();
    expect(wrapper.findComponent(KnowledgeCardEditorDialog).props('busy')).toBe(true);
    route.query = {};
    repositories.selectedRepositoryId = 'repo-2';
    useBranchContextStore().clear();
    await flushPromises();
    resolveSave({ ...card, id: 'old-saved-card', title: 'Updated' });
    await flushPromises();
    expect(wrapper.findComponent(KnowledgeCardEditorDialog).props('busy')).toBe(false);
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('card')).toBeNull();
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('modelValue')).toBe(false);
    wrapper.unmount();
  });

});

