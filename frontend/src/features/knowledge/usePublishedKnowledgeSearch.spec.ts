import { effectScope, nextTick, shallowRef } from 'vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { intelligenceApi, type UnifiedSearchResponse } from '@/api/intelligence';
import type { BranchContext } from '@/api/branches';
import { usePublishedKnowledgeSearch } from './usePublishedKnowledgeSearch';
vi.mock('@/api/intelligence', () => ({ intelligenceApi: { unifiedSearch: vi.fn() } }));
const context = (id = 'project'): BranchContext => ({ repositoryId: id, contextId: `ctx-${id}`, branchId: 'workspace', branchName: 'WORKSPACE', contentVersion: null, commitSha: null, expiresAt: '' });
const response = (id: string, degraded = false) => ({ evidence: [{ sourceType: 'KNOWLEDGE', knowledgeCardId: id }], retrieval: { degraded } } as UnifiedSearchResponse);
beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers(); });
afterEach(() => vi.useRealTimers());

it('debounces keywords and searches through the current explicit knowledge context', async () => {
  const query = shallowRef('退');
  const scope = effectScope();
  vi.mocked(intelligenceApi.unifiedSearch).mockResolvedValue(response('rule'));
  const search = scope.run(() => usePublishedKnowledgeSearch({ query, context: context() }))!;
  query.value = '退款期限'; await nextTick();
  await vi.advanceTimersByTimeAsync(250);
  expect(intelligenceApi.unifiedSearch).toHaveBeenCalledExactlyOnceWith('project', '退款期限', 50, 'ctx-project');
  expect(search.hits.value[0].knowledgeCardId).toBe('rule');
  expect(search.loading.value).toBe(false);
  scope.stop();
});

it('discards an old project response after switching contexts', async () => {
  let resolve!: (value: UnifiedSearchResponse) => void;
  vi.mocked(intelligenceApi.unifiedSearch).mockImplementationOnce(() => new Promise(done => { resolve = done; }));
  const current = shallowRef<BranchContext | null>(context());
  const scope = effectScope();
  const search = scope.run(() => usePublishedKnowledgeSearch({ query: '退款', context: current }))!;
  await vi.advanceTimersByTimeAsync(250);
  current.value = null; await nextTick();
  resolve(response('old-project-card'));
  await vi.advanceTimersByTimeAsync(0);
  expect(search.hits.value).toEqual([]);
  expect(search.loading.value).toBe(false);
  scope.stop();
});

it('searches within the selected knowledge kind and invalidates results when the kind changes', async () => {
  const kind = shallowRef<string | undefined>('RUNBOOK');
  vi.mocked(intelligenceApi.unifiedSearch).mockResolvedValue(response('runbook'));
  const scope = effectScope();
  const search = scope.run(() => usePublishedKnowledgeSearch({ query: '退款', context: context(), knowledgeKind: kind }))!;
  await vi.advanceTimersByTimeAsync(250);
  expect(intelligenceApi.unifiedSearch).toHaveBeenLastCalledWith('project', '退款', 50, 'ctx-project', 'RUNBOOK');
  kind.value = 'BUSINESS_RULE'; await nextTick();
  expect(search.hits.value).toEqual([]);
  await vi.advanceTimersByTimeAsync(250);
  expect(intelligenceApi.unifiedSearch).toHaveBeenLastCalledWith('project', '退款', 50, 'ctx-project', 'BUSINESS_RULE');
  scope.stop();
});

it('reports failures, supports retry and preserves degraded results', async () => {
  vi.mocked(intelligenceApi.unifiedSearch).mockRejectedValueOnce(new Error('检索暂不可用')).mockResolvedValue(response('rule', true));
  const scope = effectScope();
  const search = scope.run(() => usePublishedKnowledgeSearch({ query: '退款', context: context() }))!;
  await vi.advanceTimersByTimeAsync(250);
  expect(search.error.value).toBe('检索暂不可用');
  search.retry(); await nextTick(); await vi.advanceTimersByTimeAsync(250);
  expect(search.error.value).toBe('');
  expect(search.hits.value[0].knowledgeCardId).toBe('rule');
  expect(search.degraded.value).toBe(true);
  scope.stop();
});

it('clears results and cancels pending work when keywords are cleared or the page is closed', async () => {
  vi.mocked(intelligenceApi.unifiedSearch).mockResolvedValue(response('rule'));
  const query = shallowRef('退款');
  const scope = effectScope();
  const search = scope.run(() => usePublishedKnowledgeSearch({ query, context: context() }))!;
  await vi.advanceTimersByTimeAsync(250);
  query.value = ''; await nextTick();
  expect(search.hits.value).toEqual([]);
  query.value = '新搜索'; await nextTick();
  scope.stop(); await vi.advanceTimersByTimeAsync(250);
  expect(intelligenceApi.unifiedSearch).toHaveBeenCalledTimes(1);
});
