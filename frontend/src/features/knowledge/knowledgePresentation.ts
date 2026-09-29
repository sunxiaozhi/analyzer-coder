import type { KnowledgeCard, KnowledgeBranchScope } from '@/api/intelligence';
import type { BranchContext, BranchValidationState, RepositoryBranch } from '@/api/branches';
import { statusLabel } from '@/utils/displayLabels';

/** Excerpts are plain text, never HTML. */
export function knowledgeExcerpt(content: string) {
  return content.replace(/^\s*(```|~~~).*$/gm, '')
    .replace(/!?\[[^\]]*\]\(knowledge-attachment:\/\/[^)]+\)/g, '')
    .replace(/!?\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/^\s*(#{1,6}\s+|>\s*|[-*+]\s+|\d+\.\s+)/gm, '')
    .replace(/(\*\*|__|~~|`)/g, '').replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ').trim();
}

export function branchScopeLabel(scope: KnowledgeBranchScope | undefined, branches: Pick<RepositoryBranch, 'id' | 'name'>[], currentName?: string | null) {
  if (scope?.mode === 'ALL_BRANCHES') return '项目全部分支共享';
  const names = scope?.branchIds.map(id => branches.find(branch => branch.id === id)?.name ?? id) ?? [];
  if (names.length > 1) return `${names.join('、')} 共 ${names.length} 个分支共享`;
  return names[0] || currentName ? `仅 ${names[0] || currentName} 可见` : '共享范围未提供';
}

/** Display facts only; frozen read contexts also enforce retrieval policy. */
export function knowledgeStatus(card: KnowledgeCard, context?: BranchContext | null, validation?: BranchValidationState) {
  if (card.publicationStatus !== 'PUBLISHED') return { label: statusLabel(card.publicationStatus), type: 'info' as const };
  if (!context || !validation) return { label: '已发布', type: 'success' as const };
  if (validation === 'CURRENT') return { label: '已发布 · 当前分支已验证', type: 'success' as const };
  if (validation === 'INVALID') return { label: '已发布 · 当前分支不适用', type: 'danger' as const };
  if (validation === 'REVIEW_REQUIRED') return { label: '已发布 · 当前分支待复核', type: 'warning' as const };
  const generic = card.enforcement === 'REFERENCE' && !card.codeReferences?.length;
  return generic ? { label: '已发布 · 尚无当前分支验证', type: 'info' as const }
    : { label: '已发布 · 当前分支待验证', type: 'warning' as const };
}
