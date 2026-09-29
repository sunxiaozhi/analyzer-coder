import type { KnowledgeCard } from '@/api/intelligence';
import type { BranchContext, BranchValidationCard } from '@/api/branches';

export function cardForBranch(card: KnowledgeCard, context: BranchContext | null, validations: BranchValidationCard[]): KnowledgeCard {
  if (!context || !card.branchScope || (card.branchScope.mode === 'SELECTED_BRANCHES' && card.branchScope.branchIds.length === 1)) return card;
  const validation = validations.find(item => item.cardId === card.id && item.revision === card.revision);
  const status: Record<string, KnowledgeCard['sourceVersionStatus']> = {
    CURRENT: 'CURRENT', INVALID: 'STALE', REVIEW_REQUIRED: 'SUSPECT', UNVERIFIED: 'UNVERIFIED',
  };
  return { ...card, sourceVersionStatus: status[validation?.state ?? 'UNVERIFIED'],
    verificationNote: validation?.note || null,
    lastVerifiedContentVersion: validation?.state === 'CURRENT' ? context.contentVersion : null,
    verifiedCommit: validation?.state === 'CURRENT' ? context.commitSha : null };
}
