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
});
