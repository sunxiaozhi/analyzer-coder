import { shallowMount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import KnowledgeView from './KnowledgeView.vue';
import KnowledgeCardDetailDialog from '@/features/knowledge/KnowledgeCardDetailDialog.vue';
import KnowledgeCardEditorDialog from '@/features/knowledge/KnowledgeCardEditorDialog.vue';
import KnowledgeCardListItem from '@/features/knowledge/KnowledgeCardListItem.vue';
import { intelligenceApi } from '@/api/intelligence';

let repositories: {
  selectedRepositoryId: string;
  selectedRepository: { capabilities: { canUpdate: boolean; canConfigure: boolean } };
};
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => repositories }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { cardId: 'card-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/api/intelligence', () => ({
  intelligenceApi: { cards: vi.fn(), markdownSources: vi.fn(), sourceDrift: vi.fn() },
}));

describe('knowledge evidence access', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    repositories = reactive({
      selectedRepositoryId: 'repo-1',
      selectedRepository: { capabilities: { canUpdate: false, canConfigure: false } },
    });
    vi.mocked(intelligenceApi.cards).mockResolvedValue([
      { id: 'card-1', title: 'Published rule', cardType: '规则', tags: [] },
    ] as unknown as Awaited<ReturnType<typeof intelligenceApi.cards>>);
    vi.mocked(intelligenceApi.sourceDrift).mockResolvedValue(null);
    vi.mocked(intelligenceApi.markdownSources).mockResolvedValue({
      snapshotId: 'snapshot', counts: { total: 0, pending: 0, current: 0, stale: 0 }, items: [],
    });
  });

  function mountView() {
    return shallowMount(KnowledgeView, {
      global: {
        directives: { loading: () => {} },
        stubs: {
          ElInput: true, ElSelect: true, ElOption: true, ElButton: true, ElEmpty: true,
          ElDialog: true, ElTimeline: true, ElTimelineItem: true, ElCard: true,
        },
      },
    });
  }

  it('opens a published card from an evidence link without loading draft tools', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(intelligenceApi.cards).toHaveBeenCalledWith('repo-1');
    expect(intelligenceApi.markdownSources).not.toHaveBeenCalled();
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('modelValue')).toBe(true);
    expect(wrapper.findComponent(KnowledgeCardDetailDialog).props('card')?.id).toBe('card-1');
    expect(wrapper.findComponent(KnowledgeCardListItem).props('canMaintain')).toBe(false);
    expect(wrapper.findComponent(KnowledgeCardEditorDialog).exists()).toBe(false);
    expect(wrapper.text()).not.toContain('Markdown 预备知识');
    wrapper.unmount();
  });

  it('keeps draft sources and the editor available to maintainers', async () => {
    repositories.selectedRepository.capabilities.canUpdate = true;
    const wrapper = mountView();
    await flushPromises();
    expect(intelligenceApi.markdownSources).toHaveBeenCalledWith('repo-1');
    expect(wrapper.findComponent(KnowledgeCardListItem).props('canMaintain')).toBe(true);
    expect(wrapper.findComponent(KnowledgeCardEditorDialog).exists()).toBe(true);
    wrapper.unmount();
  });
});

