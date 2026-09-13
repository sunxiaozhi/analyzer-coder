import { request } from './http';
import type { UnifiedSearchResponse } from './intelligence';

export interface RepositoryBranch {
  id: string; name: string; snapshotId: string | null; commitSha: string | null;
  status: 'PENDING' | 'BUILDING' | 'READY' | 'FAILED'; error: string | null; generation: number;
}
export interface BranchContext {
  contextId: string; repositoryId: string; branchId: string; branchName: string;
  snapshotId: string; commitSha: string; expiresAt: string;
}
export interface BranchScope {
  cardId: string; revision: number; mode: 'ALL_BRANCHES' | 'SELECTED_BRANCHES'; branchIds: string[];
}
const base = (repositoryId: string) => `/api/repositories/${repositoryId}`;
const json = (body: unknown) => JSON.stringify(body);
export const branchesApi = {
  list: (repositoryId: string) => request<RepositoryBranch[]>(`${base(repositoryId)}/branches`),
  track: (repositoryId: string, name: string) => request<RepositoryBranch>(`${base(repositoryId)}/branches`, { method: 'POST', body: json({ name }) }),
  prepare: (repositoryId: string, branchId: string) => request<RepositoryBranch>(`${base(repositoryId)}/branches/${branchId}/prepare`, { method: 'POST' }),
  context: (repositoryId: string, branchId: string) => request<BranchContext>(`${base(repositoryId)}/contexts`, { method: 'POST', body: json({ branchId }) }),
  search: (context: BranchContext, query: string) => request<UnifiedSearchResponse>(
    `${base(context.repositoryId)}/evidence-search?${new URLSearchParams({ query, limit: '30' })}`,
    { headers: { 'X-Branch-Context': context.contextId } },
  ),
  scopes: (repositoryId: string) => request<BranchScope[]>(`${base(repositoryId)}/knowledge/branch-scopes`),
  scope: (repositoryId: string, scope: BranchScope) => request<void>(`${base(repositoryId)}/knowledge/${scope.cardId}/branch-scope`, { method: 'PUT', body: json(scope) }),
};
