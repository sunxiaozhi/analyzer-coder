import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ReviewResultOverview from './ReviewResultOverview.vue';
import type { TaskReviewResult } from '@/api/taskReviews';

describe('ReviewResultOverview', () => {
  it('deduplicates changed objects and explains the evidence boundary', () => {
    const baseSymbol = {
      symbolId: 'ts:src/order.ts:function:save', name: 'save', kind: 'FUNCTION', filePath: 'src/order.ts',
      declarationStartLine: 1, declarationEndLine: 20, changeType: 'MODIFIED' as const,
      oldStartLine: 2, newStartLine: 2, syntheticHunk: false, resolution: 'SOURCE_DECLARATION' as const, provenance: [],
    };
    const result = {
      reviewId: 'review-1', status: 'COMPLETED', repositoryId: 'repo-1', snapshotId: 'snapshot-1',
      createdBy: 'user-1', clientRequestId: 'request-1', modelConfigId: null, task: null,
      changeSource: 'WORKTREE', baseRef: 'HEAD', headRef: null,
      change: {
        source: 'WORKTREE', baseCommit: 'abc', headCommit: 'abc', worktreeDigest: 'digest', partial: false,
        changes: [{ type: 'MODIFIED', oldPath: 'src/order.ts', newPath: 'src/order.ts', binary: false, additions: 5, deletions: 2, hunks: [] }],
        limitations: [],
      },
      changedSymbols: [{ ...baseSymbol, hunkIndex: 0 }, { ...baseSymbol, hunkIndex: 1 }],
      applicableKnowledge: [], referenceCandidates: [], requiredTests: [], requiredApprovals: [], staleKnowledge: [], unknowns: [],
      summary: null, modelSummary: null, modelSummaryState: { status: 'NOT_REQUESTED', code: null, detail: null },
      error: null, createdAt: '2026-09-09T00:00:00Z', finishedAt: '2026-09-09T00:00:01Z',
    } satisfies TaskReviewResult;

    const wrapper = mount(ReviewResultOverview, { props: { result } });
    expect(wrapper.text()).toContain('1 个文件 · 1 个代码对象');
    expect(wrapper.text()).toContain('新增 5 行、删除 2 行');
    expect(wrapper.text()).toContain('“没有要求”不代表代码没有风险');
  });
});
