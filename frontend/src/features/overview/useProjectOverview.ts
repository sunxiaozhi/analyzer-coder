import { onScopeDispose, shallowReadonly, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  getBranchOverview,
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

  function isCurrent(repositoryId: string, version: number) {
    return !disposed && version === contextVersion && repositoryId === repositories.selectedRepositoryId;
  }

  async function load(repositoryId: string | null) {
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
      if (branches.context) {
        const pinned = branches.context;
        const result = await getBranchOverview(repositoryId, pinned.contextId);
        if (version !== loadVersion || !isCurrent(repositoryId, context)) return;
        if ([result.preparation, result.codeFacts, result.health].some(value =>
          value.contentVersion !== pinned.contentVersion || value.commitSha !== pinned.commitSha)) {
          throw new Error('分支总览版本不一致，请刷新阅读上下文');
        }
        preparation.value = result.preparation;
        codeFacts.value = result.codeFacts;
        health.value = result.health;
        return;
      }
      throw new Error(readScope.reason.value);
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
    const branchId = branches.selectedBranchId;
    if (!branchId) { ElMessage.warning('请在项目管理中选择分支'); return; }
    const version = contextVersion;
    preparing.value = true;
    try {
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
  }

  watch(() => [repositories.selectedRepositoryId, branches.identity] as const, ([repositoryId]) => {
    contextVersion++;
    preparing.value = false;
    void load(repositoryId);
  }, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { disposed = true; contextVersion++; loadVersion++; });

  return {
    preparation: shallowReadonly(preparation), codeFacts: shallowReadonly(codeFacts),
    health: shallowReadonly(health), loading: shallowReadonly(loading),
    preparing: shallowReadonly(preparing), error: shallowReadonly(error),
    reload: () => load(repositories.selectedRepositoryId),
    prepare: () => runPreparation(),
    retryStage: (stage: PreparationStage['key']) => runPreparation(stage),
  };
}
