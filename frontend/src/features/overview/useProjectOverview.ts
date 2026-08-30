import { shallowReadonly, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  getIndexJob,
  getProjectCodeFacts,
  getProjectHealthOverview,
  getRepositoryProfile,
  prepareRepository,
  retryPreparationStage,
  type PreparationStage,
  type ProjectCodeFacts,
  type ProjectHealthOverview,
  type RepositoryPreparation,
} from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';

export function useProjectOverview() {
  const repositories = useRepositoryStore();
  const preparation = shallowRef<RepositoryPreparation | null>(null);
  const codeFacts = shallowRef<ProjectCodeFacts | null>(null);
  const health = shallowRef<ProjectHealthOverview | null>(null);
  const loading = shallowRef(false);
  const preparing = shallowRef(false);
  const error = shallowRef<string | null>(null);
  let loadVersion = 0;

  async function load(repositoryId: string | null) {
    const version = ++loadVersion;
    preparation.value = null;
    codeFacts.value = null;
    health.value = null;
    error.value = null;
    if (!repositoryId) return;
    loading.value = true;
    try {
      const [profile, facts, projectHealth] = await Promise.all([
        getRepositoryProfile(repositoryId),
        getProjectCodeFacts(repositoryId).catch(() => null),
        getProjectHealthOverview(repositoryId),
      ]);
      if (version !== loadVersion) return;
      preparation.value = profile;
      codeFacts.value = facts;
      health.value = projectHealth;
    } catch (exception) {
      if (version === loadVersion) {
        error.value = exception instanceof Error ? exception.message : '项目总览加载失败';
      }
    } finally {
      if (version === loadVersion) loading.value = false;
    }
  }

  async function prepare() {
    const repositoryId = repositories.selectedRepositoryId;
    if (!repositoryId || preparing.value) return;
    preparing.value = true;
    try {
      await drivePreparation(repositoryId);
      await load(repositoryId);
      notifyPreparationResult();
    } catch (exception) {
      ElMessage.error(exception instanceof Error ? exception.message : '项目准备失败');
      await load(repositoryId);
    } finally {
      preparing.value = false;
    }
  }

  async function retryStage(stage: PreparationStage['key']) {
    const repositoryId = repositories.selectedRepositoryId;
    if (!repositoryId || preparing.value) return;
    preparing.value = true;
    try {
      const retried = await retryPreparationStage(repositoryId, stage);
      preparation.value = retried;
      if (activeJob(retried) && retried.activeJobId) await waitForJob(retried.activeJobId);
      await drivePreparation(repositoryId);
      await load(repositoryId);
      notifyPreparationResult();
    } catch (exception) {
      ElMessage.error(exception instanceof Error ? exception.message : '阶段重试失败');
      await load(repositoryId);
    } finally {
      preparing.value = false;
    }
  }

  async function drivePreparation(repositoryId: string) {
    for (let round = 0; round < 8; round += 1) {
      const result = await prepareRepository(repositoryId);
      preparation.value = result;
      if (!activeJob(result) || !result.activeJobId) return;
      const completed = await waitForJob(result.activeJobId);
      if (
        (completed.type === 'FULL' || completed.type === 'INCREMENTAL')
        && completed.currentStep?.includes(':vectors-degraded')
      ) return;
    }
    throw new Error('准备流程超过预期阶段数，请刷新后查看具体失败阶段');
  }

  function activeJob(result: RepositoryPreparation) {
    return Boolean(
      result.activeJobId
      && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(result.activeJobStatus ?? ''),
    );
  }

  function notifyPreparationResult() {
    if (preparation.value?.state === 'READY') ElMessage.success('项目已准备完成');
    else if (preparation.value?.state === 'DEGRADED') ElMessage.warning('准备已完成，但存在可见的降级项');
    else if (preparation.value?.state === 'ACTION_REQUIRED') ElMessage.error(preparation.value.message);
  }

  async function waitForJob(jobId: string) {
    for (let attempt = 0; attempt < 240; attempt += 1) {
      const job = await getIndexJob(jobId);
      if (job.status === 'SUCCEEDED') return job;
      if (job.status === 'FAILED' || job.status === 'CANCELED') {
        throw new Error(job.errorMessage ?? '项目准备任务未完成');
      }
      await new Promise(resolve => window.setTimeout(resolve, 1500));
    }
    throw new Error('项目仍在后台准备，请稍后刷新项目总览');
  }

  watch(() => repositories.selectedRepositoryId, load, { immediate: true });

  return {
    preparation: shallowReadonly(preparation),
    codeFacts: shallowReadonly(codeFacts),
    health: shallowReadonly(health),
    loading: shallowReadonly(loading),
    preparing: shallowReadonly(preparing),
    error: shallowReadonly(error),
    reload: () => load(repositories.selectedRepositoryId),
    prepare,
    retryStage,
  };
}
