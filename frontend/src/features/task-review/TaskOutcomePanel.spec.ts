import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import TaskOutcomePanel from './TaskOutcomePanel.vue';
import { listTaskOutcomes, reportTaskOutcome, type TaskReviewResult } from '@/api/taskReviews';

vi.mock('@/api/taskReviews', async importOriginal => {
  const actual = await importOriginal<typeof import('@/api/taskReviews')>();
  return { ...actual, listTaskOutcomes: vi.fn(), reportTaskOutcome: vi.fn() };
});

const review = {
  reviewId: 'review-1', status: 'COMPLETED', repositoryId: 'repo-1', snapshotId: 'snapshot-1',
  createdBy: 'account-1', clientRequestId: 'request-1', modelConfigId: null,
  task: '修改退款规则', changeSource: 'COMMIT_RANGE', baseRef: 'base', headRef: 'head',
  change: {
    source: 'COMMIT_RANGE', baseCommit: '0'.repeat(40), headCommit: 'a'.repeat(40),
    worktreeDigest: null, partial: false, changes: [{
      type: 'MODIFIED', oldPath: 'src/refund.ts', newPath: 'src/refund.ts', binary: false,
      additions: 2, deletions: 1, hunks: [],
    }], limitations: [],
  },
  changedSymbols: [], applicableKnowledge: [], referenceCandidates: [],
  requiredTests: [{
    kind: 'REQUIRED_TEST', key: 'npm test', title: 'npm test', status: 'REQUIRED_NOT_REPORTED',
    knowledgeIds: [], evidence: [], unknownReason: null, sources: [],
  }],
  requiredApprovals: [], staleKnowledge: [], unknowns: [], summary: '审查完成',
  modelSummary: null, modelSummaryState: { status: 'NOT_REQUESTED', code: null, detail: null },
  error: null, createdAt: '2026-08-31T08:00:00Z', finishedAt: '2026-08-31T08:00:01Z',
} satisfies TaskReviewResult;

describe('TaskOutcomePanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows immutable reported facts without claiming that feedback changed knowledge', async () => {
    vi.mocked(listTaskOutcomes).mockResolvedValue([{
      id: 'outcome-1', repositoryId: 'repo-1', reviewId: 'review-1', reportedBy: 'account-1',
      reporterDisplayName: '开发者', clientRequestId: 'outcome-request-1', finalCommit: 'a'.repeat(40),
      commitBinding: 'EXACT_REVIEW_HEAD', summary: '实际实现已完成', tests: [{
        key: 'npm test', status: 'PASSED', evidenceUrl: null,
      }], approvals: [], feedback: [], coverage: {
        requiredTests: ['npm test'], reportedRequiredTests: ['npm test'], missingRequiredTests: [],
        requiredApprovals: [], reportedRequiredApprovals: [], missingRequiredApprovals: [],
      }, createdAt: '2026-08-31T09:00:00Z',
    }]);
    const wrapper = mount(TaskOutcomePanel, {
      props: { repositoryId: 'repo-1', review },
      global: {
        directives: { loading: () => undefined },
        stubs: {
          'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' },
          'el-input': { props: ['modelValue'], template: '<input />' },
          'el-select': { props: ['modelValue'], template: '<select><slot/></select>' },
          'el-option': { template: '<option />' },
          'el-tag': { template: '<span><slot/></span>' },
        },
      },
    });
    await flushPromises();

    expect(listTaskOutcomes).toHaveBeenCalledWith('repo-1', 'review-1');
    expect(wrapper.text()).toContain('实际实现已完成');
    expect(wrapper.text()).toContain('精确绑定审查 Head');
    expect(wrapper.text()).toContain('不会自动修改正式知识');
    expect(reportTaskOutcome).not.toHaveBeenCalled();
  });
});
