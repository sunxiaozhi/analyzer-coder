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
  nodes: { symbol: string; depth: number; focus: boolean }[];
  edges: { source: string; target: string; relation: string }[];
  risk: string;
  relationSource: 'CODEGRAPH_CLI' | 'HEURISTIC_CALL_REFERENCE';
  snapshotId: string;
  algorithm: string;
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
export interface KnowledgeCard {
  id: string;
  repositoryId: string;
  title: string;
  cardType: string;
  content: string;
  renderedContent: string;
  tags: string[];
  publicationStatus: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  revision: number;
  createdAt: string;
  updatedAt: string;
  verifiedCommit: string | null;
  sourceVersionStatus: 'UNVERIFIED' | 'CURRENT' | 'STALE';
  sourceVersionCheckedAt: string | null;
  reviewStatus: 'UNREVIEWED' | 'APPROVED' | 'CHANGES_REQUESTED';
  reviewedBy: string | null;
  reviewedAt: string | null;
  attachments: KnowledgeAttachment[];
  codeReferences: CodeReference[];
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
  publicationStatus: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  changedBy: string | null;
  changedAt: string;
}

export const intelligenceApi = {
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
