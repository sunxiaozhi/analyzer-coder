import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import FeatureGuideView from './FeatureGuideView.vue';
import { getRepositoryProfile } from '@/api/repositories';

const mocks = vi.hoisted(() => ({ push: vi.fn(), profile: vi.fn() }));
let repositoryStore: {
  selectedRepositoryId: string | null;
  selectedRepository: null | {
    id: string;
    name: string;
    branch: string;
    commit: string;
    snapshotId: string | null;
    capabilities: { canUpdate: boolean };
  };
};
let authStore: { isAdmin: boolean };

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => repositoryStore }));
vi.mock('@/stores/authStore', () => ({ useAuthStore: () => authStore }));
vi.mock('@/api/repositories', () => ({ getRepositoryProfile: mocks.profile }));

function preparation(state = 'READY') {
  return {
    snapshotId: 'snapshot-1',
    commitSha: '1234567890abcdef',
    branch: 'main',
    dirty: false,
    generatedAt: '2026-09-09T00:00:00Z',
    repositoryId: 'repo-1',
    state,
    progress: 100,
    message: 'ready',
    stages: [],
    profile: {},
    activeJobId: null,
    activeJobType: null,
    activeJobStatus: null,
  };
}

function mountView() {
  return shallowMount(FeatureGuideView);
}

describe('feature guide', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    repositoryStore = reactive({
      selectedRepositoryId: 'repo-1',
      selectedRepository: {
        id: 'repo-1',
        name: 'analyzer-coder',
        branch: 'main',
        commit: '1234567890abcdef',
        snapshotId: 'snapshot-1',
        capabilities: { canUpdate: false },
      },
    });
    authStore = reactive({ isAdmin: false });
    mocks.profile.mockResolvedValue(preparation());
  });

  it('shows the six-step workflow with live repository status and data sources', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(getRepositoryProfile).toHaveBeenCalledWith('repo-1');
    expect(wrapper.findAll('.workflow-card')).toHaveLength(6);
    expect(wrapper.text()).toContain('证据已就绪');
    expect(wrapper.text()).toContain('code_chunks 与 CodeGraph');
    expect(wrapper.text()).toContain('只读查看');
    expect(wrapper.find('.administration').exists()).toBe(false);

    await wrapper.findAll('.workflow-action')[2].trigger('click');
    expect(mocks.push).toHaveBeenCalledWith('/search');
    wrapper.unmount();
  });

  it('shows system management cards only to administrators', async () => {
    authStore.isAdmin = true;
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.findAll('.administration-card')).toHaveLength(4);
    expect(wrapper.text()).toContain('索引任务');
    expect(wrapper.text()).toContain('账号权限');
    wrapper.unmount();
  });

  it('guides an account without a repository to project selection without reading profile data', async () => {
    repositoryStore.selectedRepositoryId = null;
    repositoryStore.selectedRepository = null;
    const wrapper = mountView();
    await flushPromises();

    expect(getRepositoryProfile).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('尚未选择项目');
    expect(wrapper.text()).toContain('需要选择项目');

    await wrapper.find('.context-action').trigger('click');
    expect(mocks.push).toHaveBeenCalledWith('/repositories');
    wrapper.unmount();
  });
});