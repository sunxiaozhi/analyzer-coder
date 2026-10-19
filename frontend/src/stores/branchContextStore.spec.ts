import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { branchesApi, type BranchContext, type RepositoryBranch } from '@/api/branches';
import { useBranchContextStore } from './branchContextStore';

vi.mock('@/api/branches', () => ({
  branchesApi: { list: vi.fn(), context: vi.fn() },
}));

const branch = (id: string, status: RepositoryBranch['status'] = 'READY'): RepositoryBranch => ({
  id,
  name: id,
  contentVersion: status === 'READY' ? `contentVersion-${id}` : null,
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

  it('uses default branch only as an initial preference, without falling back from an unready choice', async () => {
    vi.mocked(branchesApi.list).mockResolvedValue([branch('main'), branch('release', 'PENDING')]);
    const store = useBranchContextStore();
    await store.load('repo-1', null, 'release');
    expect(store.selectedBranchId).toBe('release');
    expect(store.context).toBeNull();
    expect(branchesApi.context).not.toHaveBeenCalled();
  });

  it('locks a ready branch and clears the previous context before switching', async () => {
    vi.mocked(branchesApi.list).mockResolvedValue([branch('main'), branch('release')]);
    vi.mocked(branchesApi.context).mockImplementation(async (repositoryId, branchId) => ({
      contextId: `context-${branchId}`,
      repositoryId,
      branchId,
      branchName: branchId,
      contentVersion: `contentVersion-${branchId}`,
      commitSha: `commit-${branchId}`,
      expiresAt: '2026-09-15T01:00:00Z',
    }));
    const store = useBranchContextStore();

    await store.load('repo-1', 'main');
    expect(store.context?.branchId).toBe('main');
    const switching = store.select('release');
    expect(store.context).toBeNull();
    await switching;
    expect(store.context?.contentVersion).toBe('contentVersion-release');
  });

  it('never creates a fallback context for an unprepared branch', async () => {
    vi.mocked(branchesApi.list).mockResolvedValue([branch('feature', 'PENDING')]);
    const store = useBranchContextStore();

    await store.load('repo-1');

    expect(store.selectedBranchId).toBe('feature');
    expect(store.context).toBeNull();
    expect(branchesApi.context).not.toHaveBeenCalled();
  });

  it('clears a previous loading state when selecting an unready branch', async () => {
    const store = useBranchContextStore();
    store.repositoryId = 'repo-1';
    store.branches = [branch('feature', 'PENDING')];
    store.loading = true;
    await store.select('feature');
    expect(store.loading).toBe(false);
    expect(store.context).toBeNull();
  });
  it('creates a ready read context even when preference reads and writes fail', async () => {
    vi.mocked(branchesApi.list).mockResolvedValue([branch('main')]);
    vi.mocked(branchesApi.context).mockResolvedValue({ contextId: 'ctx', repositoryId: 'repo', branchId: 'main' } as BranchContext);
    const reading = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('storage unavailable'); });
    const writing = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    try {
      const store = useBranchContextStore(); await store.load('repo');
      expect(store.context?.contextId).toBe('ctx'); expect(store.error).toBeNull();
      expect(branchesApi.context).toHaveBeenCalledWith('repo', 'main');
    } finally { reading.mockRestore(); writing.mockRestore(); }
  });

  it('keeps an unready selection unready when preference storage fails', async () => {
    const store = useBranchContextStore(); store.repositoryId = 'repo'; store.branches = [branch('feature', 'PENDING')];
    const writing = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    try {
      await store.select('feature');
      expect(store.selectedBranchId).toBe('feature'); expect(store.context).toBeNull();
      expect(branchesApi.context).not.toHaveBeenCalled();
    } finally { writing.mockRestore(); }
  });

  it('preserves the old pin on a background failure and applies a later successful refresh', async () => {
    const store = useBranchContextStore(); store.repositoryId = 'repo'; store.selectedBranchId = 'main';
    const old = { contextId: 'old', repositoryId: 'repo', branchId: 'main', contentVersion: 'old-version' } as BranchContext;
    store.context = old; store.branches = [branch('main')];
    vi.mocked(branchesApi.list).mockRejectedValueOnce(new Error('network')).mockResolvedValue([branch('main')]);
    vi.mocked(branchesApi.context).mockResolvedValue({ ...store.context!, contextId: 'new', contentVersion: 'new-version' });
    await expect(store.refreshCurrent()).rejects.toThrow('network');
    expect(store.context).toEqual(old);
    expect(await store.refreshCurrent()).toBe(true); expect(store.context?.contextId).toBe('new');
  });

  it('rechecks edit protection before applying a background response', async () => {
    const store = useBranchContextStore(); store.repositoryId = 'repo'; store.selectedBranchId = 'main';
    store.context = { contextId: 'old', repositoryId: 'repo', branchId: 'main' } as BranchContext;
    vi.mocked(branchesApi.list).mockResolvedValue([branch('main')]);
    let finish!: (value: any) => void;
    vi.mocked(branchesApi.context).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    let safe = true;
    const refreshing = store.refreshCurrent(() => safe);
    await Promise.resolve(); safe = false; finish({ contextId: 'new' });
    expect(await refreshing).toBe(false); expect(store.context?.contextId).toBe('old');
  });

  it('does not apply a background response after switching to another branch', async () => {
    const store = useBranchContextStore(); store.repositoryId = 'repo'; store.selectedBranchId = 'main';
    store.branches = [branch('main'), branch('release')];
    store.context = { contextId: 'old-main', repositoryId: 'repo', branchId: 'main' } as BranchContext;
    vi.mocked(branchesApi.list).mockResolvedValue(store.branches);
    let finish!: (context: BranchContext) => void;
    vi.mocked(branchesApi.context).mockImplementation(async (_, id) => id === 'main'
      ? new Promise(resolve => { finish = resolve; })
      : { contextId: 'release', repositoryId: 'repo', branchId: 'release' } as BranchContext);
    const refreshing = store.refreshCurrent(); await Promise.resolve();
    await store.select('release'); finish({ contextId: 'late-main' } as BranchContext);
    expect(await refreshing).toBe(false); expect(store.context?.contextId).toBe('release');
  });

});
