import { computed } from 'vue';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';

/** A Git project never falls back to the legacy/default-version endpoints. */
export function useBranchReadScope() {
  const repositories = useRepositoryStore();
  const branches = useBranchContextStore();
  const requiresContext = computed(() => ['LOCAL_GIT', 'REMOTE_GIT', 'GITLAB']
    .includes(repositories.selectedRepository?.sourceType ?? ''));
  const blocked = computed(() => requiresContext.value && (!branches.context
    || branches.context.repositoryId !== repositories.selectedRepositoryId
    || branches.context.branchId !== branches.selectedBranchId));
  const reason = computed(() => branches.error
    ?? (branches.loading ? '正在锁定当前分支快照，请稍候' : '当前分支快照未就绪，请在项目管理中同步该分支'));
  return { requiresContext, blocked, reason };
}
