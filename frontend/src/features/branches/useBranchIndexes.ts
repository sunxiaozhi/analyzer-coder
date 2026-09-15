import { onBeforeUnmount, shallowRef, watch } from 'vue';
import { branchesApi, type BranchIndexStatus } from '@/api/branches';

export function useBranchIndexes(repositoryId: () => string) {
  const statuses = shallowRef<BranchIndexStatus[]>([]);
  const error = shallowRef('');
  let version = 0;
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  async function refresh() {
    const current = ++version;
    clearTimeout(timer);
    try {
      const rows = await branchesApi.indexStatuses(repositoryId());
      if (stopped || current !== version) return;
      statuses.value = rows; error.value = '';
    } catch (cause) {
      if (!stopped && current === version) error.value = cause instanceof Error ? cause.message : '无法读取分支索引状态';
    } finally {
      if (!stopped && current === version) timer = setTimeout(refresh, 2500);
    }
  }
  watch(repositoryId, () => { statuses.value = []; void refresh(); }, { immediate: true });
  onBeforeUnmount(() => { stopped = true; ++version; clearTimeout(timer); });
  return { statuses, error, refresh };
}
