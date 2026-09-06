import { effectScope } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import * as api from '@/api/repositories';
import { useProjectOverview } from './useProjectOverview';

vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => ({ selectedRepositoryId: 'repo-1' }) }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }));
vi.mock('@/api/repositories', () => ({
  getIndexJob: vi.fn(), getProjectCodeFacts: vi.fn(), getProjectHealthOverview: vi.fn(),
  getRepositoryProfile: vi.fn(), prepareRepository: vi.fn(), retryPreparationStage: vi.fn(),
}));

describe('project preparation', () => {
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
