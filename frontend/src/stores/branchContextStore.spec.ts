import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { branchesApi, type RepositoryBranch } from '@/api/branches';
import { useBranchContextStore } from './branchContextStore';

vi.mock('@/api/branches', () => ({
  branchesApi: { list: vi.fn(), context: vi.fn() },
}));

const branch = (id: string, status: RepositoryBranch['status'] = 'READY'): RepositoryBranch => ({
  id,
  name: id,
  snapshotId: status === 'READY' ? `snapshot-${id}` : null,
  commitSha: status === 'READY' ? `commit-${id}` : null,
  status,
  error: null,
  generation: 1,
  trackingStatus: 'ACTIVE',
  archivedAt: null,
});

describe('branch context store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
    vi.resetAllMocks();
  });

  it('locks a ready branch and clears the previous context before switching', async () => {
    vi.mocked(branchesApi.list).mockResolvedValue([branch('main'), branch('release')]);
    vi.mocked(branchesApi.context).mockImplementation(async (repositoryId, branchId) => ({
      contextId: `context-${branchId}`,
      repositoryId,
      branchId,
      branchName: branchId,
      snapshotId: `snapshot-${branchId}`,
      commitSha: `commit-${branchId}`,
      expiresAt: '2026-09-15T01:00:00Z',
    }));
    const store = useBranchContextStore();

    await store.load('repo-1', 'main');
    expect(store.context?.branchId).toBe('main');
    const switching = store.select('release');
    expect(store.context).toBeNull();
    await switching;
    expect(store.context?.snapshotId).toBe('snapshot-release');
  });

  it('never creates a fallback context for an unprepared branch', async () => {
    vi.mocked(branchesApi.list).mockResolvedValue([branch('feature', 'PENDING')]);
    const store = useBranchContextStore();

    await store.load('repo-1');

    expect(store.selectedBranchId).toBe('feature');
    expect(store.context).toBeNull();
    expect(branchesApi.context).not.toHaveBeenCalled();
  });
});
