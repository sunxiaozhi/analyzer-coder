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
    expect(wrapper.text()).toContain('退款审批规则');
    expect(wrapper.text()).toContain('可信知识');
    expect(wrapper.text()).toContain('不把关键词相似结果冒充适用规则');
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

function graphFixture(snapshotId = 'snapshot-1') {
  return {
    snapshotId, nodes: [
      { id: 'a', symbol: 'caller', filePath: 'a.ts', startLine: 1, endLine: 2 },
      { id: 'b', symbol: 'callee', filePath: 'b.ts', startLine: 4, endLine: 5 },
    ],
    edges: [{ id: 'edge', source: 'a', target: 'b', relation: 'CALLS' }],
    paths: [{ targetNodeId: 'b', nodeIds: ['a', 'b'], edgeIds: ['edge'], depth: 1 }],
    coverage: { complete: true, representedAffectedRecordCount: 1, affectedRecordCount: 1 },
    limitations: [], affectedNodeCount: 1, maxDepthReached: 1,
  };
}

function mountRelations() {
  api.codeEvidenceContext.mockResolvedValue({ knowledgeReferences: [], limitations: [] });
  api.latestGraph.mockResolvedValue({ snapshotId: 'snapshot-1', cliVersion: 'test', nodeCount: 2 });
  return mount(CodeEvidencePanel, {
    props: { repositoryId: 'repo-1', filePath: 'a.ts', initialSymbol: 'caller', snapshotId: 'snapshot-1', autoAnalyze: true },
    global: { stubs: { 'el-input': true, 'el-input-number': true, 'el-button': true } },
  });
}

describe('graph context integrity', () => {
  it('does not display a slow relation response after switching files', async () => {
    const old = deferred<ReturnType<typeof graphFixture>>();
    api.graph.mockReturnValueOnce(old.promise);
    const wrapper = mountRelations();
    await flushPromises();
    await wrapper.setProps({ filePath: 'c.ts', initialSymbol: null, autoAnalyze: false });
    await flushPromises();
    old.resolve(graphFixture());
    await flushPromises();
    expect(wrapper.find('.relation-summary').exists()).toBe(false);
    wrapper.unmount();
  });

  it('renders the real edge direction and describes mapping coverage accurately', async () => {
    api.graph.mockResolvedValue(graphFixture());
    const wrapper = mountRelations();
    await flushPromises();
    await wrapper.findAll('.context-tabs button')[0].trigger('click');
    expect(wrapper.find('.path-chain small').text()).toBe('→ CALLS');
    expect(wrapper.find('.coverage').text()).toContain('返回记录已完整映射');
    expect(wrapper.text()).not.toContain('路径覆盖完整');
    await wrapper.find('.path-chain button').trigger('click');
    expect(wrapper.emitted('openFile')?.[0]).toEqual(['a.ts', 1, 2]);
    wrapper.unmount();
  });

  it('rejects a response from a different snapshot', async () => {
    api.graph.mockResolvedValue(graphFixture('new-snapshot'));
    const wrapper = mountRelations();
    await flushPromises();
    await wrapper.findAll('.context-tabs button')[0].trigger('click');
    expect(wrapper.text()).toContain('代码快照已更新');
    expect(wrapper.find('.relation-summary').exists()).toBe(false);
    wrapper.unmount();
  });

  it('refreshes a completed graph query instead of clearing it without reloading', async () => {
    api.graph.mockResolvedValue(graphFixture());
    const wrapper = mountRelations();
    await flushPromises();
    await wrapper.findAll('.context-tabs button')[0].trigger('click');
    const calls = api.graph.mock.calls.length;
    await wrapper.find('[title="刷新文件证据和图谱状态"]').trigger('click');
    await flushPromises();
    expect(api.graph).toHaveBeenCalledTimes(calls + 1);
    expect(wrapper.find('.relation-summary').exists()).toBe(true);
    wrapper.unmount();
  });
});
