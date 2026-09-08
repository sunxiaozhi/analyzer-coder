import { reactive } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getTaskReview, listTaskReviews, type TaskReviewResult } from '@/api/taskReviews';
import { intelligenceApi } from '@/api/intelligence';
import ChangeImpactView from './ChangeImpactView.vue';

let route: { query: { reviewId?: string } };
let store: { selectedRepositoryId: string; selectedRepository: object; repositories: object[] };
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => store }));
vi.mock('@/api/taskReviews', () => ({ getTaskReview: vi.fn(), listTaskReviews: vi.fn(), createTaskReview: vi.fn(), createPullRequestReview: vi.fn() }));
vi.mock('@/api/intelligence', () => ({ intelligenceApi: { askModels: vi.fn() } }));

describe('overview review links', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    route = reactive({ query: { reviewId: 'review-old' } });
    store = reactive({ selectedRepositoryId: 'repo-1', selectedRepository: { snapshotId: 'current', capabilities: {} }, repositories: [{}] });
    vi.mocked(listTaskReviews).mockResolvedValue([]);
    vi.mocked(intelligenceApi.askModels).mockResolvedValue([]);
    vi.mocked(getTaskReview).mockResolvedValue({ reviewId: 'review-old', status: 'FAILED', snapshotId: 'old-snapshot',
      change: null, error: { code: 'GIT_REF_NOT_FOUND', message: '目标提交不存在' }, finishedAt: null } as TaskReviewResult);
  });

  it('loads the requested record even when it is outside the recent history page', async () => {
    const wrapper = shallowMount(ChangeImpactView, { global: { stubs: { ElButton: true }, directives: { loading: () => {} } } });
    try {
      await flushPromises();
      expect(getTaskReview).toHaveBeenCalledWith('repo-1', 'review-old');
      expect(wrapper.text()).toContain('历史审查 · 只读');
      expect(wrapper.text()).toContain('目标提交不存在');
      expect(wrapper.text()).toContain('old-snap');
    } finally { wrapper.unmount(); }
  });
});

it('does not show Git review inputs for a ZIP repository', async () => {
  route = reactive({ query: {} });
  store = reactive({ selectedRepositoryId: 'zip', selectedRepository: {
    sourceType: 'ZIP', snapshotId: 'current', capabilities: {},
  }, repositories: [{}] });
  vi.mocked(listTaskReviews).mockResolvedValue([]);
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([]);
  const wrapper = shallowMount(ChangeImpactView, { global: { stubs: { ElButton: true }, directives: { loading: () => {} } } });
  await flushPromises();
  expect(wrapper.text()).toContain('ZIP 项目没有可审查的 Git 历史');
  expect(wrapper.findComponent({ name: 'TaskReviewForm' }).exists()).toBe(false);
  wrapper.unmount();
});

it('ignores a historical response after the user starts another review', async () => {
  route = reactive({ query: { reviewId: 'slow-history' } });
  store = reactive({ selectedRepositoryId: 'repo-1', selectedRepository: {
    sourceType: 'LOCAL_GIT', snapshotId: 'current', capabilities: {},
  }, repositories: [{}] });
  vi.mocked(listTaskReviews).mockResolvedValue([]);
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([]);
  let finishHistory!: (value: TaskReviewResult) => void;
  vi.mocked(getTaskReview).mockReturnValue(new Promise(done => { finishHistory = done; }));
  const wrapper = shallowMount(ChangeImpactView, { global: { stubs: { ElButton: true }, directives: { loading: () => {} } } });
  await flushPromises();
  const { createTaskReview } = await import('@/api/taskReviews');
  vi.mocked(createTaskReview).mockResolvedValue({
    reviewId: 'new-review', snapshotId: 'current', status: 'FAILED',
    error: { code: 'NEW', message: '本次审查记录' },
  } as TaskReviewResult);
  wrapper.findComponent({ name: 'TaskReviewForm' }).vm.$emit('submit', {
    task: 'new', changeSource: 'WORKTREE', baseRef: 'HEAD', headRef: null, modelConfigId: null,
  });
  await flushPromises();
  finishHistory({ reviewId: 'slow-history', snapshotId: 'old', status: 'FAILED',
    error: { code: 'OLD', message: '不应覆盖的新旧混淆' },
  } as TaskReviewResult);
  await flushPromises();
  expect(wrapper.text()).toContain('本次审查记录');
  expect(wrapper.text()).not.toContain('不应覆盖的新旧混淆');
  wrapper.unmount();
});
