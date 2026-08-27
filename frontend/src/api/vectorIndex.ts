import { request } from '@/api/http';
import type { PageResult } from '@/types/pagination';

export type VectorStatus = 'EMBEDDED' | 'MISSING';
export type VectorSource = 'code' | 'knowledge';

export interface VectorIndexSummary {
  repositoryId: string;
  snapshotId: string | null;
  commitSha: string | null;
  totalChunks: number;
  vectorizedChunks: number;
  missingChunks: number;
  knowledgeCards: number;
  vectorizedKnowledgeCards: number;
  vectorModel: string | null;
  dimension: number | null;
  retrievalCapability: 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING';
  capabilityLabel: '字符相似度' | '语义检索';
  updatedAt: string | null;
}

export interface VectorIndexChunk {
  id: string;
  snapshotId: string;
  commitSha: string;
  filePath: string;
  symbolName: string | null;
  symbolKind: string | null;
  language: string | null;
  chunkType: string;
  startLine: number | null;
  endLine: number | null;
  contentExcerpt: string;
  contentHash: string;
  vectorModel: string | null;
  dimension: number | null;
  retrievalCapability: 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING' | null;
  capabilityLabel: '字符相似度' | '语义检索' | null;
  vectorizedAt: string | null;
  vectorStatus: VectorStatus;
}

export interface VectorIndexKnowledge {
  id: string;
  title: string;
  cardType: string;
  revision: number;
  contentExcerpt: string;
  contentHash: string | null;
  vectorModel: string | null;
  dimension: number | null;
  retrievalCapability: 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING' | null;
  capabilityLabel: '字符相似度' | '语义检索' | null;
  vectorizedAt: string | null;
  vectorStatus: VectorStatus;
}

export interface VectorIndexQuery {
  q?: string;
  status?: VectorStatus;
  chunkType?: string;
  pageNum: number;
  pageSize: number;
}

function queryString(params: VectorIndexQuery) {
  const query = new URLSearchParams({
    pageNum: String(params.pageNum),
    pageSize: String(params.pageSize),
  });
  if (params.q) query.set('q', params.q);
  if (params.status) query.set('status', params.status);
  if (params.chunkType) query.set('chunkType', params.chunkType);
  return query.toString();
}

export const vectorIndexApi = {
  summary: (repositoryId: string) =>
    request<VectorIndexSummary>(`/api/repositories/${repositoryId}/vector-index/summary`),
  chunks: (repositoryId: string, params: VectorIndexQuery) =>
    request<PageResult<VectorIndexChunk>>(
      `/api/repositories/${repositoryId}/vector-index/chunks?${queryString(params)}`,
    ),
  knowledge: (repositoryId: string, params: VectorIndexQuery) =>
    request<PageResult<VectorIndexKnowledge>>(
      `/api/repositories/${repositoryId}/vector-index/knowledge?${queryString(params)}`,
    ),
};
