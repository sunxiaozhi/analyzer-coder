import { shallowReadonly, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  getIndexJob,
  getProjectCodeFacts,
  getRepositoryProfile,
  prepareRepository,
  type ProjectCodeFacts,
  type RepositoryPreparation,
} from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';

export function useProjectOverview() {
  const repositories = useRepositoryStore();
  const preparation = shallowRef<RepositoryPreparation | null>(null);
  const codeFacts = shallowRef<ProjectCodeFacts | null>(null);
  const loading = shallowRef(false);
  const preparing = shallowRef(false);
  const error = shallowRef<string | null>(null);
  let loadVersion = 0;

  async function load(repositoryId: string | null) {
    const version = ++loadVersion;
    preparation.value = null;
    codeFacts.value = null;
    error.value = null;
    if (!repositoryId) return;
    loading.value = true;
    try {
      const [profile, facts] = await Promise.all([
        getRepositoryProfile(repositoryId),
        getProjectCodeFacts(repositoryId).catch(() => null),
      ]);
      if (version !== loadVersion) return;
      preparation.value = profile;
      codeFacts.value = facts;
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
      for (let round = 0; round < 4; round += 1) {
        const result = await prepareRepository(repositoryId);
        preparation.value = result;
        const active = result.activeJobId
          && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(result.activeJobStatus ?? '');
        if (!active) break;
        await waitForJob(result.activeJobId!);
      }
      await load(repositoryId);
      if (preparation.value?.state === 'READY') ElMessage.success('项目已准备完成');
      else if (preparation.value?.state === 'DEGRADED') ElMessage.warning('项目已准备完成，向量能力处于降级状态');
      else if (preparation.value?.state === 'ACTION_REQUIRED') ElMessage.error(preparation.value.message);
    } catch (exception) {
      ElMessage.error(exception instanceof Error ? exception.message : '项目准备失败');
      await load(repositoryId);
    } finally {
      preparing.value = false;
    }
  }

  async function waitForJob(jobId: string) {
    for (let attempt = 0; attempt < 240; attempt += 1) {
      const job = await getIndexJob(jobId);
      if (job.status === 'SUCCEEDED') return;
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
    loading: shallowReadonly(loading),
    preparing: shallowReadonly(preparing),
    error: shallowReadonly(error),
    reload: () => load(repositories.selectedRepositoryId),
    prepare,
  };
}
