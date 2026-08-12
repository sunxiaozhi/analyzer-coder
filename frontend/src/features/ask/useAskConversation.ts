import { shallowRef } from 'vue';
import { intelligenceApi, type Answer } from '@/api/intelligence';

export type AskRequestState = 'idle' | 'sending' | 'failed' | 'succeeded';

export function useAskConversation() {
  const question = shallowRef('');
  const submittedQuestion = shallowRef('');
  const answer = shallowRef<Answer | null>(null);
  const activeCitationId = shallowRef<string | null>(null);
  const requestState = shallowRef<AskRequestState>('idle');
  const error = shallowRef<string | null>(null);
  let clientRequestId: string | null = null;
  let requestVersion = 0;

  async function send(repositoryId: string, retrying = false) {
    const value = question.value.trim();
    if (!value || requestState.value === 'sending') return null;
    const version = ++requestVersion;
    if (!retrying || !clientRequestId) clientRequestId = crypto.randomUUID();
    submittedQuestion.value = value;
    requestState.value = 'sending';
    error.value = null;
    try {
      const result = await intelligenceApi.ask(repositoryId, value, clientRequestId);
      if (version !== requestVersion) return null;
      answer.value = result;
      if (question.value.trim() === value) question.value = '';
      activeCitationId.value = result.citations[0]?.id ?? null;
      requestState.value = 'succeeded';
      return result;
    } catch (exception) {
      if (version !== requestVersion) return null;
      error.value = exception instanceof Error ? exception.message : '问答失败';
      requestState.value = 'failed';
      throw exception;
    }
  }

  async function retry(repositoryId: string) {
    if (submittedQuestion.value) question.value = submittedQuestion.value;
    return send(repositoryId, true);
  }

  function restore(result: Answer) {
    requestVersion++;
    answer.value = result;
    submittedQuestion.value = result.question;
    question.value = '';
    error.value = null;
    activeCitationId.value = result.citations[0]?.id ?? null;
    clientRequestId = null;
    requestState.value = 'succeeded';
  }

  function reset() {
    requestVersion++;
    question.value = '';
    submittedQuestion.value = '';
    answer.value = null;
    activeCitationId.value = null;
    requestState.value = 'idle';
    error.value = null;
    clientRequestId = null;
  }

  function invalidate() {
    reset();
  }

  return {
    question,
    submittedQuestion,
    answer,
    activeCitationId,
    requestState,
    error,
    send,
    retry,
    restore,
    reset,
    invalidate,
    selectCitation: (id: string) => { activeCitationId.value = id; },
  };
}
