import { onScopeDispose, shallowReadonly, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  getBranchOverview, getIndexJob, getProjectCodeFacts, getProjectHealthOverview, getRepositoryProfile,
  prepareRepository, retryPreparationStage,
  type PreparationStage, type ProjectCodeFacts, type ProjectHealthOverview, type RepositoryPreparation,
} from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { useBranchReadScope } from '@/features/branches/useBranchReadScope';
import { branchesApi } from '@/api/branches';

export function useProjectOverview() {
  const repositories = useRepositoryStore();
  const branches = useBranchContextStore();
  const readScope = useBranchReadScope();
  const preparation = shallowRef<RepositoryPreparation | null>(null);
  const codeFacts = shallowRef<ProjectCodeFacts | null>(null);
  const health = shallowRef<ProjectHealthOverview | null>(null);
  const loading = shallowRef(false);
  const preparing = shallowRef(false);
  const error = shallowRef<string | null>(null);
  let loadVersion = 0;
  let contextVersion = 0;
  let disposed = false;
  let refreshTimer: ReturnType<typeof setTimeout> | undefined;

  function isCurrent(repositoryId: string, version: number) {
    return !disposed && version === contextVersion && repositoryId === repositories.selectedRepositoryId;
  }

  async function load(repositoryId: string | null) {
    clearTimeout(refreshTimer);
    const version = ++loadVersion;
    const context = contextVersion;
    preparation.value = null;
    codeFacts.value = null;
    health.value = null;
    error.value = null;
    loading.value = false;
    if (!repositoryId) return;
    if (readScope.blocked.value) {
      if (branches.loading) loading.value = true;
      else error.value = readScope.reason.value;
      return;
    }
    loading.value = true;
    try {
      if (readScope.requiresContext.value && branches.context) {
        const pinned = branches.context;
        const result = await getBranchOverview(repositoryId, pinned.contextId);
        if (version !== loadVersion || !isCurrent(repositoryId, context)) return;
        if ([result.preparation, result.codeFacts, result.health].some(value =>
          value.snapshotId !== pinned.snapshotId || value.commitSha !== pinned.commitSha)) {
          throw new Error('分支总览版本不一致，请刷新阅读上下文');
        }
        preparation.value = result.preparation;
        codeFacts.value = result.codeFacts;
        health.value = result.health;
        return;
      }
      const [profile, facts, projectHealth] = await Promise.all([
        getRepositoryProfile(repositoryId),
        getProjectCodeFacts(repositoryId).catch(() => null),
        getProjectHealthOverview(repositoryId),
      ]);
      if (version !== loadVersion || !isCurrent(repositoryId, context)) return;
      if (projectHealth && (profile.snapshotId !== projectHealth.snapshotId
        || profile.commitSha !== projectHealth.commitSha)) {
        throw new Error('读取期间项目快照发生变化，请重新加载总览以获取同一版本的数据');
      }
      preparation.value = profile;
      codeFacts.value = facts && facts.snapshotId === profile.snapshotId && facts.commitSha === profile.commitSha ? facts : null;
      health.value = projectHealth;
      if (profile.state === 'PROCESSING') {
        refreshTimer = setTimeout(() => {
          if (isCurrent(repositoryId, context) && !preparing.value) void load(repositoryId);
        }, 5000);
      }
    } catch (exception) {
      if (version === loadVersion && isCurrent(repositoryId, context)) {
        error.value = exception instanceof Error ? exception.message : '项目总览加载失败';
      }
    } finally {
      if (version === loadVersion && isCurrent(repositoryId, context)) loading.value = false;
    }
  }

  async function runPreparation(stage?: PreparationStage['key']) {
    const repositoryId = repositories.selectedRepositoryId;
    if (!repositoryId || preparing.value) return;
    if (readScope.requiresContext.value) {
      const branchId = branches.selectedBranchId;
      if (!branchId) { ElMessage.warning('请在项目管理中选择分支'); return; }
      preparing.value = true;
      const version = contextVersion;
      try {
        // Preparation is a durable branch job, not a default-pointer mutation.
        if (stage === 'knowledge_drift') { ElMessage.warning('请在知识页验证当前分支的知识'); return; }
        const kind = stage === 'content' ? 'CONTENT' : stage === 'graph' ? 'GRAPH' : stage === 'vectors' ? 'VECTORS' : 'PREPARE';
        if (kind === 'VECTORS' && branches.context) {
          await branchesApi.prepareVectors(branches.context);
        } else if (kind === 'VECTORS') {
          throw new Error(readScope.reason.value);
        } else if (kind === 'CONTENT' || kind === 'GRAPH') {
          if (!branches.context || readScope.blocked.value) throw new Error(readScope.reason.value);
          await branchesApi.codeOperation(repositoryId, branchId, kind, branches.context.contextId);
        } else {
          await branchesApi.codeOperation(repositoryId, branchId, 'PREPARE');
        }
        if (isCurrent(repositoryId, version)) ElMessage.success('已提交当前分支任务，可在项目管理中查看进度');
      } catch (cause) {
        if (isCurrent(repositoryId, version)) ElMessage.error(cause instanceof Error ? cause.message : '分支任务提交失败');
      } finally { if (isCurrent(repositoryId, version)) preparing.value = false; }
      return;
    }
    const version = contextVersion;
    preparing.value = true;
    try {
      if (stage) {
        const retried = await retryPreparationStage(repositoryId, stage);
        if (!isCurrent(repositoryId, version)) return;
        preparation.value = retried;
        if (activeJob(retried) && retried.activeJobId) {
          if (!await waitForJob(retried.activeJobId, repositoryId, version)) return;
        }
      }
      if (!await drivePreparation(repositoryId, version)) return;
      await load(repositoryId);
      if (!isCurrent(repositoryId, version)) return;
      if (preparation.value?.state === 'READY') ElMessage.success('项目已准备完成');
      else if (preparation.value?.state === 'DEGRADED') ElMessage.warning('准备已完成，但存在可见的降级项');
      else if (preparation.value?.state === 'ACTION_REQUIRED') ElMessage.error(preparation.value.message);
    } catch (exception) {
      if (!isCurrent(repositoryId, version)) return;
      ElMessage.error(exception instanceof Error ? exception.message : '项目准备失败');
      await load(repositoryId);
    } finally {
      if (isCurrent(repositoryId, version)) preparing.value = false;
    }
  }

  async function drivePreparation(repositoryId: string, version: number) {
    for (let round = 0; round < 8; round += 1) {
      if (!isCurrent(repositoryId, version)) return false;
      const result = await prepareRepository(repositoryId);
      if (!isCurrent(repositoryId, version)) return false;
      preparation.value = result;
      if (!activeJob(result) || !result.activeJobId) return true;
      if (!await waitForJob(result.activeJobId, repositoryId, version)) return false;
    }
    throw new Error('准备流程超过预期阶段数，请刷新后查看具体失败阶段');
  }

  function activeJob(result: RepositoryPreparation) {
    return Boolean(result.activeJobId
      && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(result.activeJobStatus ?? ''));
  }

  async function waitForJob(jobId: string, repositoryId: string, version: number) {
    for (let attempt = 0; attempt < 240; attempt += 1) {
      if (!isCurrent(repositoryId, version)) return false;
      const job = await getIndexJob(jobId);
      if (!isCurrent(repositoryId, version)) return false;
      if (job.status === 'SUCCEEDED') return true;
      if (job.status === 'FAILED' || job.status === 'CANCELED') {
        throw new Error(job.errorMessage ?? '项目准备任务未完成');
      }
      await new Promise(resolve => window.setTimeout(resolve, 1500));
    }
    throw new Error('项目仍在后台准备，请稍后刷新项目总览');
  }

  watch(() => [repositories.selectedRepositoryId, branches.identity] as const, ([repositoryId]) => {
    contextVersion++;
    preparing.value = false;
    void load(repositoryId);
  }, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { clearTimeout(refreshTimer); disposed = true; contextVersion++; loadVersion++; });

  return {
    preparation: shallowReadonly(preparation), codeFacts: shallowReadonly(codeFacts),
    health: shallowReadonly(health), loading: shallowReadonly(loading),
    preparing: shallowReadonly(preparing), error: shallowReadonly(error),
    reload: () => load(repositories.selectedRepositoryId),
    prepare: () => runPreparation(),
    retryStage: (stage: PreparationStage['key']) => runPreparation(stage),
  };
}
