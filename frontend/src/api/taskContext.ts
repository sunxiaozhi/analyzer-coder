import { request } from '@/api/http';
import type { KnowledgeEnforcement, KnowledgeSeverity } from '@/api/intelligence';
import type { Provenance } from '@/types/evidence';

export type TaskContextEntryType =
  | 'VERIFIED_KNOWLEDGE'
  | 'CODE_FACT'
  | 'RETRIEVAL_CANDIDATE'
  | 'UNKNOWN';

export interface TaskContextRequest {
  task: string;
  taskReviewId?: string | null;
  maxItems?: number;
  maxChars?: number;
  maxTokens?: number;
}

export interface TaskContextEntry {
  id: string;
  type: TaskContextEntryType;
  title: string;
  content: string;
  severity: KnowledgeSeverity | null;
  enforcement: KnowledgeEnforcement | null;
  knowledgeId: string | null;
  knowledgeRevision: number | null;
  chunkId: string | null;
  filePath: string | null;
  symbolName: string | null;
  startLine: number | null;
  endLine: number | null;
  contentHash: string | null;
  requiredTests: string[];
  requiredApproverAccountIds: string[];
  sources: Provenance[];
  unknownCode: string | null;
}

export interface TaskContextBudget {
  maxItems: number;
  maxChars: number;
  maxTokens: number | null;
  effectiveMaxChars: number;
  selectedItems: number;
  usedChars: number;
  estimatedTokens: number;
  omittedItems: number;
  truncated: boolean;
}

export interface TaskContext {
  repositoryId: string;
  repositoryName: string;
  snapshotId: string;
  commitSha: string | null;
  task: string;
  taskReviewId: string | null;
  entries: TaskContextEntry[];
  requiredTests: string[];
  requiredApprovals: string[];
  unknowns: { code: string; detail: string; sources: Provenance[] }[];
  markdown: string;
  budget: TaskContextBudget;
}

export function generateTaskContext(repositoryId: string, payload: TaskContextRequest) {
  return request<TaskContext>(`/api/repositories/${repositoryId}/task-context`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}
