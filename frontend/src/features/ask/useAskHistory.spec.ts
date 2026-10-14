import { effectScope } from 'vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { intelligenceApi, type QaHistoryRecord } from '@/api/intelligence';
import { useAskHistory } from './useAskHistory';
vi.mock('@/api/intelligence', () => ({ intelligenceApi: { history: vi.fn() } }));
const page = (start: number, count: number) => Array.from({ length: count }, (_, index) => ({ threadId: String(start + index) } as QaHistoryRecord));
beforeEach(() => vi.resetAllMocks());
afterEach(() => vi.restoreAllMocks());

it('loads a 51st conversation and retries a failed next page without skipping it', async () => {
  vi.mocked(intelligenceApi.history).mockResolvedValueOnce(page(0, 50)).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce(page(50, 1));
  const scope = effectScope();
  const state = scope.run(() => useAskHistory(() => ({ repositoryId: 'p', contextId: 'ctx' })))!;
  await state.refresh(); await state.loadMore();
  expect(state.records.value).toHaveLength(50);
  expect(state.error.value).toBe('网络中断');
  await state.loadMore();
  expect(intelligenceApi.history).toHaveBeenLastCalledWith('p', 50, 50, 'ctx');
  expect(state.records.value.at(-1)?.threadId).toBe('50');
  expect(state.hasMore.value).toBe(false);
  scope.stop();
});

it('does not merge a late previous-branch page after refreshing the history scope', async () => {
  let resolve!: (records: QaHistoryRecord[]) => void;
  let pinned = { repositoryId: 'p', contextId: 'old' };
  vi.mocked(intelligenceApi.history).mockResolvedValueOnce(page(0, 50)).mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValueOnce(page(100, 1));
  const scope = effectScope();
  const state = scope.run(() => useAskHistory(() => pinned))!;
  await state.refresh(); const pending = state.loadMore();
  pinned = { repositoryId: 'p', contextId: 'new' }; state.reset(); await state.refresh();
  resolve(page(50, 50)); await pending;
  expect(state.records.value.map(record => record.threadId)).toEqual(['100']);
  scope.stop();
});
