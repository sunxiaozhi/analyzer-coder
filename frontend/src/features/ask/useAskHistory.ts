import { onScopeDispose, shallowRef } from 'vue';
import { intelligenceApi, type QaHistoryRecord } from '@/api/intelligence';

/** A page belongs to one pinned project/branch; late responses must not cross that scope. */
export function useAskHistory(scope: () => { repositoryId: string; contextId?: string | null } | null) {
  const records = shallowRef<QaHistoryRecord[]>([]);
  const loading = shallowRef(false), loadingMore = shallowRef(false);
  const hasMore = shallowRef(false), error = shallowRef('');
  const pageSize = 50;
  let revision = 0, offset = 0;

  function reset() {
    revision++; offset = 0;
    records.value = []; hasMore.value = false; error.value = '';
    loading.value = false; loadingMore.value = false;
  }

  async function load(more = false) {
    if (more && (loading.value || loadingMore.value || !hasMore.value)) return;
    const pinned = scope();
    if (!pinned) { reset(); return; }
    const ticket = ++revision;
    if (!more) { offset = 0; records.value = []; hasMore.value = false; }
    error.value = '';
    loading.value = !more; loadingMore.value = more;
    const isCurrent = () => ticket === revision && scope()?.repositoryId === pinned.repositoryId
      && scope()?.contextId === pinned.contextId;
    try {
      const page = await intelligenceApi.history(pinned.repositoryId, pageSize, offset, pinned.contextId);
      if (!isCurrent()) return;
      const combined = new Map((more ? records.value : []).map(record => [record.threadId, record]));
      page.forEach(record => { if (!combined.has(record.threadId)) combined.set(record.threadId, record); });
      records.value = [...combined.values()];
      offset += page.length;
      hasMore.value = page.length === pageSize;
    } catch (cause) {
      if (isCurrent()) error.value = cause instanceof Error ? cause.message : '历史记录加载失败';
    } finally {
      if (ticket === revision) { loading.value = false; loadingMore.value = false; }
    }
  }

  onScopeDispose(reset);
  return { records, loading, loadingMore, hasMore, error, reset, refresh: () => load(), loadMore: () => load(true) };
}
