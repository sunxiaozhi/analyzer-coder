import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

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

const push = vi.hoisted(() => vi.fn());
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/stores/branchContextStore', () => ({ useBranchContextStore: () => ({ context: { branchId: 'release' } }) }));
beforeEach(() => vi.clearAllMocks());
import CodeEvidencePanel from './CodeEvidencePanel.vue';

describe('CodeEvidencePanel', () => {
  it('shows directly bound knowledge with its trust and applicability evidence', async () => {
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
    expect(wrapper.text()).not.toContain('代码关系');
    expect(api.latestGraph).not.toHaveBeenCalled();
    expect(api.graph).not.toHaveBeenCalled();
    await wrapper.get('.context-actions button').trigger('click');
    expect(push).toHaveBeenCalledWith({ name: 'atlas', query: { path: 'src/RefundService.java', symbol: 'approveRefund', snapshotId: 'snapshot-1', contextId: undefined, branchId: 'release' } });
    expect(wrapper.text()).toContain('退款审批规则');
    expect(wrapper.text()).toContain('可信知识');
    expect(wrapper.text()).toContain('不把关键词相似结果冒充适用规则');
  });
});
