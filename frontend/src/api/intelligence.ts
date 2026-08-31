import type { IndexJob } from '@/types/api';
import { request } from './http';

export interface CodeReference {
  repositoryId: string;
  chunkId: string | null;
  snapshotId: string | null;
  filePath: string;
  symbolName: string | null;
  startLine: number | null;
  endLine: number | null;
  contentHash: string;
  stale: boolean;
}

export interface Citation {
  id: string;
  repositoryId: string;
  sourceType: 'CODE' | 'KNOWLEDGE';
  chunkId: string | null;
  knowledgeCardId: string | null;
  snapshotId: string | null;
  title: string;
  filePath: string;
  symbolName: string | null;
  startLine: number | null;
  endLine: number | null;
  content: string;
  rank: number;
  score: number;
  lexicalScore: number;
  similarityScore: number;
  similarityKind: 'NONE' | 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING';
  channels: string[];
  codeReferences: CodeReference[];
}

export interface CitationAssessment {
  factualBlockCount: number;
  citedBlockCount: number;
  uncitedBlockCount: number;
  coverageRate: number;
  invalidReferences: string[];
  entailmentVerified: boolean;
}

export interface RetrievalDiagnostics {
  snapshotId: string | null;
  vectorModel: string | null;
  retrievalCapability: 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING' | 'UNKNOWN' | null;
  enabledChannels: string[];
  unavailableChannels: { channel: string; reason: string; detail: string }[];
  channelMetrics: { channel: string; recalledCount: number; durationMs: number }[];
  recalledCount: number;
  durationMs: number;
  degraded: boolean;
  degradationReasons: string[];
}

export interface HybridSearchHit {
  chunkId: string;
  snapshotId: string;
  filePath: string;
  symbolName: string | null;
  symbolKind: string | null;
  startLine: number | null;
  endLine: number | null;
  content: string;
  contentHash: string;
  score: number;
  lexicalScore: number;
  similarityScore: number;
  similarityKind: 'NONE' | 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING';
  channels: string[];
}

export interface HybridSearchResponse {
  hits: HybridSearchHit[];
  retrieval: RetrievalDiagnostics;
}

export interface CodeEvidenceKnowledgeReference {
  knowledgeId: string;
  title: string;
  kind: string;
  severity: string;
  enforcement: string;
  ownerAccountId: string | null;
  revision: number;
  publicationStatus: string;
  reviewStatus: string;
  sourceVersionStatus: string;
  trusted: boolean;
  bindings: {
    chunkId: string | null;
    snapshotId: string | null;
    symbolName: string | null;
    startLine: number | null;
    endLine: number | null;
    contentHash: string;
    stale: boolean;
    currentSnapshot: boolean;
  }[];
}

export interface CodeEvidenceReviewReference {
  reviewId: string;
  task: string | null;
  changeSource: string;
  snapshotId: string;
  currentSnapshot: boolean;
  roles: string[];
  symbols: string[];
  createdAt: string;
  finishedAt: string | null;
}

export interface CodeEvidenceContext {
  repositoryId: string;
  snapshotId: string | null;
  commitSha: string | null;
  filePath: string;
  symbol: string | null;
  knowledgeReferences: CodeEvidenceKnowledgeReference[];
  reviewReferences: CodeEvidenceReviewReference[];
  scannedReviewCount: number;
  limitations: string[];
  generatedAt: string;
}

export interface Answer {
  conversationId: string;
  threadId: string;
  turnNo: number;
  repositoryId: string;
  title: string;
  question: string;
  answer: string;
  snapshotId: string | null;
  citations: Citation[];
  provider: string;
  evidenceStatus:
    | 'CITATION_COMPLETE'
    | 'CITATION_INCOMPLETE'
    | 'SUPPORTED'
    | 'DEGRADED'
    | 'MODEL_OUTPUT_REJECTED'
    | 'INSUFFICIENT';
  fallbackReason: string | null;
  citationAssessment: CitationAssessment | null;
  retrieval: RetrievalDiagnostics;
  createdAt: string;
}
export interface AskModel {
  id: string;
  name: string;
  model: string;
  availability: string;
  breakerState: string;
  available: boolean;
}
export interface QaThreadDetail {
  threadId: string;
  repositoryId: string;
  title: string;
  turns: Answer[];
}
export interface QaHistoryRecord {
  threadId: string;
  repositoryId: string;
  title: string;
  question: string;
  provider: string;
  evidenceStatus: Answer['evidenceStatus'];
  fallbackReason: string | null;
  citationCount: number;
  turnCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface GraphResult {
  nodes: {
    id: string;
    symbol: string;
    kind: string;
    filePath: string;
    startLine: number | null;
    endLine: number | null;
    depth: number;
    focus: boolean;
  }[];
  edges: {
    id: string;
    source: string;
    target: string;
    relation: string;
    sourceLine: number | null;
  }[];
  paths: { targetNodeId: string; nodeIds: string[]; edgeIds: string[]; depth: number }[];
  relationSource: 'CODEGRAPH_CLI';
  graphArtifactId: string;
  snapshotId: string;
  cliVersion: string;
  affectedNodeCount: number;
  maxDepthReached: number;
  coverage: {
    cliReportedNodeCount: number;
    cliReportedEdgeCount: number;
    representedNodeCount: number;
    representedEdgeCount: number;
    affectedRecordCount: number;
    representedAffectedRecordCount: number;
    unmappedAffectedRecordCount: number;
    complete: boolean;
  };
  limitations: string[];
}
export interface GraphTarget { symbol: string; filePath: string; startLine: number | null }
export interface CodeGraphArtifact {
  id: string;
  repositoryId: string;
  snapshotId: string;
  cliVersion: string;
  status: string;
  artifactPath: string;
  nodeCount: number;
  edgeCount: number;
}
export interface KnowledgeAttachment {
  id: string;
  originalName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  scanStatus: string;
  createdAt: string;
}
export type KnowledgeKind =
  | 'REFERENCE'
  | 'BUSINESS_RULE'
  | 'ARCH_DECISION'
  | 'API_CONTRACT'
  | 'DATA_CONSTRAINT'
  | 'TEST_OBLIGATION'
  | 'SECURITY_POLICY'
  | 'RUNBOOK'
  | 'INCIDENT_LESSON'
  | 'OWNERSHIP'
  | 'TECH_DEBT';
export type KnowledgeSeverity = 'INFO' | 'WARNING' | 'CRITICAL';
export type KnowledgeEnforcement = 'REFERENCE' | 'ADVISORY' | 'REQUIRED';
export interface KnowledgeScope {
  pathPatterns: string[];
  symbols: string[];
  modules: string[];
  repositoryIds: string[];
  serviceNames: string[];
  contractIds: string[];
}
export interface KnowledgeObligations {
  requiredTests: string[];
  requiredApproverAccountIds: string[];
  instructions: string[];
  prohibitedPathPatterns: string[];
  knowledgeUpdateRequired: boolean;
}
export interface KnowledgeCard {
  id: string;
  repositoryId: string;
  title: string;
  cardType: string;
  content: string;
  renderedContent: string;
  tags: string[];
  knowledgeKind: KnowledgeKind;
  severity: KnowledgeSeverity;
  enforcement: KnowledgeEnforcement;
  ownerAccountId: string | null;
  scope: KnowledgeScope;
  obligations: KnowledgeObligations;
  lastVerifiedSnapshotId: string | null;
  verificationNote: string | null;
  publicationStatus: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  revision: number;
  createdAt: string;
  updatedAt: string;
  verifiedCommit: string | null;
  sourceVersionStatus: 'UNVERIFIED' | 'CURRENT' | 'SUSPECT' | 'STALE';
  sourceVersionCheckedAt: string | null;
  reviewStatus: 'UNREVIEWED' | 'APPROVED' | 'CHANGES_REQUESTED';
  reviewedBy: string | null;
  reviewedAt: string | null;
  attachments: KnowledgeAttachment[];
  codeReferences: CodeReference[];
}
export type KnowledgeDriftReasonKind =
  | 'CODE_REFERENCE_HASH_CHANGED'
  | 'PATH_SCOPE_MATCHED'
  | 'SYMBOL_SCOPE_MATCHED'
  | 'MANUAL_CONFIRMATION'
  | 'MANUAL_STALE_DECISION';
export interface KnowledgeDriftReason {
  kind: KnowledgeDriftReasonKind;
  rule: string | null;
  filePath: string | null;
  startLine: number | null;
  endLine: number | null;
  changeType: string | null;
  detail: string;
}
export interface KnowledgeDriftEvent {
  id: string;
  repositoryId: string;
  cardId: string;
  cardRevision: number;
  fromSnapshotId: string | null;
  toSnapshotId: string;
  fromCommit: string | null;
  toCommit: string | null;
  previousStatus: KnowledgeCard['sourceVersionStatus'];
  resultStatus: Exclude<KnowledgeCard['sourceVersionStatus'], 'UNVERIFIED'>;
  triggerType: 'AUTOMATIC_DIFF' | 'MANUAL_CONFIRM_CURRENT' | 'MANUAL_MARK_STALE';
  reasons: KnowledgeDriftReason[];
  note: string | null;
  actorId: string | null;
  createdAt: string;
}
export interface KnowledgeSourceReviewResponse {
  card: KnowledgeCard;
  event: KnowledgeDriftEvent;
}
export type MarkdownKnowledgeSourceStatus = 'PENDING' | 'CURRENT' | 'STALE';
export interface MarkdownKnowledgeSourceCounts {
  total: number;
  pending: number;
  current: number;
  stale: number;
}
export interface MarkdownKnowledgeSource {
  sourceId: string;
  sourcePath: string;
  sourceSnapshotId: string;
  sourceContentHash: string;
  title: string;
  assetType: string;
  lineCount: number;
  byteSize: number;
  excerpt?: string | null;
  updatedAt?: string | null;
  status: MarkdownKnowledgeSourceStatus;
  cardId: string | null;
  cardRevision: number | null;
  cardTitle?: string | null;
  cardStatus?: string | null;
  generatedSnapshotId: string | null;
  generatedContentHash: string | null;
}
export interface MarkdownKnowledgeSourceList {
  snapshotId: string;
  counts: MarkdownKnowledgeSourceCounts;
  items: MarkdownKnowledgeSource[];
}
export interface GenerateMarkdownKnowledgeSourceInput {
  sourcePath: string;
  expectedSnapshotId: string;
  expectedContentHash: string;
}
export interface MarkdownKnowledgeBatchGenerationResult {
  generated: number;
  remaining: number;
}
export interface CardInput {
  title: string;
  cardType: string;
  content: string;
  tags: string[];
  knowledgeKind: KnowledgeKind;
  severity: KnowledgeSeverity;
  enforcement: KnowledgeEnforcement;
  ownerAccountId: string | null;
  scope: KnowledgeScope;
  obligations: KnowledgeObligations;
  attachmentIds: string[];
  codeReferences: { chunkId: string }[];
}
export interface CardRevision {
  cardId: string;
  revision: number;
  repositoryId: string;
  title: string;
  cardType: string;
  content: string;
  renderedContent: string;
  tags: string[];
  knowledgeKind: KnowledgeKind;
  severity: KnowledgeSeverity;
  enforcement: KnowledgeEnforcement;
  ownerAccountId: string | null;
  scope: KnowledgeScope;
  obligations: KnowledgeObligations;
  lastVerifiedSnapshotId: string | null;
  verificationNote: string | null;
  publicationStatus: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  changedBy: string | null;
  changedAt: string;
}

export const intelligenceApi = {
  search: (repositoryId: string, query: string, limit = 50) =>
    request<HybridSearchResponse>(
      `/api/repositories/${repositoryId}/hybrid-search?query=${encodeURIComponent(query)}&limit=${limit}`,
    ),
  codeEvidenceContext: (repositoryId: string, filePath: string, symbol: string | null) => {
    const query = new URLSearchParams({ filePath });
    if (symbol) query.set('symbol', symbol);
    return request<CodeEvidenceContext>(
      `/api/repositories/${repositoryId}/code-evidence-context?${query}`,
    );
  },
  ask: (
    repositoryId: string,
    question: string,
    clientRequestId: string,
    threadId: string | null,
    modelConfigId: string,
  ) =>
    request<Answer>(`/api/repositories/${repositoryId}/ask`, {
      method: 'POST',
      body: JSON.stringify({ question, clientRequestId, threadId, modelConfigId }),
    }),
  askModels: (repositoryId: string) =>
    request<AskModel[]>(`/api/repositories/${repositoryId}/ask/models`),
  history: (repositoryId: string, limit = 50, offset = 0) =>
    request<QaHistoryRecord[]>(`/api/repositories/${repositoryId}/qa/records?limit=${limit}&offset=${offset}`),
  historyDetail: (repositoryId: string, threadId: string) =>
    request<QaThreadDetail>(`/api/repositories/${repositoryId}/qa/records/${threadId}`),
  renameHistory: (repositoryId: string, threadId: string, title: string) =>
    request<QaHistoryRecord>(`/api/repositories/${repositoryId}/qa/records/${threadId}`, {
      method: 'PATCH', body: JSON.stringify({ title }),
    }),
  deleteHistory: (repositoryId: string, threadId: string) =>
    request<void>(`/api/repositories/${repositoryId}/qa/records/${threadId}`, { method: 'DELETE' }),
  graph: (repositoryId: string, symbol: string, depth: number, _direction: string) =>
    request<GraphResult>(
      `/api/repositories/${repositoryId}/codegraph/impact?symbol=${encodeURIComponent(symbol)}&depth=${depth}`
    ),
  graphTarget: (repositoryId: string, chunkId: string) =>
    request<GraphTarget>(`/api/repositories/${repositoryId}/chunks/${chunkId}/graph-target`),
  buildGraph: (repositoryId: string) =>
    request<IndexJob>(`/api/repositories/${repositoryId}/codegraph/build`, { method: 'POST' }),
  latestGraph: (repositoryId: string) =>
    request<CodeGraphArtifact | null>(`/api/repositories/${repositoryId}/codegraph/latest`),
  uploadAttachment: (repositoryId: string, file: File) => {
    const body = new FormData();
    body.append('file', file);
    return request<KnowledgeAttachment>(
      `/api/repositories/${repositoryId}/knowledge/attachments`,
      { method: 'POST', body }
    );
  },
  cards: (repositoryId: string) =>
    request<KnowledgeCard[]>(`/api/repositories/${repositoryId}/knowledge`),
  markdownSources: (repositoryId: string) =>
    request<MarkdownKnowledgeSourceList>(
      `/api/repositories/${repositoryId}/knowledge/markdown-sources`,
    ),
  generateMarkdownSource: (
    repositoryId: string,
    input: GenerateMarkdownKnowledgeSourceInput,
  ) =>
    request<KnowledgeCard>(
      `/api/repositories/${repositoryId}/knowledge/markdown-sources/generate`,
      { method: 'POST', body: JSON.stringify(input) },
    ),
  generatePendingMarkdownSources: (repositoryId: string, expectedSnapshotId: string) =>
    request<MarkdownKnowledgeBatchGenerationResult>(
      `/api/repositories/${repositoryId}/knowledge/markdown-sources/generate-pending`,
      { method: 'POST', body: JSON.stringify({ expectedSnapshotId }) },
    ),
  createCard: (repositoryId: string, input: CardInput) =>
    request<KnowledgeCard>(`/api/repositories/${repositoryId}/knowledge`, {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  updateCard: (repositoryId: string, id: string, input: CardInput) =>
    request<KnowledgeCard>(`/api/repositories/${repositoryId}/knowledge/${id}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }),
  reviewCard: (
    repositoryId: string,
    id: string,
    reviewStatus: 'APPROVED' | 'CHANGES_REQUESTED',
  ) => request<KnowledgeCard>(`/api/repositories/${repositoryId}/knowledge/${id}/review`, {
    method: 'POST',
    body: JSON.stringify({ reviewStatus }),
  }),
  setCardPublication: (
    repositoryId: string,
    id: string,
    publicationStatus: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED',
  ) => request<KnowledgeCard>(`/api/repositories/${repositoryId}/knowledge/${id}/publication`, {
    method: 'POST',
    body: JSON.stringify({ publicationStatus }),
  }),
  sourceDrift: (repositoryId: string, id: string) =>
    request<KnowledgeDriftEvent | null>(
      `/api/repositories/${repositoryId}/knowledge/${id}/source-drift`,
    ).then(result => result ?? null),
  reviewKnowledgeSource: (
    repositoryId: string,
    id: string,
    action: 'CONFIRM_CURRENT' | 'MARK_STALE',
    expectedRevision: number,
    note: string,
  ) => request<KnowledgeSourceReviewResponse>(
    `/api/repositories/${repositoryId}/knowledge/${id}/source-review`,
    { method: 'POST', body: JSON.stringify({ action, expectedRevision, note }) },
  ),
  cardHistory: (repositoryId: string, id: string) =>
    request<CardRevision[]>(`/api/repositories/${repositoryId}/knowledge/${id}/history`),
  restoreCardRevision: (repositoryId: string, id: string, revision: number) =>
    request<KnowledgeCard>(
      `/api/repositories/${repositoryId}/knowledge/${id}/history/${revision}/restore`,
      { method: 'POST' }
    ),
  settings: () => request<Record<string, string>>('/api/settings'),
  saveSettings: (input: Record<string, string>) =>
    request<Record<string, string>>('/api/settings', {
      method: 'PUT',
      body: JSON.stringify(input),
    }),
};
