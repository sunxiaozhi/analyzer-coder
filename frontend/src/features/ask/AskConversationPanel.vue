<script setup lang="ts">
import { Promotion, RefreshRight } from '@element-plus/icons-vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { computed, nextTick, onActivated, watch, useTemplateRef } from 'vue';
import type { Answer } from '@/api/intelligence';
import type { AskRequestState } from './useAskConversation';

const question = defineModel<string>({ required: true });
const props = defineProps<{
  answer: Answer | null;
  submittedQuestion: string;
  requestState: AskRequestState;
  error: string | null;
  activeCitationId: string | null;
  disabled?: boolean;
}>();
const emit = defineEmits<{ send: []; retry: []; selectCitation: [id: string] }>();
const messagesElement = useTemplateRef<HTMLElement>('messages');
const renderedAnswer = computed(() => DOMPurify.sanitize(
  marked.parse(props.answer?.answer ?? '', { async: false, gfm: true }),
  { USE_PROFILES: { html: true } },
));

async function scrollToLatest() {
  await nextTick();
  if (messagesElement.value) messagesElement.value.scrollTop = messagesElement.value.scrollHeight;
}

watch(() => [props.answer?.conversationId, props.requestState], scrollToLatest);
onActivated(scrollToLatest);

function statusLabel(status: Answer['evidenceStatus']) {
  return ({
    SUPPORTED: '引用校验通过',
    DEGRADED: '本地证据模式',
    MODEL_OUTPUT_REJECTED: '模型回答已拦截',
    INSUFFICIENT: '证据不足',
  })[status];
}

function shortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault();
    emit('send');
  }
}
</script>

<template>
  <main class="chat-column">
    <div ref="messages" class="messages" aria-live="polite">
      <el-empty
        v-if="!submittedQuestion && !answer && requestState === 'idle'"
        description="可以询问业务知识、实现位置或代码影响"
      />

      <article v-if="submittedQuestion" class="message user">
        <span>你</span>
        <p>{{ submittedQuestion }}</p>
      </article>

      <article v-if="requestState === 'sending'" class="message assistant pending">
        <span>AI</span>
        <div class="answer-loading">
          <i></i>
          <div><b>正在检索当前仓库</b><small>正在核对代码和知识证据…</small></div>
        </div>
      </article>

      <article v-if="requestState === 'failed'" class="message assistant failed">
        <span>AI</span>
        <el-alert type="error" :closable="false" show-icon>
          <template #title>本次提问未完成</template>
          <p>{{ error }}</p>
          <el-button :icon="RefreshRight" @click="emit('retry')">重新发送</el-button>
        </el-alert>
      </article>

      <article v-if="answer" class="message assistant">
        <span>AI</span>
        <div class="assistant-content">
          <div class="answer-markdown" v-html="renderedAnswer"></div>
          <div class="answer-trust">
            <strong :data-status="answer.evidenceStatus">{{ statusLabel(answer.evidenceStatus) }}</strong>
            <small>{{ answer.provider }} · {{ new Date(answer.createdAt).toLocaleString() }}</small>
          </div>
          <div v-if="answer.citations.length" class="citations">
            <button
              v-for="citation in answer.citations"
              :key="citation.id"
              :class="{ active: citation.id === activeCitationId }"
              @click="emit('selectCitation', citation.id)"
            >
              <span>[S{{ citation.rank }}] {{ citation.sourceType === 'KNOWLEDGE' ? '知识库' : '关联代码' }}</span>
              <b>{{ citation.title }}</b>
              <small>{{ citation.channels.join(' + ') }} · {{ citation.score.toFixed(2) }}</small>
            </button>
          </div>
        </div>
      </article>
    </div>

    <div class="composer">
      <el-input
        v-model="question"
        type="textarea"
        :autosize="{ minRows: 4, maxRows: 8 }"
        :disabled="disabled"
        placeholder="询问业务规则、实现位置、调用关系或影响范围…"
        @keydown="shortcut"
      />
      <el-button
        type="primary"
        :icon="Promotion"
        :loading="requestState === 'sending'"
        :disabled="disabled || !question.trim()"
        aria-label="发送问题"
        circle
        @click="emit('send')"
      />
    </div>
  </main>
</template>

<style scoped>
.chat-column {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid #dedee3;
  border-radius: 7px 0 0 7px;
  background: #fff;
}
.messages {
  min-height: 0;
  padding: 22px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.message {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 10px;
  margin-bottom: 22px;
}
.message > span {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 6px;
  background: #eee;
  font-size: 10px;
  font-weight: 700;
}
.message.user {
  display: flex;
  justify-content: flex-end;
}
.message.user > span { display: none; }
.message.user > p {
  max-width: min(78%, 780px);
  margin: 0;
  padding: 11px 16px;
  color: #fff;
  border-radius: 18px 18px 3px 18px;
  background: #0066cc;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.message.assistant { grid-template-columns: minmax(0, 1fr); }
.message.assistant > span { display: none; }
.assistant-content { display: grid; min-width: 0; gap: 12px; }
.answer-markdown { color: #34383e; font-size: 14px; line-height: 1.75; overflow-wrap: anywhere; }
.answer-markdown :deep(p) { margin: 3px 0 14px; }
.answer-markdown :deep(p:last-child) { margin-bottom: 0; }
.answer-markdown :deep(h1),
.answer-markdown :deep(h2),
.answer-markdown :deep(h3) { margin: 22px 0 9px; color: #25292e; line-height: 1.4; }
.answer-markdown :deep(h1) { font-size: 20px; }
.answer-markdown :deep(h2) { font-size: 17px; }
.answer-markdown :deep(h3) { font-size: 15px; }
.answer-markdown :deep(ul),
.answer-markdown :deep(ol) { padding-left: 23px; }
.answer-markdown :deep(code) { padding: 2px 5px; color: #a52d20; border: 1px solid #e1e4e8; border-radius: 4px; background: #f4f6f8; font: 12px Consolas, monospace; }
.answer-markdown :deep(pre) { padding: 15px; overflow: auto; color: #e8edf3; border-radius: 6px; background: #20242a; }
.answer-markdown :deep(pre code) { padding: 0; color: inherit; border: 0; background: transparent; }
.answer-trust { display: flex; gap: 8px; align-items: center; color: #66717c; font-size: 9px; }
.answer-trust strong { padding: 3px 7px; color: #1e6b44; border-radius: 4px; background: #e9f7ef; }
.answer-trust strong[data-status="MODEL_OUTPUT_REJECTED"],
.answer-trust strong[data-status="INSUFFICIENT"] { color: #9a4c22; background: #fff0e8; }
.citations { display: grid; gap: 7px; width: min(100%, 680px); }
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
.citations button.active { border-color: #9fc3e5; background: #eef6fd; box-shadow: inset 3px 0 #0066cc; }
.citations button span { color: #0066cc; font-size: 9px; font-weight: 650; }
.citations button b { overflow-wrap: anywhere; color: #34404b; font-size: 10px; }
.citations button small { grid-column: 2; color: #78838d; font: 8px "SFMono-Regular", Consolas, monospace; }
.answer-loading { display: flex; gap: 10px; align-items: center; padding: 4px 0; }
.answer-loading i { width: 13px; height: 13px; border: 2px solid #b9d2e8; border-top-color: #0066cc; border-radius: 50%; animation: spin .8s linear infinite; }
.answer-loading div { display: grid; gap: 3px; }
.answer-loading b { font-size: 12px; }
.answer-loading small { color: #77818a; font-size: 10px; }
.message.failed :deep(.el-alert) { width: min(100%, 680px); }
.composer { display: flex; align-items: flex-end; gap: 10px; padding: 12px; border-top: 1px solid #ececef; background: #fff; }
.composer .el-button { flex: none; margin-bottom: 4px; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .answer-loading i { animation: none; } }
@media (max-width: 760px) {
  .chat-column { min-height: 620px; border-radius: 7px; }
  .messages { max-height: 65vh; padding: 16px; }
  .message.user > p { max-width: 90%; }
}
</style>
