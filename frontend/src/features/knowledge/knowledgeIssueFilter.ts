import type { KnowledgeCard } from '@/api/intelligence';
import type { BranchValidationState } from '@/api/branches';

export const knowledgeIssueLabels = {
  KNOWLEDGE_REVIEW_REQUIRED: '当前分支待复核或失效',
  SUSPECT_KNOWLEDGE: '当前分支待复核',
  STALE_KNOWLEDGE: '当前分支不适用',
  REQUIRED_KNOWLEDGE_WITHOUT_OWNER: '强约束知识缺少维护人',
  UNREVIEWED_KNOWLEDGE: '未审核知识',
  NO_TRUSTED_KNOWLEDGE: '待发布、审核或验证',
} as const;
export type KnowledgeIssue = keyof typeof knowledgeIssueLabels;

export function parseKnowledgeIssue(value: unknown): KnowledgeIssue | null {
  return typeof value === 'string' && Object.hasOwn(knowledgeIssueLabels, value) ? value as KnowledgeIssue : null;
}

/** Match the branch overview's non-archived knowledge and current-revision validation. */
export function matchesKnowledgeIssue(card: KnowledgeCard, issue: KnowledgeIssue | null,
  validation: BranchValidationState, knowledgeOnly: boolean) {
  if (!issue) return true;
  if (card.publicationStatus === 'ARCHIVED') return false;
  switch (issue) {
    case 'KNOWLEDGE_REVIEW_REQUIRED': return !knowledgeOnly && (validation === 'REVIEW_REQUIRED' || validation === 'INVALID');
    case 'SUSPECT_KNOWLEDGE': return !knowledgeOnly && validation === 'REVIEW_REQUIRED';
    case 'STALE_KNOWLEDGE': return !knowledgeOnly && validation === 'INVALID';
    case 'REQUIRED_KNOWLEDGE_WITHOUT_OWNER': return card.enforcement === 'REQUIRED' && !card.ownerAccountId;
    case 'UNREVIEWED_KNOWLEDGE': return card.reviewStatus === 'UNREVIEWED';
    case 'NO_TRUSTED_KNOWLEDGE': return card.publicationStatus !== 'PUBLISHED' || card.reviewStatus !== 'APPROVED'
      || (!knowledgeOnly && validation !== 'CURRENT');
  }
}
