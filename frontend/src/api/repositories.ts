import { request } from '@/api/http';
import type { PageResult } from '@/types/pagination';
import type {
  CodeChunkListResponse,
  IndexJob,
  IndexJobType,
  RegisterRepositoryPayload,
  Repository,
  RepositoryFileContent,
  RepositorySnapshotFiles,
  RescanRepositoryResponse,
} from '@/types/api';

export type PreparationStageState = 'READY' | 'RUNNING' | 'PENDING' | 'FAILED' | 'DEGRADED';

export interface PreparationStage {
  key: 'snapshot' | 'content' | 'vectors' | 'graph';
  label: string;
  state: PreparationStageState;
  detail: string;
}

export interface ProjectProfileCount {
  name: string;
  count: number;
}

export interface ProjectProfile {
  fileCount: number;
  totalBytes: number;
  chunkCount: number;
  vectorizedChunks: number;
  missingChunks: number;
  knowledgeCards: number;
  graphNodes: number;
  graphEdges: number;
  languages: ProjectProfileCount[];
  modules: ProjectProfileCount[];
  entryPoints: string[];
}

export interface RepositoryPreparation {
  repositoryId: string;
  state: 'READY' | 'DEGRADED' | 'PROCESSING' | 'ACTION_REQUIRED' | 'NOT_READY';
  progress: number;
  message: string;
  stages: PreparationStage[];
  profile: ProjectProfile;
  activeJobId: string | null;
  activeJobType: IndexJobType | 'CODEGRAPH' | null;
  activeJobStatus: IndexJob['status'] | null;
}

export function listRepositories(): Promise<Repository[]> {
  return request<Repository[]>('/api/repositories');
}


export function listRepositoryPage(params: { query?: string; pageNum: number; pageSize: number }): Promise<PageResult<Repository>> {
  const search = new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize) });
  if (params.query?.trim()) search.set('query', params.query.trim());
  return request<PageResult<Repository>>(`/api/repositories/page?${search}`);
}

export function registerRepository(payload: RegisterRepositoryPayload): Promise<Repository> {
  return request<Repository>('/api/repositories', { method: 'POST', body: JSON.stringify(payload) });
}


export function updateRepository(repositoryId: string, payload: {
  name: string; description: string; defaultBranch: string; version: number;
}): Promise<Repository> {
  return request<Repository>(`/api/repositories/${repositoryId}`, {
    method: 'PATCH', body: JSON.stringify(payload),
  });
}

export function rescanRepository(repositoryId: string): Promise<RescanRepositoryResponse> {
  return request<RescanRepositoryResponse>(`/api/repositories/${repositoryId}/rescan`, { method: 'POST' });
}

export function deleteRepository(repositoryId: string): Promise<void> {
  return request<void>(`/api/repositories/${repositoryId}`, { method: 'DELETE' });
}

export function startIndex(repositoryId: string, type: IndexJobType): Promise<IndexJob> {
  return request<IndexJob>(`/api/repositories/${repositoryId}/index`, {
    method: 'POST',
    body: JSON.stringify({ type }),
  });
}

export function listIndexJobs(): Promise<IndexJob[]> {
  return request<IndexJob[]>('/api/index-jobs');
}

export function getLatestIndexStatus(repositoryId: string): Promise<IndexJob> {
  return request<IndexJob>(`/api/repositories/${repositoryId}/index/status`);
}

export function getIndexJob(indexJobId: string): Promise<IndexJob> {
  return request<IndexJob>(`/api/index-jobs/${indexJobId}`);
}

export function listChunks(
  repositoryId: string,
  params: { q?: string; limit?: number; offset?: number } = {},
): Promise<CodeChunkListResponse> {
  const search = new URLSearchParams();
  if (params.q) search.set('q', params.q);
  if (params.limit) search.set('limit', String(params.limit));
  if (params.offset) search.set('offset', String(params.offset));
  const suffix = search.toString() ? `?${search}` : '';
  return request<CodeChunkListResponse>(`/api/repositories/${repositoryId}/chunks${suffix}`);
}
export function syncRemoteRepository(repositoryId: string): Promise<RescanRepositoryResponse & { indexJobId: string | null }> {
  return request(`/api/repositories/${repositoryId}/sync`, { method: 'POST' });
}

export function getRepositoryProfile(repositoryId: string): Promise<RepositoryPreparation> {
  return request<RepositoryPreparation>(`/api/repositories/${repositoryId}/profile`);
}

export function prepareRepository(repositoryId: string): Promise<RepositoryPreparation> {
  return request<RepositoryPreparation>(`/api/repositories/${repositoryId}/prepare`, { method: 'POST' });
}

export function listRepositoryFiles(repositoryId: string): Promise<RepositorySnapshotFiles> {
  return request<RepositorySnapshotFiles>(`/api/repositories/${repositoryId}/files`);
}

export function getRepositoryFile(repositoryId: string, path: string): Promise<RepositoryFileContent> {
  return request<RepositoryFileContent>(
    `/api/repositories/${repositoryId}/files/content?path=${encodeURIComponent(path)}`,
  );
}
