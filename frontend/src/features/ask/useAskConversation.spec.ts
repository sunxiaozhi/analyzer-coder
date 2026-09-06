import { beforeEach, describe, expect, it, vi } from 'vitest';
import { intelligenceApi, type Answer } from '@/api/intelligence';
import { useAskConversation } from './useAskConversation';

vi.mock('@/api/intelligence', () => ({ intelligenceApi: { ask: vi.fn() } }));

describe('local evidence conversation', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends without a model and preserves the returned conversation and citations', async () => {
    const result = {
      repositoryId: 'repo-1', conversationId: 'answer-1', threadId: 'thread-1', turnNo: 1,
      citations: [{ id: 'source-1' }], fallbackReason: 'LOCAL_EVIDENCE_MODE',
    } as Answer;
    vi.mocked(intelligenceApi.ask).mockResolvedValue(result);
    const conversation = useAskConversation();
    conversation.question.value = 'OrderCheckoutWorkflow 在哪里定义？';

    await conversation.send('repo-1', null);

    expect(intelligenceApi.ask).toHaveBeenCalledWith(
      'repo-1', 'OrderCheckoutWorkflow 在哪里定义？', expect.any(String), null, null,
    );
    expect(conversation.threadId.value).toBe('thread-1');
    expect(conversation.turns.value).toEqual([result]);
    expect(conversation.question.value).toBe('');
  });

  it('retries local evidence requests using the original idempotency key', async () => {
    vi.mocked(intelligenceApi.ask).mockRejectedValueOnce(new Error('network unavailable'));
    const conversation = useAskConversation();
    conversation.question.value = '定位入口';
    await expect(conversation.send('repo-1', null)).rejects.toThrow('network unavailable');
    vi.mocked(intelligenceApi.ask).mockResolvedValue({
      repositoryId: 'repo-1', conversationId: 'answer-1', threadId: 'thread-1', turnNo: 1, citations: [],
    } as unknown as Answer);

    await conversation.retry('repo-1', null);

    expect(vi.mocked(intelligenceApi.ask).mock.calls[1]).toEqual(vi.mocked(intelligenceApi.ask).mock.calls[0]);
    expect(conversation.requestState.value).toBe('succeeded');
  });
});
