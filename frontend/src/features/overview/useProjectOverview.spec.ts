import { effectScope, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '@/api/repositories';
import { useProjectOverview } from './useProjectOverview';

let repositoryStore: { selectedRepositoryId: string | null };
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => repositoryStore }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }));
vi.mock('@/api/repositories', () => ({
  getIndexJob: vi.fn(), getProjectCodeFacts: vi.fn(), getProjectHealthOverview: vi.fn(),
  getRepositoryProfile: vi.fn(), prepareRepository: vi.fn(), retryPreparationStage: vi.fn(),
}));

describe('project preparation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    repositoryStore = reactive({ selectedRepositoryId: 'repo-1' });
  });
  it('continues after vector degradation to graph and knowledge checks', async () => {
    const completed = { repositoryId: 'repo-1', state: 'DEGRADED', activeJobId: null } as api.RepositoryPreparation;
    vi.mocked(api.getRepositoryProfile).mockResolvedValue(completed);
    vi.mocked(api.getProjectCodeFacts).mockResolvedValue(null as unknown as api.ProjectCodeFacts);
    vi.mocked(api.getProjectHealthOverview).mockResolvedValue(null as unknown as api.ProjectHealthOverview);
    const running = (id: string) => ({ repositoryId: 'repo-1', state: 'PROCESSING', activeJobId: id, activeJobStatus: 'QUEUED' } as api.RepositoryPreparation);
    vi.mocked(api.prepareRepository)
      .mockResolvedValueOnce(running('index')).mockResolvedValueOnce(running('graph'))
      .mockResolvedValueOnce(running('drift')).mockResolvedValueOnce(completed);
    vi.mocked(api.getIndexJob)
      .mockResolvedValueOnce({ type: 'FULL', status: 'SUCCEEDED', currentStep: 'full:completed:8:vectors-degraded' } as Awaited<ReturnType<typeof api.getIndexJob>>)
      .mockResolvedValueOnce({ type: 'CODEGRAPH', status: 'SUCCEEDED' } as Awaited<ReturnType<typeof api.getIndexJob>>)
      .mockResolvedValueOnce({ type: 'KNOWLEDGE_DRIFT', status: 'SUCCEEDED' } as Awaited<ReturnType<typeof api.getIndexJob>>);
    const scope = effectScope();
    const overview = scope.run(useProjectOverview)!;
    try {
      await flushPromises();
      await overview.prepare();
      expect(api.getIndexJob).toHaveBeenCalledTimes(3);
      expect(api.prepareRepository).toHaveBeenCalledTimes(4);
      expect(overview.preparation.value?.state).toBe('DEGRADED');
    } finally { scope.stop(); }
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

describe('preparation repository isolation', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.getProjectCodeFacts).mockResolvedValue(null as unknown as api.ProjectCodeFacts);
    vi.mocked(api.getProjectHealthOverview).mockResolvedValue(null as unknown as api.ProjectHealthOverview);
    repositoryStore = reactive({ selectedRepositoryId: 'repo-1' });
    vi.mocked(api.getRepositoryProfile).mockImplementation(async id => ({ repositoryId: id, state: 'READY', activeJobId: null } as api.RepositoryPreparation));
  });

  it('ignores a prepare response after switching away and back to the same repository', async () => {
    const pending = deferred<api.RepositoryPreparation>();
    vi.mocked(api.prepareRepository).mockReturnValue(pending.promise);
    const scope = effectScope();
    const overview = scope.run(useProjectOverview)!;
    try {
      await flushPromises();
      const operation = overview.prepare();
      repositoryStore.selectedRepositoryId = 'repo-2';
      await flushPromises();
      repositoryStore.selectedRepositoryId = 'repo-1';
      await flushPromises();
      pending.resolve({ repositoryId: 'repo-1', state: 'PROCESSING', activeJobId: 'old-job', activeJobStatus: 'QUEUED' } as api.RepositoryPreparation);
      await operation;
      expect(overview.preparation.value?.state).toBe('READY');
      expect(overview.preparing.value).toBe(false);
      expect(api.getIndexJob).not.toHaveBeenCalled();
    } finally { scope.stop(); }
  });

  it('stops advancing old jobs after a repository switch during polling', async () => {
    const pending = deferred<Awaited<ReturnType<typeof api.getIndexJob>>>();
    vi.mocked(api.prepareRepository).mockResolvedValue({ repositoryId: 'repo-1', state: 'PROCESSING', activeJobId: 'job', activeJobStatus: 'QUEUED' } as api.RepositoryPreparation);
    vi.mocked(api.getIndexJob).mockReturnValue(pending.promise);
    const scope = effectScope();
    const overview = scope.run(useProjectOverview)!;
    try {
      await flushPromises();
      const operation = overview.prepare();
      await flushPromises();
      repositoryStore.selectedRepositoryId = 'repo-2';
      await flushPromises();
      pending.resolve({ status: 'SUCCEEDED' } as Awaited<ReturnType<typeof api.getIndexJob>>);
      await operation;
      expect(overview.preparation.value?.repositoryId).toBe('repo-2');
      expect(api.prepareRepository).toHaveBeenCalledTimes(1);
      expect(overview.preparing.value).toBe(false);
    } finally { scope.stop(); }
  });

  it('does not advance a retry after its owner scope has closed', async () => {
    const pending = deferred<api.RepositoryPreparation>();
    vi.mocked(api.retryPreparationStage).mockReturnValue(pending.promise);
    const scope = effectScope();
    const overview = scope.run(useProjectOverview)!;
    await flushPromises();
    const operation = overview.retryStage('vectors');
    scope.stop();
    pending.resolve({ repositoryId: 'repo-1', state: 'PROCESSING', activeJobId: 'job', activeJobStatus: 'QUEUED' } as api.RepositoryPreparation);
    await operation;
    expect(api.getIndexJob).not.toHaveBeenCalled();
    expect(api.prepareRepository).not.toHaveBeenCalled();
  });
});
