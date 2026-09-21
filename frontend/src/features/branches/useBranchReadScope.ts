import { computed } from 'vue';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';

/** Every project is read through an explicit branch, including ZIP's WORKSPACE branch. */
export function useBranchReadScope() {
  const repositories = useRepositoryStore();
  const branches = useBranchContextStore();
  const requiresContext = computed(() => Boolean(repositories.selectedRepositoryId));
  const blocked = computed(() => requiresContext.value && (!branches.context
    || branches.context.repositoryId !== repositories.selectedRepositoryId
    || branches.context.branchId !== branches.selectedBranchId));
  const reason = computed(() => branches.error
    ?? (branches.loading ? '正在锁定当前分支版本，请稍候' : '当前分支版本未就绪，请在项目管理中同步该分支'));
  return { requiresContext, blocked, reason };
}
