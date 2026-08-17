import { computed, shallowRef } from 'vue';
import { intelligenceApi, type Answer, type QaThreadDetail } from '@/api/intelligence';

export type AskRequestState = 'idle' | 'sending' | 'failed' | 'succeeded';

export function useAskConversation() {
  const question = shallowRef('');
  const turns = shallowRef<Answer[]>([]);
  const activeAnswerId = shallowRef<string | null>(null);
  const activeCitationId = shallowRef<string | null>(null);
  const requestState = shallowRef<AskRequestState>('idle');
  const error = shallowRef<string | null>(null);
  const pendingQuestion = shallowRef('');
  const threadId = shallowRef<string | null>(null);
  let clientRequestId: string | null = null;
  let requestVersion = 0;

  const activeAnswer = computed(() =>
    turns.value.find(turn => turn.conversationId === activeAnswerId.value)
      ?? turns.value.at(-1)
      ?? null
  );

  async function send(repositoryId: string, retrying = false) {
    const value = (retrying ? pendingQuestion.value : question.value).trim();
    if (!value || requestState.value === 'sending') return null;
    const version = ++requestVersion;
    if (!retrying || !clientRequestId) clientRequestId = crypto.randomUUID();
    pendingQuestion.value = value;
    requestState.value = 'sending';
    error.value = null;
    try {
      const result = await intelligenceApi.ask(repositoryId, value, clientRequestId, threadId.value);
      if (version !== requestVersion) return null;
      threadId.value = result.threadId;
      turns.value = [...turns.value.filter(turn => turn.conversationId !== result.conversationId), result]
        .sort((left, right) => left.turnNo - right.turnNo);
      activeAnswerId.value = result.conversationId;
      activeCitationId.value = result.citations[0]?.id ?? null;
      if (question.value.trim() === value) question.value = '';
      pendingQuestion.value = '';
      clientRequestId = null;
      requestState.value = 'succeeded';
      return result;
    } catch (exception) {
      if (version !== requestVersion) return null;
      error.value = exception instanceof Error ? exception.message : '问答失败';
      requestState.value = 'failed';
      throw exception;
    }
  }

  function retry(repositoryId: string) {
    return send(repositoryId, true);
  }

  function restore(thread: QaThreadDetail) {
    requestVersion++;
    threadId.value = thread.threadId;
    turns.value = [...thread.turns].sort((left, right) => left.turnNo - right.turnNo);
    activeAnswerId.value = null;
    activeCitationId.value = null;
    question.value = '';
    pendingQuestion.value = '';
    error.value = null;
    clientRequestId = null;
    requestState.value = turns.value.length ? 'succeeded' : 'idle';
  }

  function selectAnswer(conversationId: string) {
    const answer = turns.value.find(turn => turn.conversationId === conversationId);
    if (!answer) return;
    activeAnswerId.value = conversationId;
    activeCitationId.value = answer.citations[0]?.id ?? null;
  }

  function reset() {
    requestVersion++;
    question.value = '';
    turns.value = [];
    activeAnswerId.value = null;
    activeCitationId.value = null;
    requestState.value = 'idle';
    error.value = null;
    pendingQuestion.value = '';
    threadId.value = null;
    clientRequestId = null;
  }

  return {
    question,
    turns,
    threadId,
    activeAnswer,
    activeAnswerId,
    activeCitationId,
    requestState,
    error,
    pendingQuestion,
    send,
    retry,
    restore,
    reset,
    invalidate: reset,
    selectAnswer,
    selectCitation: (id: string) => { activeCitationId.value = id; },
  };
}
