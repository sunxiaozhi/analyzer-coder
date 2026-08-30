import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { TaskReviewResult } from '@/api/taskReviews';
import ChangeEvidenceSpine from './ChangeEvidenceSpine.vue';

const result = {
  reviewId: 'review-1',
  status: 'COMPLETED',
  repositoryId: 'repo-1',
  snapshotId: 'snapshot-1',
  createdBy: 'account-1',
  clientRequestId: 'request-1',
  modelConfigId: null,
  task: '修改退款规则',
  changeSource: 'WORKTREE',
  baseRef: null,
  headRef: null,
  change: {
    source: 'WORKTREE',
    baseCommit: 'abc',
    headCommit: 'abc',
    worktreeDigest: 'digest',
    partial: false,
    changes: [{
      type: 'MODIFIED', oldPath: 'src/refund.ts', newPath: 'src/refund.ts', binary: false,
      additions: 3, deletions: 1, hunks: [{ oldStart: 12, oldCount: 2, newStart: 12, newCount: 4 }],
    }],
    limitations: [],
  },
  changedSymbols: [{
    symbolId: 'ts:src/refund.ts:function:approveRefund',
    name: 'approveRefund',
    kind: 'FUNCTION',
    filePath: 'src/refund.ts',
    declarationStartLine: 10,
    declarationEndLine: 28,
    changeType: 'MODIFIED',
    oldStartLine: 12,
    newStartLine: 12,
    hunkIndex: 0,
    syntheticHunk: false,
    resolution: 'SOURCE_DECLARATION',
    provenance: [{
      sourceType: 'SOURCE_TEXT', repositoryId: 'repo-1', snapshotId: 'snapshot-1', commitSha: 'abc',
      worktreeDigest: 'digest', filePath: 'src/refund.ts', startLine: 10, endLine: 28, side: 'NEW',
      detail: '源码声明与 Hunk 相交',
    }],
  }],
  applicableKnowledge: [],
  referenceCandidates: [],
  requiredTests: [],
  requiredApprovals: [],
  staleKnowledge: [],
  unknowns: [],
  summary: '完成',
  modelSummary: null,
  modelSummaryState: { status: 'NOT_REQUESTED', code: null, detail: null },
  error: null,
  createdAt: '2026-08-30T08:00:00Z',
  finishedAt: '2026-08-30T08:00:01Z',
} satisfies TaskReviewResult;

describe('ChangeEvidenceSpine', () => {
  it('shows every evidence stage without probability-like scores', async () => {
    const wrapper = mount(ChangeEvidenceSpine, { props: { result } });
    const text = wrapper.text();

    expect(text).toContain('真实改动');
    expect(text).toContain('适用知识');
    expect(text).toContain('测试与审批');
    expect(text).toContain('知识失效');
    expect(text).toContain('未知项');
    expect(text).not.toContain('%');

    await wrapper.get('.change-list button').trigger('click');
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({
      kind: 'CHANGE',
      title: 'approveRefund',
      filePath: 'src/refund.ts',
    });
  });

  it('labels model output as suggestion and exposes only server-expanded evidence', async () => {
    const modelResult: TaskReviewResult = {
      ...result,
      modelConfigId: 'model-1',
      modelSummaryState: { status: 'COMPLETED', code: null, detail: null },
      modelSummary: {
        summary: '这是一段引用总结。',
        provider: 'fixture/model',
        sourceType: 'MODEL_SUGGESTION',
        generatedAt: '2026-08-30T08:00:01Z',
        unknowns: [],
        findings: [{
          text: '核对退款函数的真实改动。',
          evidenceIds: ['evidence-1'],
          evidence: [{
            id: 'evidence-1', kind: 'CHANGED_SYMBOL', title: 'approveRefund', detail: 'MODIFIED',
            filePath: 'src/refund.ts', startLine: 10, endLine: 28, knowledgeId: null,
          }],
          sources: [{
            id: 'source-1', sourceType: 'MODEL_SUGGESTION', repositoryId: 'repo-1',
            snapshotId: null, commitSha: null, worktreeDigest: null, filePath: null,
            symbolName: null, symbolKind: null, startLine: null, endLine: null, contentHash: null,
            knowledgeCardId: null, knowledgeRevision: null, knowledgeReviewStatus: null,
            graphArtifactId: null, relationPath: [], retrievalChannel: null,
            findingId: 'evidence-1', detail: '模型建议引用既有审查证据',
          }],
        }],
      },
    };
    const wrapper = mount(ChangeEvidenceSpine, { props: { result: modelResult } });

    expect(wrapper.text()).toContain('MODEL_SUGGESTION');
    expect(wrapper.text()).toContain('这是一段引用总结');
    await wrapper.get('.model-findings button').trigger('click');
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({
      kind: 'MODEL',
      filePath: 'src/refund.ts',
      startLine: 10,
    });
  });
});
