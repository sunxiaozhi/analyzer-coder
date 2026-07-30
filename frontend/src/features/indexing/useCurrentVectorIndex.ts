import { computed, shallowRef, watch, type ComputedRef } from 'vue';
import {
  vectorIndexApi,
  type VectorIndexChunk,
  type VectorIndexKnowledge,
  type VectorIndexSummary,
  type VectorSource,
  type VectorStatus,
} from '@/api/vectorIndex';

export function useCurrentVectorIndex(repositoryId: ComputedRef<string | null>) {
  const summary = shallowRef<VectorIndexSummary | null>(null);
  const chunks = shallowRef<VectorIndexChunk[]>([]);
  const knowledge = shallowRef<VectorIndexKnowledge[]>([]);
  const source = shallowRef<VectorSource>('code');
  const query = shallowRef('');
  const appliedQuery = shallowRef('');
  const status = shallowRef<VectorStatus | ''>('');
  const chunkType = shallowRef('');
  const pageNum = shallowRef(1);
  const pageSize = shallowRef(15);
  const total = shallowRef(0);
  const loading = shallowRef(false);
  const error = shallowRef<string | null>(null);

  const items = computed(() => source.value === 'code' ? chunks.value : knowledge.value);

  async function loadSummary() {
    if (!repositoryId.value) {
      summary.value = null;
      return;
    }
    summary.value = await vectorIndexApi.summary(repositoryId.value);
  }

  async function loadItems() {
    if (!repositoryId.value) {
      chunks.value = [];
      knowledge.value = [];
      total.value = 0;
      return;
    }
    const params = {
      q: appliedQuery.value || undefined,
      status: status.value || undefined,
      chunkType: source.value === 'code' ? chunkType.value || undefined : undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    };
    const result = source.value === 'code'
      ? await vectorIndexApi.chunks(repositoryId.value, params)
      : await vectorIndexApi.knowledge(repositoryId.value, params);
    if (source.value === 'code') chunks.value = result.items as VectorIndexChunk[];
    else knowledge.value = result.items as VectorIndexKnowledge[];
    total.value = result.total;
  }

  async function refresh() {
    loading.value = true;
    error.value = null;
    try {
      await Promise.all([loadSummary(), loadItems()]);
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '加载当前向量索引失败';
    } finally {
      loading.value = false;
    }
  }

  async function search() {
    appliedQuery.value = query.value.trim();
    pageNum.value = 1;
    await refresh();
  }

  async function changePage(value: number) {
    pageNum.value = value;
    await refresh();
  }

  async function changePageSize(value: number) {
    pageSize.value = value;
    pageNum.value = 1;
    await refresh();
  }

  watch(repositoryId, () => {
    pageNum.value = 1;
    void refresh();
  }, { immediate: true });

  watch([source, status, chunkType], () => {
    pageNum.value = 1;
    void refresh();
  });

  return {
    summary,
    source,
    query,
    status,
    chunkType,
    pageNum,
    pageSize,
    total,
    loading,
    error,
    items,
    refresh,
    search,
    changePage,
    changePageSize,
  };
}
