export type IndexJobType = 'FULL' | 'INCREMENTAL';

export type IndexJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export type ChunkType = 'FILE' | 'SYMBOL' | 'DOC_SECTION' | 'TEST_CASE' | 'CONFIG' | 'KNOWLEDGE_CARD';

export interface ApiErrorResponse {
  code: string;
  message: string;
  timestamp: string;
}

export interface Repository {
  id: string;
  name: string;
  path: string;
  codeGraphPath: string;
}

export interface RegisterRepositoryPayload {
  name: string;
  path: string;
}

export interface IndexJob {
  id: string;
  repositoryId: string;
  type: IndexJobType;
  status: IndexJobStatus;
  currentStep: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export interface CodeChunk {
  id: string;
  repositoryId: string;
  commitSha: string;
  filePath: string;
  symbolId: string | null;
  symbolName: string | null;
  symbolKind: string | null;
  language: string | null;
  chunkType: ChunkType;
  startLine: number | null;
  endLine: number | null;
  content: string;
  contentHash: string;
  createdAt: string;
}

export interface CodeChunkListResponse {
  total: number;
  limit: number;
  offset: number;
  chunks: CodeChunk[];
}
