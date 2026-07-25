<script setup lang="ts">
import { Promotion } from '@element-plus/icons-vue';
import { computed, nextTick, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { intelligenceApi, type Answer, type CodeReference } from '@/api/intelligence';
import AnswerEvidencePanel from '@/features/ask/AnswerEvidencePanel.vue';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositories = useRepositoryStore();
const router = useRouter();
const question = shallowRef('');
const busy = shallowRef(false);
const messages = shallowRef<{ role: 'user' | 'assistant'; text: string; answer?: Answer }[]>([]);
const latest = computed(() => [...messages.value].reverse().find(item => item.answer)?.answer);

async function send() {
  const repositoryId = repositories.selectedRepositoryId;
  const value = question.value.trim();
  if (!repositoryId) return ElMessage.warning('请先选择仓库');
  if (!value) return;
  messages.value = [...messages.value, { role: 'user', text: value }];
  question.value = '';
  busy.value = true;
  try {
    const answer = await intelligenceApi.ask(repositoryId, value);
    messages.value = [...messages.value, { role: 'assistant', text: answer.answer, answer }];
    await nextTick();
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
</script>
<template>
  <section class="qa-page">
    <main class="chat-column">
      <div class="chat-head">
        <div><b>知识与代码问答</b><span>联合检索团队知识、当前快照代码与结构化引用</span></div>
        <el-button @click="messages = []">清空会话</el-button>
      </div>
      <div class="messages">
        <el-empty v-if="!messages.length" description="可以询问业务知识、实现位置或代码影响" />
        <article v-for="(message, index) in messages" :key="index" :class="['message', message.role]">
          <span>{{ message.role === 'user' ? '你' : 'AI' }}</span>
          <p class="answer-copy">{{ message.text }}</p>
          <div v-if="message.answer" class="citations">
            <button v-for="citation in message.answer.citations" :key="citation.id">
              [S{{ citation.rank }}] {{ citation.sourceType === 'KNOWLEDGE' ? '知识' : '代码' }} · {{ citation.title }}
            </button>
          </div>
        </article>
      </div>
      <div class="composer">
        <el-input v-model="question" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="询问业务规则、实现位置、调用关系或影响范围…" @keydown.ctrl.enter="send" />
        <el-button type="primary" :icon="Promotion" :loading="busy" circle @click="send" />
      </div>
    </main>
    <AnswerEvidencePanel :citations="latest?.citations ?? []"
      @open-knowledge="openKnowledge" @open-code="openCode" @open-graph="openGraph" />
  </section>
</template>

<style scoped>
.answer-copy { white-space: pre-line; }
</style>
