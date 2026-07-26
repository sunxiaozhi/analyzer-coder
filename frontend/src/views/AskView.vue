<script setup lang="ts">
import { Promotion } from '@element-plus/icons-vue';
import { computed, nextTick, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import { intelligenceApi, type Answer } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';

type Message = { role: 'user' | 'assistant'; text: string; answer?: Answer };

const repositories = useRepositoryStore();
const question = shallowRef('');
const busy = shallowRef(false);
const messages = shallowRef<Message[]>([]);
const messagesElement = shallowRef<HTMLElement | null>(null);
const latest = computed(() => [...messages.value].reverse().find(message => message.answer)?.answer);

async function scrollToLatest() {
  await nextTick();
  const element = messagesElement.value;
  if (element) element.scrollTop = element.scrollHeight;
}

async function send() {
  const repoId = repositories.selectedRepositoryId;
  if (!repoId) return ElMessage.warning('请先选择仓库');
  if (!question.value.trim() || busy.value) return;

  const content = question.value.trim();
  messages.value = [...messages.value, { role: 'user', text: content }];
  question.value = '';
  busy.value = true;
  await scrollToLatest();

  try {
    const answer = await intelligenceApi.ask(repoId, content);
    messages.value = [...messages.value, { role: 'assistant', text: answer.answer, answer }];
    await scrollToLatest();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '问答失败');
  } finally {
    busy.value = false;
  }
}

function clearMessages() {
  messages.value = [];
}
</script>

<template>
  <section class="qa-page">
    <main class="chat-column">
      <div class="chat-head">
        <div>
          <b>代码问答</b>
          <span>本地混合检索，回答携带代码版本与行号引用</span>
        </div>
        <el-button @click="clearMessages">清空会话</el-button>
      </div>
      <div ref="messagesElement" class="messages">
        <el-empty v-if="!messages.length" description="选择仓库后提出代码问题" />
        <article v-for="(message, index) in messages" :key="index" :class="['message', message.role]">
          <span>{{ message.role === 'user' ? '你' : 'AI' }}</span>
          <p>{{ message.text }}</p>
          <div v-if="message.answer" class="citations">
            <button v-for="citation in message.answer.citations" :key="citation.id">
              [{{ citation.rank }}] {{ citation.filePath }}:{{ citation.startLine }}
            </button>
          </div>
        </article>
      </div>
      <div class="composer">
        <el-input
          v-model="question"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 8 }"
          placeholder="询问当前代码库..."
          @keydown.ctrl.enter="send"
        />
        <el-button type="primary" :icon="Promotion" :loading="busy" circle @click="send" />
      </div>
    </main>
    <aside class="evidence qa-evidence">
      <div class="pane-head">
        <b>回答证据</b>
        <span>{{ latest?.citations.length ?? 0 }} 处引用</span>
      </div>
      <div v-for="citation in latest?.citations" :key="citation.id" class="evidence-block">
        <small>引用 {{ citation.rank }}</small>
        <b>{{ citation.symbolName || citation.filePath }}</b>
        <p class="mono">{{ citation.filePath }} · L{{ citation.startLine }}-{{ citation.endLine }}</p>
        <pre>{{ citation.content }}</pre>
      </div>
    </aside>
  </section>
</template>

<style scoped>
.chat-column {
  min-height: 0;
  overflow: hidden;
}

.messages {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.message p {
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.qa-evidence {
  min-height: 0;
}

.evidence-block pre {
  max-height: 180px;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
}

@media (max-width: 760px) {
  .chat-column {
    min-height: calc(100vh - 116px);
  }

  .messages {
    max-height: 65vh;
  }
}
</style>
