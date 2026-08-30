import { request } from '@/api/http';
import type {
  KnowledgeEnforcement,
  KnowledgeKind,
  KnowledgeObligations,
  KnowledgeSeverity,
} from '@/api/intelligence';
import type { Provenance } from '@/types/evidence';

export type TaskReviewStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type ChangeSource = 'WORKTREE' | 'SINGLE_COMMIT' | 'COMMIT_RANGE';
export type ChangeType = 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED' | 'COPIED';
export type SymbolResolution = 'CODEGRAPH' | 'SOURCE_DECLARATION' | 'CHUNK_SYMBOL' | 'FILE_LEVEL';

export interface TaskReviewCreatePayload {
  clientRequestId: string;
  task: string | null;
  changeSource: ChangeSource;
  baseRef: string | null;
  headRef: string | null;
  modelConfigId: string | null;
}

export type PullRequestProviderKind = 'GITHUB' | 'GITLAB';

export interface PullRequestReviewPayload {
  clientRequestId: string;
  provider: PullRequestProviderKind;
  number: number;
  task: string | null;
  modelConfigId: string | null;
  apiBaseUrl: string | null;
}

export interface PullRequestReviewResult {
  provider: PullRequestProviderKind;
  externalId: string;
  number: number;
  title: string;
  webUrl: string | null;
  author: string | null;
  draft: boolean;
  fetchedAt: string;
  review: TaskReviewResult;
  comment: {
    action: 'CREATED' | 'UPDATED';
    commentId: string;
    commentUrl: string | null;
  };
}

export interface GitHunk {
  oldStart: number;
  oldCount: number;
  newStart: number;
  newCount: number;
}

export interface GitFileChange {
  type: ChangeType;
  oldPath: string | null;
  newPath: string | null;
  binary: boolean;
  additions: number | null;
  deletions: number | null;
  hunks: GitHunk[];
}

export interface RepositoryChange {
  source: ChangeSource;
  baseCommit: string | null;
  headCommit: string | null;
  worktreeDigest: string | null;
  partial: boolean;
  changes: GitFileChange[];
  limitations: { code: string; detail: string }[];
}

export interface SymbolProvenance {
  sourceType: 'CODEGRAPH_NODE' | 'SOURCE_TEXT' | 'CHUNK_INDEX' | 'FILE_CHANGE';
  repositoryId: string;
  snapshotId: string | null;
  commitSha: string | null;
  worktreeDigest: string | null;
  filePath: string;
  startLine: number | null;
  endLine: number | null;
  side: 'OLD' | 'NEW';
  detail: string;
}

export interface ChangedSymbol {
  symbolId: string;
  name: string;
  kind: string;
  filePath: string;
  declarationStartLine: number;
  declarationEndLine: number;
  changeType: ChangeType;
  oldStartLine: number | null;
  newStartLine: number | null;
  hunkIndex: number;
  syntheticHunk: boolean;
  resolution: SymbolResolution;
  provenance: SymbolProvenance[];
}

export interface KnowledgeEvidence {
  sourceType: 'GIT_FACT' | 'CODE_FACT' | 'GRAPH_INFERENCE';
  repositoryId: string;
  snapshotId: string | null;
  commitSha: string | null;
  filePath: string;
  symbolName: string | null;
  moduleId: string | null;
  knowledgeChunkId: string | null;
  detail: string;
}

export interface KnowledgeMatchReason {
  kind: 'CODE_REFERENCE' | 'PATH_PATTERN' | 'SYMBOL' | 'MODULE';
  rule: string;
  target: string;
  evidence: KnowledgeEvidence;
}

export interface KnowledgeMatch {
  knowledgeId: string;
  title: string;
  kind: KnowledgeKind;
  severity: KnowledgeSeverity;
  enforcement: KnowledgeEnforcement;
  ownerAccountId: string | null;
  revision: number;
  sourceVersionStatus: string;
  obligations: KnowledgeObligations;
  reasons: KnowledgeMatchReason[];
  sources: Provenance[];
}

export interface KnowledgeReferenceCandidate {
  knowledgeId: string;
  title: string;
  kind: KnowledgeKind;
  sourceVersionStatus: string;
  retrievalSource: string;
  detail: string;
  provenance: Provenance;
}

export interface ReviewUnknownReason {
  code: string;
  knowledgeId: string | null;
  filePath: string | null;
  rule: string | null;
  detail: string;
}

export interface TaskReviewFinding {
  kind: 'REQUIRED_TEST' | 'REQUIRED_APPROVAL' | 'UNKNOWN';
  key: string;
  title: string;
  status: 'REQUIRED_NOT_REPORTED' | 'REQUIRED' | 'UNKNOWN';
  knowledgeIds: string[];
  evidence: KnowledgeMatchReason[];
  unknownReason: ReviewUnknownReason | null;
  sources: Provenance[];
}

export type ModelSummaryStatus = 'NOT_REQUESTED' | 'COMPLETED' | 'UNAVAILABLE' | 'REJECTED';

export interface ModelSummaryEvidence {
  id: string;
  kind: string;
  title: string;
  detail: string;
  filePath: string | null;
  startLine: number | null;
  endLine: number | null;
  knowledgeId: string | null;
}

export interface ModelSummaryFinding {
  text: string;
  evidenceIds: string[];
  evidence: ModelSummaryEvidence[];
  sources: Provenance[];
}

export interface ModelSummary {
  summary: string;
  findings: ModelSummaryFinding[];
  unknowns: string[];
  provider: string;
  sourceType: 'MODEL_SUGGESTION';
  generatedAt: string;
}

export interface TaskReviewResult {
  reviewId: string;
  status: TaskReviewStatus;
  repositoryId: string;
  snapshotId: string;
  createdBy: string;
  clientRequestId: string;
  modelConfigId: string | null;
  task: string | null;
  changeSource: ChangeSource;
  baseRef: string | null;
  headRef: string | null;
  change: RepositoryChange | null;
  changedSymbols: ChangedSymbol[];
  applicableKnowledge: KnowledgeMatch[];
  referenceCandidates: KnowledgeReferenceCandidate[];
  requiredTests: TaskReviewFinding[];
  requiredApprovals: TaskReviewFinding[];
  staleKnowledge: KnowledgeMatch[];
  unknowns: TaskReviewFinding[];
  summary: string | null;
  modelSummary: ModelSummary | null;
  modelSummaryState: {
    status: ModelSummaryStatus;
    code: string | null;
    detail: string | null;
  };
  error: { code: string; message: string } | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface TaskReviewSummary {
  reviewId: string;
  status: TaskReviewStatus;
  repositoryId: string;
  snapshotId: string;
  createdBy: string;
  clientRequestId: string;
  task: string | null;
  changeSource: ChangeSource;
  changedFileCount: number;
  changedSymbolCount: number;
  applicableKnowledgeCount: number;
  requiredTestCount: number;
  requiredApprovalCount: number;
  staleKnowledgeCount: number;
  unknownCount: number;
  error: { code: string; message: string } | null;
  createdAt: string;
  finishedAt: string | null;
}

export function createTaskReview(repositoryId: string, payload: TaskReviewCreatePayload) {
  return request<TaskReviewResult>(`/api/repositories/${repositoryId}/task-reviews`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function createPullRequestReview(repositoryId: string, payload: PullRequestReviewPayload) {
  return request<PullRequestReviewResult>(
    `/api/repositories/${repositoryId}/pull-request-reviews`,
    { method: 'POST', body: JSON.stringify(payload) },
  );
}

export function listTaskReviews(repositoryId: string, limit = 20, offset = 0) {
  return request<TaskReviewSummary[]>(
    `/api/repositories/${repositoryId}/task-reviews?limit=${limit}&offset=${offset}`,
  );
}

export function getTaskReview(repositoryId: string, reviewId: string) {
  return request<TaskReviewResult>(
    `/api/repositories/${repositoryId}/task-reviews/${reviewId}`,
  );
}
