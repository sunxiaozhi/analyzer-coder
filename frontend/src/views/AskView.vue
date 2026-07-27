<script setup lang="ts">
import { Promotion } from '@element-plus/icons-vue';
import { computed, nextTick, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { intelligenceApi, type Answer, type CodeReference } from '@/api/intelligence';
import AnswerEvidencePanel from '@/features/ask/AnswerEvidencePanel.vue';
import { useRepositoryStore } from '@/stores/repositoryStore';

type Message = { role: 'user' | 'assistant'; text: string; answer?: Answer };

const repositories = useRepositoryStore();
const router = useRouter();
const question = shallowRef('');
const busy = shallowRef(false);
const messages = shallowRef<Message[]>([]);
const messagesElement = shallowRef<HTMLElement | null>(null);
const activeCitationId = shallowRef<string | null>(null);
const latest = computed(() => [...messages.value].reverse().find(item => item.answer)?.answer);
const evidenceAnswer = computed(() => {
  if (activeCitationId.value) {
    const selected = [...messages.value].reverse()
      .find(item => item.answer?.citations.some(citation => citation.id === activeCitationId.value));
    if (selected?.answer) return selected.answer;
  }
  return latest.value;
});

async function scrollToLatest() {
  await nextTick();
  const element = messagesElement.value;
  if (element) element.scrollTop = element.scrollHeight;
}

async function send() {
  const repositoryId = repositories.selectedRepositoryId;
  const value = question.value.trim();
  if (!repositoryId) return ElMessage.warning('请先选择仓库');
  if (!value || busy.value) return;
  messages.value = [...messages.value, { role: 'user', text: value }];
  question.value = '';
  busy.value = true;
  await scrollToLatest();
  try {
    const answer = await intelligenceApi.ask(repositoryId, value);
    messages.value = [...messages.value, { role: 'assistant', text: answer.answer, answer }];
    activeCitationId.value = answer.citations[0]?.id ?? null;
    await scrollToLatest();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '问答失败');
  } finally {
    busy.value = false;
  }
}

function openCode(reference: CodeReference) {
  void router.push({ name: 'search', query: {
    path: reference.filePath,
    startLine: String(reference.startLine ?? 1),
    endLine: String(reference.endLine ?? reference.startLine ?? 1),
  } });
}

function openKnowledge(cardId: string) {
  void router.push({ name: 'knowledge', query: { cardId } });
}

async function openGraph(reference: CodeReference) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  try {
    const target = reference.chunkId
      ? await intelligenceApi.graphTarget(repositoryId, reference.chunkId)
      : { symbol: reference.symbolName || reference.filePath };
    await router.push({ name: 'graph', query: { symbol: target.symbol, depth: '3', analyze: '1' } });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法解析图谱目标');
  }
}

function clearMessages() {
  messages.value = [];
  activeCitationId.value = null;
}
</script>

<template>
  <section class="qa-page">
    <main class="chat-column">
      <div class="chat-head">
        <div><b>知识与代码问答</b><span>联合检索团队知识、当前版本代码与结构化引用</span></div>
        <el-button @click="clearMessages">清空会话</el-button>
      </div>
      <div ref="messagesElement" class="messages">
        <el-empty v-if="!messages.length" description="可以询问业务知识、实现位置或代码影响" />
        <article v-for="(message, index) in messages" :key="index" :class="['message', message.role]">
          <span>{{ message.role === 'user' ? '你' : 'AI' }}</span>
          <p class="answer-copy">{{ message.text }}</p>
          <div v-if="message.answer" class="citations">
            <button
              v-for="citation in message.answer.citations"
              :key="citation.id"
              :class="{ active: citation.id === activeCitationId }"
              @click="activeCitationId = citation.id"
            >
              <span>[S{{ citation.rank }}] {{ citation.sourceType === 'KNOWLEDGE' ? '知识库' : '关联代码' }}</span>
              <b>{{ citation.title }}</b>
              <small v-if="citation.sourceType === 'CODE'">
                {{ citation.filePath }} · L{{ citation.startLine ?? '?' }}
              </small>
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
          @keydown.ctrl.enter="send"
        />
        <el-button type="primary" :icon="Promotion" :loading="busy" circle @click="send" />
      </div>
    </main>
    <AnswerEvidencePanel
      :citations="evidenceAnswer?.citations ?? []"
      :active-citation-id="activeCitationId"
      @open-knowledge="openKnowledge"
      @open-code="openCode"
      @open-graph="openGraph"
    />
  </section>
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
  box-shadow: inset 3px 0 transparent;
  transition: border-color .18s ease, background-color .18s ease, box-shadow .18s ease;
}
.citations button:hover,
.citations button.active {
  border-color: #9fc3e5;
  background: #eef6fd;
  box-shadow: inset 3px 0 #0066cc;
}
.citations button span {
  color: #0066cc;
  font-size: 9px;
  font-weight: 650;
  white-space: nowrap;
}
.citations button b {
  overflow-wrap: anywhere;
  color: #34404b;
  font-size: 10px;
}
.citations button small {
  grid-column: 2;
  overflow-wrap: anywhere;
  color: #78838d;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 8px;
  line-height: 1.5;
}
@media (max-width: 760px) {
  .chat-column { min-height: calc(100vh - 116px); }
  .messages { max-height: 65vh; }
}
</style>
