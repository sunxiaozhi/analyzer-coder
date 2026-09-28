import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './http';
import { intelligenceApi, type CardInput } from './intelligence';
import { listChunks } from './repositories';
vi.mock('./http', () => ({ request: vi.fn() }));

describe('knowledge branch workflow', () => {
  beforeEach(() => vi.mocked(request).mockReset());
  it('keeps code selection and card mutations on the selected branch', async () => {
    await listChunks('repo', { q: 'RefundService', limit: 20 }, 'legacy-context');
    await intelligenceApi.updateCard('repo', 'card', { title: '退款规则' } as CardInput, 'legacy-context');
    await intelligenceApi.reviewCard('repo', 'card', 'APPROVED', 'legacy-context');
    await intelligenceApi.setCardPublication('repo', 'card', 'DRAFT', 'legacy-context');
    await intelligenceApi.reviewKnowledgeSource('repo', 'card', 'CONFIRM_CURRENT', 2, '已核对', 'legacy-context');
    expect(request).toHaveBeenCalledTimes(5);
    for (const [, options] of vi.mocked(request).mock.calls) {
      expect(new Headers(options?.headers).get('X-Branch-Context')).toBe('legacy-context');
    }
  });
  it('publishes the exact revision with one request', async () => {
    await intelligenceApi.publishCard('repo', 'card', 7, 'legacy-context');
    expect(request).toHaveBeenCalledOnce();
    expect(request).toHaveBeenCalledWith('/api/repositories/repo/knowledge/card/publish', {
      method: 'POST', headers: { 'X-Branch-Context': 'legacy-context' },
      body: JSON.stringify({ expectedRevision: 7 }),
    });
  });
});
