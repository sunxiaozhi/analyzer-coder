import type { IndexJob } from '@/types/api';
import { request } from './http';

export interface CodeReference {
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
  semanticScore: number;
  channels: string[];
  codeReferences: CodeReference[];
}

export interface Answer {
  conversationId: string;
  answer: string;
  snapshotId: string | null;
  citations: Citation[];
  provider: string;
  evidenceStatus: 'SUPPORTED' | 'DEGRADED' | 'MODEL_OUTPUT_REJECTED' | 'INSUFFICIENT';
  createdAt: string;
}
export interface QaSession { id:string;title:string;repositoryIds:string[];createdAt:string;updatedAt:string }
export interface QaMessage { id:string;role:'user'|'assistant';content:string;citations:string;conversationId:string|null;createdAt:string }

export interface GraphResult {
  nodes: { symbol: string; depth: number; focus: boolean }[];
  edges: { source: string; target: string; relation: string }[];
  risk: string;
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
  status: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
  verifiedCommit: string | null;
  codeReviewStatus: 'UNVERIFIED' | 'CURRENT' | 'REVIEW_REQUIRED';
  codeReviewedAt: string | null;
  attachments: KnowledgeAttachment[];
  codeReferences: CodeReference[];
}
export interface CardInput {
  title: string;
  cardType: string;
  content: string;
  tags: string[];
  status: string;
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
  status: string;
  changedBy: string | null;
  changedAt: string;
}

export const intelligenceApi = {
  ask: (repositoryId: string, question: string, options?:{sessionId?:string;repositoryIds?:string[]}) =>
    request<Answer>(`/api/repositories/${repositoryId}/ask`, {
      method: 'POST',
      body: JSON.stringify({ question, ...options }),
    }),
  sessions:()=>request<QaSession[]>('/api/qa/sessions'),
  createSession:(repositoryIds:string[],title='新会话')=>request<QaSession>('/api/qa/sessions',{method:'POST',body:JSON.stringify({repositoryIds,title})}),
  sessionMessages:(id:string)=>request<QaMessage[]>(`/api/qa/sessions/${id}/messages`),
  renameSession:(id:string,title:string)=>request<QaSession>(`/api/qa/sessions/${id}`,{method:'PATCH',body:JSON.stringify({title})}),
  deleteSession:(id:string)=>request<void>(`/api/qa/sessions/${id}`,{method:'DELETE'}),
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
