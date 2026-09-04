import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  codeEvidenceContext: vi.fn(),
  latestGraph: vi.fn(),
  graph: vi.fn(),
  buildGraph: vi.fn(),
}));

vi.mock('@/api/intelligence', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/intelligence')>();
  return { ...original, intelligenceApi: { ...original.intelligenceApi, ...api } };
});

import CodeEvidencePanel from './CodeEvidencePanel.vue';

describe('CodeEvidencePanel', () => {
  it('keeps direct knowledge and immutable review references separate from graph relations', async () => {
    api.codeEvidenceContext.mockResolvedValue({
      repositoryId: 'repo-1',
      snapshotId: 'snapshot-1',
      commitSha: 'abc',
      filePath: 'src/RefundService.java',
      symbol: 'approveRefund',
      knowledgeReferences: [{
        knowledgeId: 'knowledge-1',
        title: '退款审批规则',
        kind: 'BUSINESS_RULE',
        severity: 'CRITICAL',
        enforcement: 'REQUIRED',
        ownerAccountId: 'account-1',
        revision: 2,
        publicationStatus: 'PUBLISHED',
        reviewStatus: 'APPROVED',
        sourceVersionStatus: 'CURRENT',
        trusted: true,
        bindings: [{
          chunkId: 'chunk-1',
          snapshotId: 'snapshot-1',
          symbolName: 'approveRefund',
          startLine: 10,
          endLine: 20,
          contentHash: 'hash',
          stale: false,
          currentSnapshot: true,
        }],
      }],
      reviewReferences: [{
        reviewId: 'review-1',
        task: '调整退款审批',
        changeSource: 'WORKTREE',
        snapshotId: 'snapshot-1',
        currentSnapshot: true,
        roles: ['CHANGED_FILE', 'REQUIRED_TEST'],
        symbols: ['approveRefund'],
        createdAt: '2026-08-30T10:00:00Z',
        finishedAt: '2026-08-30T10:01:00Z',
      }],
      scannedReviewCount: 5,
      limitations: ['DIRECT_KNOWLEDGE_BINDINGS_ONLY'],
      generatedAt: '2026-08-30T10:02:00Z',
    });
    api.latestGraph.mockResolvedValue(null);

    const wrapper = mount(CodeEvidencePanel, {
      props: {
        repositoryId: 'repo-1',
        filePath: 'src/RefundService.java',
        initialSymbol: 'approveRefund',
        snapshotId: 'snapshot-1',
      },
      global: {
        stubs: {
          'el-input': true,
          'el-input-number': true,
          'el-button': true,
        },
      },
    });
    await flushPromises();

    expect(api.codeEvidenceContext)
      .toHaveBeenCalledWith('repo-1', 'src/RefundService.java', 'approveRefund');
    expect(wrapper.text()).toContain('当前快照没有已发布图谱');

    await wrapper.findAll('.context-tabs button')[1].trigger('click');
    expect(wrapper.text()).toContain('退款审批规则');
    expect(wrapper.text()).toContain('可信知识');
    expect(wrapper.text()).toContain('不把关键词相似结果冒充适用规则');

    await wrapper.findAll('.context-tabs button')[2].trigger('click');
    expect(wrapper.text()).toContain('调整退款审批');
    expect(wrapper.text()).toContain('变更文件');
    expect(wrapper.text()).toContain('要求测试');
  });
});
