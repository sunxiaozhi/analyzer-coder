import type { Answer, Citation, EvidenceSource } from '@/api/intelligence';
export const markdownSource: EvidenceSource = { sourceType: 'MARKDOWN', id: null, filePath: 'docs/refund.md', branchId: 'main', contentVersion: 'v1', revision: 4, startLine: null, endLine: null };
export function citationFixture(patch: Partial<Citation> = {}): Citation {
  return { id: 'k1', repositoryId: 'repo', sourceType: 'KNOWLEDGE', chunkId: null, knowledgeCardId: 'card', contentVersion: null,
    title: '退款规则', filePath: 'knowledge://card', symbolName: null, startLine: null, endLine: null,
    content: '## 背景\n\n退款规则：七天内可以退款。\n\n仅在完整快照中的后续说明。', excerpt: '退款规则：七天内可以退款。',
    excerptStartLine: 3, excerptEndLine: 3, knowledgeRevision: 4, rank: 3, score: .9, lexicalScore: .9,
    similarityScore: .8, similarityKind: 'SEMANTIC_EMBEDDING', channels: [], sourceScope: '项目共享', codeReferences: [], ...patch };
}
export function markdownFixture(patch: Partial<Citation> = {}): Citation {
  return citationFixture({ id: 'md', rank: 5, sourceType: 'CODE', knowledgeCardId: null, knowledgeRevision: null,
    chunkId: 'chunk', contentVersion: 'v1', filePath: 'docs/refund.md', title: 'docs/refund.md', startLine: 1, endLine: 3,
    content: '# 来源文档\n\n仅来源文档中保存的内容。', excerpt: '仅来源文档中保存的内容。', ...patch });
}
export function answerFixture(id: string, citations: Citation[]): Answer {
  return { conversationId: id, threadId: 'thread', repositoryId: 'repo', title: '退款', question: '退款', answer: '实现 [S5]。', citations,
    contentVersion: 'v1', branchId: 'main', branchName: 'main', commitSha: null, fallbackReason: null, citationAssessment: null,
    retrieval: { recalledCount: citations.length, durationMs: 1, degraded: false, enabledChannels: [], unavailableChannels: [], channelMetrics: [],
      contentVersion: 'v1', vectorModel: null, retrievalCapability: null, degradationReasons: [] },
    provider: 'model', evidenceStatus: 'CITATION_COMPLETE', createdAt: new Date().toISOString(), turnNo: 1 };
}
