import { request } from './http';
import type { UnifiedSearchResponse } from './intelligence';

export interface RepositoryBranch {
  id: string; name: string; snapshotId: string | null; commitSha: string | null;
  status: 'PENDING' | 'BUILDING' | 'READY' | 'FAILED'; error: string | null; generation: number;
  trackingStatus: 'ACTIVE' | 'ARCHIVED'; archivedAt: string | null;
}
export interface RemoteBranch {
  name: string;
  commitSha: string;
}
export interface BranchPreparationJob {
  id: string; branchId: string; status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  stage: string; error: string | null;
  kind: 'SNAPSHOT' | 'VECTORS'; snapshotId: string | null;
}
export interface BranchContext {
  contextId: string; repositoryId: string; branchId: string; branchName: string;
  snapshotId: string; commitSha: string; expiresAt: string;
}
export interface BranchScope {
  cardId: string; revision: number; mode: 'ALL_BRANCHES' | 'SELECTED_BRANCHES'; branchIds: string[];
}
export type BranchValidationState = 'CURRENT' | 'UNVERIFIED' | 'REVIEW_REQUIRED' | 'INVALID';
export interface BranchValidationCard {
  cardId: string; revision: number; title: string; content: string;
  state: BranchValidationState; note: string;
}
const base = (repositoryId: string) => `/api/repositories/${repositoryId}`;
const json = (body: unknown) => JSON.stringify(body);
export const branchesApi = {
  list: (repositoryId: string) => request<RepositoryBranch[]>(`${base(repositoryId)}/branches`),
  discover: (repositoryId: string) => request<RemoteBranch[]>(`${base(repositoryId)}/branches/discover`),
  track: (repositoryId: string, name: string) => request<RepositoryBranch>(`${base(repositoryId)}/branches`, { method: 'POST', body: json({ name }) }),
  archive: (repositoryId: string, branchId: string) => request<RepositoryBranch>(`${base(repositoryId)}/branches/${branchId}/archive`, { method: 'POST' }),
  restore: (repositoryId: string, branchId: string) => request<RepositoryBranch>(`${base(repositoryId)}/branches/${branchId}/restore`, { method: 'POST' }),
  prepare: (repositoryId: string, branchId: string) => request<BranchPreparationJob>(`${base(repositoryId)}/branches/${branchId}/prepare`, { method: 'POST' }),
  preparationJobs: (repositoryId: string) => request<BranchPreparationJob[]>(`${base(repositoryId)}/branch-preparation-jobs`),
  prepareVectors: (context: BranchContext) => request<BranchPreparationJob>(`${base(context.repositoryId)}/branch-vector-jobs`, { method: 'POST', body: json({ contextId: context.contextId, branchId: context.branchId }) }),
  context: (repositoryId: string, branchId: string) => request<BranchContext>(`${base(repositoryId)}/contexts`, { method: 'POST', body: json({ branchId }) }),
  search: (context: BranchContext, query: string) => request<UnifiedSearchResponse>(
    `${base(context.repositoryId)}/evidence-search?${new URLSearchParams({ query, limit: '30' })}`,
    { headers: { 'X-Branch-Context': context.contextId } },
  ),
  scopes: (repositoryId: string) => request<BranchScope[]>(`${base(repositoryId)}/knowledge/branch-scopes`),
  validations: (context: BranchContext) => request<BranchValidationCard[]>(`${base(context.repositoryId)}/knowledge/branch-validations?${new URLSearchParams({ contextId: context.contextId })}`),
  validate: (context: BranchContext, card: BranchValidationCard, state: BranchValidationState, note: string) => request<void>(`${base(context.repositoryId)}/knowledge/${card.cardId}/branch-validation`, {
    method: 'POST', body: json({ contextId: context.contextId, revision: card.revision, state, note }),
  }),
  scope: (repositoryId: string, scope: BranchScope) => request<void>(`${base(repositoryId)}/knowledge/${scope.cardId}/branch-scope`, { method: 'PUT', body: json(scope) }),
};
