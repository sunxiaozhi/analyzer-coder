import { computed, shallowRef } from 'vue';
import { intelligenceApi, type Answer } from '@/api/intelligence';

export interface AskMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  answer?: Answer;
}

export function useAskConversation() {
  const question = shallowRef('');
  const busy = shallowRef(false);
  const messages = shallowRef<AskMessage[]>([]);
  const activeCitationId = shallowRef<string | null>(null);

  const latestAnswer = computed(() =>
    [...messages.value].reverse().find(message => message.answer)?.answer
  );
  const evidenceAnswer = computed(() => {
    if (!activeCitationId.value) return latestAnswer.value;
    return [...messages.value].reverse()
      .find(message => message.answer?.citations.some(
        citation => citation.id === activeCitationId.value
      ))?.answer ?? latestAnswer.value;
  });

  async function send(repositoryId: string, options?:{sessionId?:string;repositoryIds?:string[]}) {
    const value = question.value.trim();
    if (!value || busy.value) return;
    messages.value = [...messages.value, message('user', value)];
    question.value = '';
    busy.value = true;
    try {
      const answer = await intelligenceApi.ask(repositoryId, value, options);
      messages.value = [...messages.value, {
        ...message('assistant', answer.answer),
        answer,
      }];
      activeCitationId.value = answer.citations[0]?.id ?? null;
    } finally {
      busy.value = false;
    }
  }

  function clear() {
    messages.value = [];
    activeCitationId.value = null;
  }

  function selectCitation(id: string) {
    activeCitationId.value = id;
  }
  function restore(items:{id:string;role:'user'|'assistant';content:string;citations:string;conversationId:string|null;createdAt:string}[]){
    messages.value=items.map(item=>{let answer:Answer|undefined;if(item.role==='assistant'&&item.conversationId){try{answer={conversationId:item.conversationId,answer:item.content,snapshotId:null,citations:JSON.parse(item.citations||'[]'),provider:'history',evidenceStatus:'SUPPORTED',createdAt:item.createdAt}}catch{answer=undefined}}return{id:item.id,role:item.role,text:item.content,answer}});activeCitationId.value=null;
  }

  return {
    question,
    busy,
    messages,
    activeCitationId,
    evidenceAnswer,
    send,
    clear,
    selectCitation,
    restore,
  };
}

function message(role: AskMessage['role'], text: string): AskMessage {
  return { id: crypto.randomUUID(), role, text };
}
