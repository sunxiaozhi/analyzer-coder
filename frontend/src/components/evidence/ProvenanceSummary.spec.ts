import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { Provenance } from '@/types/evidence';
import ProvenanceSummary from './ProvenanceSummary.vue';

function source(overrides: Partial<Provenance>): Provenance {
  return {
    id: 'source-1',
    sourceType: 'CODE_FACT',
    repositoryId: 'repository-1',
    snapshotId: 'snapshot-123456',
    commitSha: 'a'.repeat(40),
    worktreeDigest: null,
    filePath: 'src/AuthService.java',
    symbolName: 'login',
    symbolKind: 'METHOD',
    startLine: 12,
    endLine: 24,
    contentHash: 'b'.repeat(64),
    knowledgeCardId: null,
    knowledgeRevision: null,
    knowledgeReviewStatus: null,
    graphArtifactId: null,
    relationPath: [],
    retrievalChannel: null,
    findingId: null,
    detail: '当前发布快照中的代码事实',
    ...overrides,
  };
}

describe('ProvenanceSummary', () => {
  it('shows source semantics without presenting retrieval as probability', () => {
    const wrapper = mount(ProvenanceSummary, {
      props: {
        sources: [
          source({}),
          source({
            id: 'source-2',
            sourceType: 'RETRIEVAL_CANDIDATE',
            snapshotId: null,
            commitSha: null,
            filePath: null,
            symbolName: null,
            symbolKind: null,
            startLine: null,
            endLine: null,
            contentHash: null,
            knowledgeCardId: 'knowledge-1',
            knowledgeRevision: 3,
            knowledgeReviewStatus: 'APPROVED',
            retrievalChannel: 'KNOWLEDGE_KEYWORD',
            detail: '关键词召回，但未命中确定性适用范围',
          }),
        ],
      },
    });

    expect(wrapper.text()).toContain('代码事实');
    expect(wrapper.text()).toContain('检索候选');
    expect(wrapper.text()).toContain('仅用于排序候选');
    expect(wrapper.text()).not.toContain('%');
    expect(wrapper.text()).not.toContain('概率');
  });
});
