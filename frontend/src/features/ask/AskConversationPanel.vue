<script setup lang="ts">
import { Promotion, RefreshRight } from '@element-plus/icons-vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { nextTick, onActivated, watch, useTemplateRef } from 'vue';
import type { Answer, Citation, CodeReference } from '@/api/intelligence';
import AnswerEvidencePanel from './AnswerEvidencePanel.vue';
import type { AskRequestState } from './useAskConversation';

const question = defineModel<string>({ required: true });
const props = defineProps<{
  turns: readonly Answer[];
  activeAnswerId: string | null;
  pendingQuestion: string;
  requestState: AskRequestState;
  error: string | null;
  disabled?: boolean;
  restoredThreadId?: string | null;
}>();
const emit = defineEmits<{
  send: [];
  retry: [];
  selectAnswer: [conversationId: string];
  openKnowledge: [citation: Citation];
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
}>();
const messagesElement = useTemplateRef<HTMLElement>('messages');

function renderedAnswer(answer: string) {
  return DOMPurify.sanitize(marked.parse(answer, { async: false, gfm: true }), {
    USE_PROFILES: { html: true },
  });
}

async function scrollToLatest() {
  await nextTick();
  if (messagesElement.value) messagesElement.value.scrollTop = messagesElement.value.scrollHeight;
}

watch(() => props.restoredThreadId, async (threadId, previousThreadId) => {
  if (!threadId || threadId === previousThreadId) return;
  await nextTick();
  if (messagesElement.value) messagesElement.value.scrollTop = 0;
});
watch(() => [props.turns.length, props.requestState], scrollToLatest);
onActivated(scrollToLatest);

function statusLabel(status: Answer['evidenceStatus']) {
  return ({
    CITATION_COMPLETE: '引用覆盖完整',
    CITATION_INCOMPLETE: '引用覆盖不完整',
    SUPPORTED: '历史引用格式已校验',
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
        v-if="!turns.length && !pendingQuestion && requestState === 'idle'"
        description="可以询问业务知识、实现位置或代码影响"
      />

      <template v-for="turn in turns" :key="turn.conversationId">
        <article class="message user">
          <span>你</span>
          <p>{{ turn.question }}</p>
        </article>
        <article
          :class="['message', 'assistant', { selected: turn.conversationId === activeAnswerId }]"
          @click="emit('selectAnswer', turn.conversationId)"
        >
          <span>AI</span>
          <div class="assistant-content">
            <div class="answer-markdown" v-html="renderedAnswer(turn.answer)"></div>
            <div class="answer-trust">
              <strong :data-status="turn.evidenceStatus">{{ statusLabel(turn.evidenceStatus) }}</strong>
              <em v-if="turn.conversationId === activeAnswerId" class="current-turn">当前轮次</em>
              <small>第 {{ turn.turnNo }} 轮 · {{ turn.provider }} · {{ new Date(turn.createdAt).toLocaleString() }}</small>
            </div>
            <div
              v-if="turn.citationAssessment && (turn.citationAssessment.factualBlockCount || turn.citationAssessment.invalidReferences.length)"
              class="citation-assessment"
            >
              <span>
                引用覆盖 {{ Math.round(turn.citationAssessment.coverageRate * 100) }}%
                （{{ turn.citationAssessment.citedBlockCount }}/{{ turn.citationAssessment.factualBlockCount }} 段）
              </span>
              <span v-if="turn.citationAssessment.uncitedBlockCount">
                {{ turn.citationAssessment.uncitedBlockCount }} 个事实段未引用
              </span>
              <span v-if="turn.citationAssessment.invalidReferences.length">
                非法引用：{{ turn.citationAssessment.invalidReferences.join('、') }}
              </span>
              <small>仅检查引用编号与段落覆盖，未验证证据是否在语义上支持回答。</small>
            </div>
            <details class="retrieval-diagnostics">
              <summary>
                检索 {{ turn.retrieval.recalledCount }} 条 · {{ turn.retrieval.durationMs }} ms
                <span v-if="turn.retrieval.degraded">· 已降级</span>
              </summary>
              <div>
                <span>快照 {{ turn.retrieval.snapshotId?.slice(0, 8) ?? '不可用' }}</span>
                <span>向量模型 {{ turn.retrieval.vectorModel ?? '未执行' }}</span>
                <span>启用通道 {{ turn.retrieval.enabledChannels.join('、') || '无' }}</span>
              </div>
              <ul v-if="turn.retrieval.unavailableChannels.length">
                <li v-for="item in turn.retrieval.unavailableChannels" :key="`${item.channel}:${item.reason}`">
                  {{ item.channel }} 不可用：{{ item.reason }}（{{ item.detail }}）
                </li>
              </ul>
            </details>
            <AnswerEvidencePanel v-if="turn.citations.length" :citations="turn.citations" @click.stop
              @open-knowledge="emit('openKnowledge', $event)" @open-code="emit('openCode', $event)"
              @open-graph="emit('openGraph', $event)" />
          </div>
        </article>
      </template>

      <article v-if="requestState === 'sending' && pendingQuestion" class="message user">
        <span>你</span><p>{{ pendingQuestion }}</p>
      </article>
      <article v-if="requestState === 'sending'" class="message assistant pending">
        <span>AI</span>
        <div class="answer-loading">
          <i></i><div><b>正在结合上下文检索当前仓库</b><small>正在核对代码和知识证据…</small></div>
        </div>
      </article>
      <article v-if="requestState === 'failed'" class="message assistant failed">
        <span>AI</span>
        <el-alert type="error" :closable="false" show-icon>
          <template #title>本轮提问未完成</template>
          <p>{{ error }}</p>
          <el-button :icon="RefreshRight" @click="emit('retry')">重新发送</el-button>
        </el-alert>
      </article>
    </div>

    <div class="composer">
      <el-input
        v-model="question"
        type="textarea"
        :autosize="{ minRows: 4, maxRows: 8 }"
        :disabled="disabled"
        :placeholder="turns.length ? '继续追问当前会话…' : '询问业务规则、实现位置、调用关系或影响范围…'"
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
.chat-column { display:grid; grid-template-rows:minmax(0,1fr) auto; min-width:0; min-height:0; overflow:hidden; border:1px solid #dedee3; border-radius:7px; background:#fff; }
.messages { min-height:0; padding:22px; overflow-x:hidden; overflow-y:auto; overscroll-behavior:contain; scrollbar-gutter:stable; }
.message { display:grid; grid-template-columns:26px minmax(0,1fr); gap:10px; margin-bottom:22px; }
.message>span { display:grid; place-items:center; align-self:start; width:24px; height:24px; color:#fff; border-radius:50%; background:#34383d; font-size: 11px; font-weight:700; }
.message.user>span { color:#005eb8; background:#e8f2fc; }
.message.user>p { justify-self:end; max-width:78%; margin:0; padding:9px 12px; color:#28313a; border-radius:10px 10px 2px 10px; background:#edf3f8; font-size:12px; line-height:1.55; white-space:pre-wrap; }
.message.assistant { padding:12px 14px; cursor:pointer; border:1px solid transparent; border-radius:8px; transition:background-color .16s ease,border-color .16s ease; }
.message.assistant.selected { border-color:#dce8f3; background:#f7fafc; }
.assistant-content { display:grid; gap:12px; min-width:0; padding:2px 4px 4px 0; }
.answer-markdown { min-width:0; max-width:100%; color:#30363d; font-size:13px; line-height:1.72; overflow-wrap:anywhere; }
.answer-markdown :deep(h1),.answer-markdown :deep(h2),.answer-markdown :deep(h3),.answer-markdown :deep(h4) { color:#1f2933; line-height:1.4; }
.answer-markdown :deep(h1) { margin:0 0 18px; padding-bottom:9px; border-bottom:1px solid #dfe3e8; font-size:21px; }
.answer-markdown :deep(h2) { margin:24px 0 11px; padding-bottom:6px; border-bottom:1px solid #eceff2; font-size:18px; }
.answer-markdown :deep(h3) { margin:20px 0 8px; font-size:15px; }.answer-markdown :deep(h4) { margin:18px 0 7px; font-size:14px; }
.answer-markdown :deep(p) { max-width:none; margin:0 0 13px; font-size:inherit; line-height:inherit; }.answer-markdown :deep(p:last-child) { margin-bottom:0; }
.answer-markdown :deep(ul),.answer-markdown :deep(ol) { margin:0 0 14px; padding-left:24px; }.answer-markdown :deep(li) { margin:3px 0; }
.answer-markdown :deep(a) { color:#0066cc; text-decoration:underline; text-underline-offset:3px; }
.answer-markdown :deep(blockquote) { margin:14px 0; padding:9px 14px; color:#566573; border-left:3px solid #c5cbd1; background:#f6f7f8; }
.answer-markdown :deep(code) { padding:2px 5px; color:#b42318; border:1px solid #dfe4e8; border-radius:4px; background:#f3f5f7; font:12px/1.5 "SFMono-Regular",Consolas,monospace; }
.answer-markdown :deep(pre) { max-width:100%; margin:14px 0; padding:14px 16px; overflow:auto; color:#e6edf3; border:1px solid #34373d; border-radius:6px; background:#202124; white-space:pre; }
.answer-markdown :deep(pre code) { padding:0; color:inherit; border:0; background:transparent; line-height:1.65; }
.answer-markdown :deep(table) { display:block; width:max-content; max-width:100%; margin:14px 0; overflow-x:auto; border-spacing:0; border-collapse:collapse; font-size:11px; }
.answer-markdown :deep(th),.answer-markdown :deep(td) { min-width:100px; padding:8px 10px; border:1px solid #dfe3e8; text-align:left; vertical-align:top; }.answer-markdown :deep(th) { background:#f5f7f9; font-weight:650; }
.answer-markdown :deep(hr) { margin:22px 0; border:0; border-top:1px solid #dfe3e8; }.answer-markdown :deep(img) { display:block; max-width:100%; height:auto; border-radius:6px; }
.answer-markdown :deep(input[type="checkbox"]) { margin-right:6px; }
.answer-trust { display:flex; gap:8px; align-items:center; color:#66717c; font-size: 11px; }
.current-turn { margin-left:auto; padding:3px 7px; color:#4f6b82; border:1px solid #ccdce9; border-radius:4px; background:#f2f7fb; font-size: 11px; font-style:normal; }
.answer-trust strong { padding:3px 7px; color:#1e6b44; border-radius:4px; background:#e9f7ef; }
.answer-trust strong[data-status="MODEL_OUTPUT_REJECTED"],.answer-trust strong[data-status="INSUFFICIENT"] { color:#9a4c22; background:#fff0e8; }
.answer-trust strong[data-status="CITATION_INCOMPLETE"] { color:#8a5b00; background:#fff6db; }
.citation-assessment { display:flex; flex-wrap:wrap; gap:5px 12px; color:#5e6872; font-size:11px; }
.citation-assessment small { flex-basis:100%; color:#8a5b00; }
.retrieval-diagnostics { padding:8px 10px; color:#5e6872; border:1px solid #e0e6eb; border-radius:5px; background:#fafbfc; font-size:11px; }
.retrieval-diagnostics summary { cursor:pointer; font-weight:650; }.retrieval-diagnostics summary span { color:#9a4c22; }
.retrieval-diagnostics div { display:flex; flex-wrap:wrap; gap:5px 14px; margin-top:8px; }.retrieval-diagnostics ul { margin:7px 0 0; padding-left:18px; color:#8a4b22; }
.citations { display:grid; gap:7px; width:min(100%,680px); }.citations button { display:grid; grid-template-columns:auto minmax(0,1fr); gap:3px 10px; align-items:baseline; width:100%; padding:9px 11px; text-align:left; border:1px solid #dbe6f0; background:#f7fafd; }.citations button:hover,.citations button.active { border-color:#9fc3e5; background:#eef6fd; box-shadow:inset 3px 0 #0066cc; }.citations button span { color:#0066cc; font-size: 11px; font-weight:650; }.citations button b { overflow-wrap:anywhere; color:#34404b; font-size: 11px; }.citations button small { grid-column:2; color: var(--app-text-muted); font:11px "SFMono-Regular",Consolas,monospace; }
.answer-loading { display:flex; gap:10px; align-items:center; padding:4px 0; }.answer-loading i { width:13px; height:13px; border:2px solid #b9d2e8; border-top-color:#0066cc; border-radius:50%; animation:spin .8s linear infinite; }.answer-loading div { display:grid; gap:3px; }.answer-loading b { font-size:12px; }.answer-loading small { color: var(--app-text-muted); font-size: 11px; }.message.failed :deep(.el-alert) { width:min(100%,680px); }
.composer { display:flex; align-items:flex-end; gap:10px; padding:12px; border-top:1px solid #ececef; background:#fff; }.composer .el-button { flex:none; margin-bottom:4px; }
@keyframes spin { to { transform:rotate(360deg); } } @media (prefers-reduced-motion:reduce) { .answer-loading i { animation:none; } }
@media (max-width:760px) { .chat-column { min-height:620px; border-radius:7px; }.messages { max-height:65vh; padding:16px; }.message.user>p { max-width:90%; } }
</style>
