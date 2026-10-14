import { shallowRef, watch, type MaybeRefOrGetter, toValue } from 'vue';
import { intelligenceApi, type UnifiedSearchHit } from '@/api/intelligence';
import type { BranchContext } from '@/api/branches';

export function usePublishedKnowledgeSearch(options: {
  query: MaybeRefOrGetter<string>;
  context: MaybeRefOrGetter<BranchContext | null>;
  knowledgeKind?: MaybeRefOrGetter<string | undefined>;
}) {
  const hits = shallowRef<UnifiedSearchHit[]>([]);
  const loading = shallowRef(false);
  const error = shallowRef('');
  const degraded = shallowRef(false);
  let version = 0;
  const refreshKey = shallowRef(0);
  watch(() => [toValue(options.query).trim(), toValue(options.context), toValue(options.knowledgeKind), refreshKey.value] as const,
    ([query, context, kind], _, onCleanup) => {
      const request = ++version;
      hits.value = []; error.value = ''; degraded.value = false;
      loading.value = Boolean(query && context);
      if (!query || !context) return;
      const timer = setTimeout(async () => {
        try {
          const result = kind
            ? await intelligenceApi.unifiedSearch(context.repositoryId, query, 50, context.contextId, kind)
            : await intelligenceApi.unifiedSearch(context.repositoryId, query, 50, context.contextId);
          if (request !== version) return;
          hits.value = result.evidence.filter(hit => hit.sourceType === 'KNOWLEDGE' && hit.knowledgeCardId);
          degraded.value = result.retrieval.degraded;
        } catch (cause) {
          if (request === version) error.value = cause instanceof Error ? cause.message : '知识检索失败';
        } finally { if (request === version) loading.value = false; }
      }, 250);
      onCleanup(() => { clearTimeout(timer); version++; });
    }, { immediate: true });
  return { hits, loading, error, degraded, retry: () => { refreshKey.value++; } };
}
