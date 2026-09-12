import { describe, expect, it } from 'vitest';
import type { KnowledgeCard } from '@/api/intelligence';
import { useKnowledgeCardEditor } from './useKnowledgeCardEditor';

function legacyCard(): KnowledgeCard {
  return {
    id: 'card-1',
    repositoryId: 'repo-1',
    title: '旧知识',
    cardType: '旧业务分类',
    content: '旧正文',
    renderedContent: '<p>旧正文</p>',
    tags: ['旧标签'],
    knowledgeKind: 'BUSINESS_RULE',
    severity: 'CRITICAL',
    enforcement: 'REQUIRED',
    ownerAccountId: 'owner-1',
    scope: {
      pathPatterns: ['old/**'],
      symbols: ['OldService'],
      modules: ['legacy-module'],
      repositoryIds: ['repo-2'],
      serviceNames: ['legacy-service'],
      contractIds: ['contract-1'],
    },
    obligations: {
      requiredTests: ['npm test'],
      requiredApproverAccountIds: ['approver-1'],
      instructions: ['先检查兼容性'],
      prohibitedPathPatterns: ['secrets/**'],
      knowledgeUpdateRequired: true,
    },
    lastVerifiedSnapshotId: 'snapshot-1',
    verificationNote: null,
    publicationStatus: 'DRAFT',
    revision: 2,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-02T00:00:00Z',
    verifiedCommit: 'abc123',
    sourceVersionStatus: 'CURRENT',
    sourceVersionCheckedAt: '2026-09-02T00:00:00Z',
    reviewStatus: 'APPROVED',
    reviewedBy: 'reviewer-1',
    reviewedAt: '2026-09-02T00:00:00Z',
    attachments: [],
    codeReferences: [],
  };
}

describe('useKnowledgeCardEditor', () => {
  it('creates a minimal card payload with searchable fields and effective scope', () => {
    const { form, scopeText, reset, toPayload } = useKnowledgeCardEditor(() => 'owner-current');
    reset(null);
    form.title = ' 退款规则 ';
    form.content = '退款前必须检查支付状态。';
    form.tags = [' refund ', 'refund', 'payment'];
    form.knowledgeKind = 'BUSINESS_RULE';
    scopeText.paths = 'backend/refund/**\nbackend/refund/**';
    scopeText.symbols = 'RefundService';

    const payload = toPayload({ attachmentIds: [], codeReferences: [] });

    expect(payload.title).toBe('退款规则');
    expect(payload.tags).toEqual(['refund', 'payment']);
    expect(payload.scope.pathPatterns).toEqual(['backend/refund/**']);
    expect(payload.scope.symbols).toEqual(['RefundService']);
    expect(payload.scope.modules).toEqual([]);
    expect(payload.scope.repositoryIds).toEqual([]);
    expect(payload.obligations.requiredTests).toEqual([]);
    expect(payload.cardType).toBe('知识卡片');
    expect(payload.severity).toBe('INFO');
    expect(payload.ownerAccountId).toBe('owner-current');
  });

  it('preserves hidden legacy metadata when an existing card is edited', () => {
    const { form, scopeText, reset, toPayload } = useKnowledgeCardEditor(() => 'owner-current');
    reset(legacyCard());
    form.title = '更新后的知识';
    scopeText.paths = 'new/**';
    scopeText.symbols = 'NewService';

    const payload = toPayload({ attachmentIds: [], codeReferences: [] });

    expect(payload.cardType).toBe('旧业务分类');
    expect(payload.severity).toBe('CRITICAL');
    expect(payload.scope.pathPatterns).toEqual(['new/**']);
    expect(payload.scope.symbols).toEqual(['NewService']);
    expect(payload.scope.modules).toEqual(['legacy-module']);
    expect(payload.scope.repositoryIds).toEqual(['repo-2']);
    expect(payload.scope.serviceNames).toEqual(['legacy-service']);
    expect(payload.scope.contractIds).toEqual(['contract-1']);
    expect(payload.obligations.requiredTests).toEqual(['npm test']);
    expect(payload.obligations.knowledgeUpdateRequired).toBe(true);
  });
});
