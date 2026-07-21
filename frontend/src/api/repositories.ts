import { request } from '@/api/http';
import type {
  CodeChunkListResponse,
  IndexJob,
  IndexJobType,
  RegisterRepositoryPayload,
  Repository,
  RescanRepositoryResponse,
} from '@/types/api';

export function listRepositories(): Promise<Repository[]> {
  return request<Repository[]>('/api/repositories');
}

export function registerRepository(payload: RegisterRepositoryPayload): Promise<Repository> {
  return request<Repository>('/api/repositories', { method: 'POST', body: JSON.stringify(payload) });
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
