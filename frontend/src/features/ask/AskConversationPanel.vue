<script setup lang="ts">
import { Promotion } from '@element-plus/icons-vue';
import { nextTick, onActivated, watch, useTemplateRef } from 'vue';
import type { AskMessage } from './useAskConversation';

const question = defineModel<string>({ required: true });
const props = defineProps<{
  messages: AskMessage[];
  busy: boolean;
  activeCitationId: string | null;
}>();
const emit = defineEmits<{
  send: [];
  clear: [];
  selectCitation: [id: string];
}>();
const messagesElement = useTemplateRef<HTMLElement>('messages');

async function scrollToLatest() {
  await nextTick();
  if (messagesElement.value) {
    messagesElement.value.scrollTop = messagesElement.value.scrollHeight;
  }
}

watch(
  () => props.messages.length,
  scrollToLatest
);

onActivated(scrollToLatest);

function statusLabel(status: string) {
  return {
    SUPPORTED: '引用校验通过',
    DEGRADED: '本地证据模式',
    MODEL_OUTPUT_REJECTED: '模型回答已拦截',
    INSUFFICIENT: '证据不足',
  }[status] ?? status;
}
</script>

<template>
  <main class="chat-column">
    <div class="chat-head">
      <div><b>知识与代码问答</b><span>多路召回、相关度过滤与引用校验</span></div>
      <el-button @click="emit('clear')">清空会话</el-button>
    </div>
    <div ref="messages" class="messages">
      <el-empty v-if="!messages.length" description="可以询问业务知识、实现位置或代码影响" />
      <article v-for="message in messages" :key="message.id" :class="['message', message.role]">
        <span>{{ message.role === 'user' ? '你' : 'AI' }}</span>
        <p class="answer-copy">{{ message.text }}</p>
        <div v-if="message.answer" class="answer-trust">
          <strong :data-status="message.answer.evidenceStatus">
            {{ statusLabel(message.answer.evidenceStatus) }}
          </strong>
          <small>{{ message.answer.provider }}</small>
        </div>
        <div v-if="message.answer" class="citations">
          <button
            v-for="citation in message.answer.citations"
            :key="citation.id"
            :class="{ active: citation.id === activeCitationId }"
            @click="emit('selectCitation', citation.id)"
          >
            <span>[S{{ citation.rank }}] {{ citation.sourceType === 'KNOWLEDGE' ? '知识库' : '关联代码' }}</span>
            <b>{{ citation.title }}</b>
            <small>{{ citation.channels.join(' + ') }} · {{ citation.score.toFixed(2) }}</small>
          </button>
        </div>
      </article>
    </div>
    <div class="composer">
      <el-input
        v-model="question"
        type="textarea"
        :autosize="{ minRows: 4, maxRows: 8 }"
        placeholder="询问业务规则、实现位置、调用关系或影响范围…"
        @keydown.ctrl.enter="emit('send')"
      />
      <el-button type="primary" :icon="Promotion" :loading="busy" circle @click="emit('send')" />
    </div>
  </main>
</template>

<style scoped>
.chat-column { min-height: 0; overflow: hidden; }
.messages {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.answer-copy { white-space: pre-line; overflow-wrap: anywhere; }
.answer-trust {
  display: flex;
  gap: 8px;
  align-items: center;
  color: #66717c;
  font-size: 9px;
}
.answer-trust strong {
  padding: 3px 7px;
  color: #1e6b44;
  border-radius: 4px;
  background: #e9f7ef;
}
.answer-trust strong[data-status="MODEL_OUTPUT_REJECTED"],
.answer-trust strong[data-status="INSUFFICIENT"] {
  color: #9a4c22;
  background: #fff0e8;
}
.citations {
  display: grid;
  gap: 7px;
  width: min(100%, 680px);
}
.citations button {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 3px 10px;
  align-items: baseline;
  width: 100%;
  padding: 9px 11px;
  text-align: left;
  border: 1px solid #dbe6f0;
  background: #f7fafd;
}
.citations button:hover,
.citations button.active {
  border-color: #9fc3e5;
  background: #eef6fd;
  box-shadow: inset 3px 0 #0066cc;
}
.citations button span { color: #0066cc; font-size: 9px; font-weight: 650; }
.citations button b { overflow-wrap: anywhere; color: #34404b; font-size: 10px; }
.citations button small {
  grid-column: 2;
  color: #78838d;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 8px;
}
@media (max-width: 760px) {
  .chat-column { min-height: calc(100vh - 116px); }
  .messages { max-height: 65vh; }
}
</style>
