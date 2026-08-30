import type { KnowledgeMatchReason } from '@/api/taskReviews';
import type { Provenance } from '@/types/evidence';

export type ReviewEvidenceKind = 'CHANGE' | 'KNOWLEDGE' | 'OBLIGATION' | 'STALE' | 'UNKNOWN' | 'MODEL';

export interface ReviewEvidenceSelection {
  kind: ReviewEvidenceKind;
  eyebrow: string;
  title: string;
  status: string;
  description: string;
  filePath?: string | null;
  startLine?: number | null;
  endLine?: number | null;
  knowledgeId?: string | null;
  facts: { label: string; value: string; mono?: boolean }[];
  evidence: KnowledgeMatchReason[];
  sources: Provenance[];
}
