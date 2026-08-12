<script setup lang="ts">
import { Promotion, RefreshRight } from '@element-plus/icons-vue';
import { computed, nextTick, onActivated, watch, useTemplateRef } from 'vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
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
const contentElement = useTemplateRef<HTMLElement>('content');
const renderedAnswer = computed(() => DOMPurify.sanitize(
  marked.parse(props.answer?.answer ?? '', { async: false, gfm: true }),
  { USE_PROFILES: { html: true } },
));

const statusCopy = computed(() => {
  if (!props.answer) return null;
  return ({
  SUPPORTED: { label: '引用已校验', detail: '回答中的事实引用已经通过校验' },
  DEGRADED: { label: '本地证据回答', detail: '未使用外部模型，请结合右侧证据核对' },
  MODEL_OUTPUT_REJECTED: { label: '已安全降级', detail: '模型回答未通过引用校验，已改用本地证据' },
  INSUFFICIENT: { label: '证据不足', detail: '没有生成推测性结论，请尝试更具体的问题' },
  } as const)[props.answer.evidenceStatus];
});

async function scrollToLatest() {
  await nextTick();
  if (contentElement.value) contentElement.value.scrollTop = contentElement.value.scrollHeight;
}
watch(() => [props.answer?.conversationId, props.requestState], scrollToLatest);
onActivated(scrollToLatest);

function shortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault();
    emit('send');
  }
}
</script>

<template>
  <main class="answer-workspace">
    <div ref="content" class="answer-scroll" aria-live="polite">
      <section v-if="!answer && requestState === 'idle'" class="ask-empty">
        <div class="empty-mark">?</div>
        <h2>从当前仓库找到可核对的答案</h2>
        <p>可以询问业务规则、实现位置、调用关系或影响范围。系统会联合检索代码和已发布知识。</p>
        <small>每次提问都会重新检索当前仓库，不会自动引用上一条问题。</small>
      </section>

      <section v-else class="answer-document">
        <div v-if="submittedQuestion" class="question-block">
          <span>问题</span>
          <h1>{{ submittedQuestion }}</h1>
        </div>

        <div v-if="requestState === 'sending'" class="answer-loading">
          <span></span><div><b>正在检索当前仓库</b><p>正在核对代码和知识证据…</p></div>
        </div>

        <el-alert v-if="requestState === 'failed'" type="error" :closable="false" show-icon>
          <template #title>本次提问未完成</template>
          <p>{{ error }}</p>
          <el-button :icon="RefreshRight" @click="emit('retry')">重新发送</el-button>
        </el-alert>

        <template v-if="answer">
          <header class="answer-meta">
            <div>
              <strong :data-status="answer.evidenceStatus">{{ statusCopy?.label }}</strong>
              <span>{{ statusCopy?.detail }}</span>
            </div>
            <time>{{ new Date(answer.createdAt).toLocaleString() }}</time>
          </header>
          <article class="answer-markdown" v-html="renderedAnswer"></article>
          <section v-if="answer.citations.length" class="inline-citations" aria-label="回答引用">
            <button
              v-for="citation in answer.citations"
              :key="citation.id"
              :class="{ active: citation.id === activeCitationId }"
              @click="emit('selectCitation', citation.id)"
            >
              <span>S{{ citation.rank }}</span>
              <b>{{ citation.title }}</b>
              <small>{{ citation.sourceType === 'KNOWLEDGE' ? '知识卡片' : '代码证据' }}</small>
            </button>
          </section>
        </template>
      </section>
    </div>

    <div class="composer">
      <el-input
        v-model="question"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 7 }"
        :disabled="disabled"
        placeholder="输入新的问题，例如：订单取消后库存如何回滚？"
        @keydown="shortcut"
      />
      <div class="composer-actions">
        <small>{{ disabled ? '请先选择并准备仓库' : 'Ctrl / ⌘ + Enter 发送' }}</small>
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="requestState === 'sending'"
          :disabled="disabled || !question.trim()"
          aria-label="发送问题"
          @click="emit('send')"
        >发送</el-button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.answer-workspace { display:grid; grid-template-rows:minmax(0,1fr) auto; min-width:0; min-height:0; overflow:hidden; border:1px solid #dedee3; border-radius:7px 0 0 7px; background:#fff; }
.answer-scroll { min-height:0; overflow:auto; overscroll-behavior:contain; }
.ask-empty { display:grid; place-items:center; align-content:center; min-height:100%; padding:48px; text-align:center; }
.empty-mark { display:grid; place-items:center; width:56px; height:56px; margin-bottom:18px; color:#0066cc; border:1px solid #a9cceb; border-radius:16px 16px 16px 4px; background:#eef6fd; font:700 26px/1 Georgia,serif; }
.ask-empty h2 { margin:0; color:#27272b; font:650 22px/1.3 Georgia,"Songti SC",serif; }
.ask-empty p { max-width:560px; margin:12px 0 8px; color:#5f6670; font-size:13px; line-height:1.7; }
.ask-empty small { color:#8a8f96; font-size:10px; }
.answer-document { width:min(100%,850px); margin:0 auto; padding:34px clamp(24px,5vw,64px) 56px; }
.question-block { padding-bottom:24px; border-bottom:1px solid #e4e6e9; }
.question-block span { color:#0066cc; font-size:10px; font-weight:700; letter-spacing:.12em; }
.question-block h1 { margin:8px 0 0; color:#22252a; font:650 23px/1.45 Georgia,"Songti SC",serif; }
.answer-meta { display:flex; justify-content:space-between; gap:14px; align-items:start; margin:24px 0; }
.answer-meta > div { display:grid; gap:5px; }
.answer-meta strong { width:max-content; padding:4px 8px; color:#176743; border-radius:4px; background:#e9f6ef; font-size:10px; }
.answer-meta strong[data-status="INSUFFICIENT"],.answer-meta strong[data-status="MODEL_OUTPUT_REJECTED"] { color:#91431e; background:#fff0e8; }
.answer-meta span,.answer-meta time { color:#7d838a; font-size:10px; }
.answer-markdown { color:#34383e; font-size:14px; line-height:1.75; overflow-wrap:anywhere; }
.answer-markdown :deep(p) { margin:0 0 15px; }
.answer-markdown :deep(h1),.answer-markdown :deep(h2),.answer-markdown :deep(h3) { margin:26px 0 10px; color:#23272d; line-height:1.4; }
.answer-markdown :deep(h1) { font-size:21px; }.answer-markdown :deep(h2) { font-size:18px; }.answer-markdown :deep(h3) { font-size:15px; }
.answer-markdown :deep(ul),.answer-markdown :deep(ol) { padding-left:24px; }.answer-markdown :deep(li+li) { margin-top:5px; }
.answer-markdown :deep(code) { padding:2px 5px; color:#a52d20; border:1px solid #e1e4e8; border-radius:4px; background:#f4f6f8; font:12px Consolas,monospace; }
.answer-markdown :deep(pre) { padding:15px; overflow:auto; color:#e8edf3; border-radius:6px; background:#20242a; }.answer-markdown :deep(pre code) { padding:0; color:inherit; border:0; background:transparent; }
.inline-citations { display:grid; grid-template-columns:repeat(auto-fit,minmax(210px,1fr)); gap:8px; margin-top:26px; padding-top:20px; border-top:1px solid #e6e8eb; }
.inline-citations button { display:grid; grid-template-columns:auto minmax(0,1fr); gap:2px 8px; padding:10px; text-align:left; border:1px solid #dce4ec; border-radius:5px; background:#fafcfe; }
.inline-citations button:hover,.inline-citations button.active { border-color:#8fbae1; background:#eef6fd; box-shadow:inset 3px 0 #0066cc; }
.inline-citations span { grid-row:1/3; color:#0066cc; font-size:10px; font-weight:700; }.inline-citations b { overflow:hidden; font-size:11px; text-overflow:ellipsis; white-space:nowrap; }.inline-citations small { color:#858b92; font-size:9px; }
.answer-loading { display:flex; gap:12px; align-items:center; margin-top:28px; padding:18px; background:#f6f9fc; }
.answer-loading > span { width:13px; height:13px; border:2px solid #b9d2e8; border-top-color:#0066cc; border-radius:50%; animation:spin .8s linear infinite; }.answer-loading b { font-size:12px; }.answer-loading p { margin:4px 0 0; color:#77818a; font-size:10px; }
.composer { display:grid; gap:8px; padding:12px 14px; border-top:1px solid #dedee3; background:#fbfbfc; }.composer-actions { display:flex; align-items:center; justify-content:space-between; }.composer-actions small { color:#8a8e94; font-size:9px; }
@keyframes spin { to { transform:rotate(360deg); } }
@media (prefers-reduced-motion:reduce) { .answer-loading>span { animation:none; } }
@media (max-width:760px) { .answer-workspace { min-height:620px; border-radius:7px; }.answer-document { padding:24px 18px 42px; }.ask-empty { min-height:430px; padding:28px 20px; }.question-block h1 { font-size:20px; } }
</style>
