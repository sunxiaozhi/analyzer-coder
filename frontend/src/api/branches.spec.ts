import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './http';
import { branchesApi, type BranchContext } from './branches';
vi.mock('./http', () => ({ request: vi.fn() }));
describe('branch API boundaries', () => {
  beforeEach(() => vi.mocked(request).mockReset());
  it('pins searches to the explicitly provided context', async () => {
    const context: BranchContext = { contextId: 'context-a', repositoryId: 'repo-a', branchId: 'branch-a', branchName: 'main', snapshotId: 'snapshot-a', commitSha: 'abc', expiresAt: '' };
    await branchesApi.search(context, '退款');
    expect(request).toHaveBeenCalledWith('/api/repositories/repo-a/evidence-search?query=%E9%80%80%E6%AC%BE&limit=30', { headers: { 'X-Branch-Context': 'context-a' } });
  });
  it('discovers remote branches without tracking them implicitly', async () => {
    await branchesApi.discover('repo-a');
    expect(request).toHaveBeenCalledWith('/api/repositories/repo-a/branches/discover');
    expect(request).toHaveBeenCalledTimes(1);
  });
  it('does not retry an expired context against the default branch', async () => {
    vi.mocked(request).mockRejectedValueOnce(new Error('CONTEXT_EXPIRED'));
    await expect(branchesApi.search({ repositoryId: 'repo', contextId: 'expired' } as BranchContext, 'test')).rejects.toThrow('CONTEXT_EXPIRED');
    expect(request).toHaveBeenCalledTimes(1);
  });
  it('sends the revision when updating a shared scope', async () => {
    const scope = { cardId: 'card', revision: 3, mode: 'ALL_BRANCHES' as const, branchIds: [] };
    await branchesApi.scope('repo', scope);
    expect(request).toHaveBeenCalledWith('/api/repositories/repo/knowledge/card/branch-scope', { method: 'PUT', body: JSON.stringify(scope) });
  });
  it('submits code vectors for the chosen reading context without preparing another snapshot', async () => {
    await branchesApi.prepareVectors({ repositoryId: 'repo', contextId: 'ctx', branchId: 'branch' } as BranchContext);
    expect(request).toHaveBeenCalledWith('/api/repositories/repo/branch-vector-jobs', { method: 'POST', body: JSON.stringify({ contextId: 'ctx', branchId: 'branch' }) });
    expect(request).toHaveBeenCalledTimes(1);
  });
  it('pins validation reads and writes to the exact context and revision', async () => {
    const context = { repositoryId: 'repo', contextId: 'ctx' } as BranchContext;
    await branchesApi.validations(context);
    expect(request).toHaveBeenLastCalledWith('/api/repositories/repo/knowledge/branch-validations?contextId=ctx');
    await branchesApi.validate(context, { cardId: 'card', revision: 7, title: 'title', content: 'content', state: 'UNVERIFIED', note: '' }, 'CURRENT', 'checked');
    expect(request).toHaveBeenLastCalledWith('/api/repositories/repo/knowledge/card/branch-validation', { method: 'POST', body: JSON.stringify({ contextId: 'ctx', revision: 7, state: 'CURRENT', note: 'checked' }) });
  });
});
