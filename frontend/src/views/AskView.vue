<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { intelligenceApi, type CodeReference } from '@/api/intelligence';
import AskConversationPanel from '@/features/ask/AskConversationPanel.vue';
import AnswerEvidencePanel from '@/features/ask/AnswerEvidencePanel.vue';
import { useAskConversation } from '@/features/ask/useAskConversation';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositories = useRepositoryStore();
const router = useRouter();
const conversation = useAskConversation();

async function send() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return ElMessage.warning('请先选择仓库');
  try {
    await conversation.send(repositoryId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '问答失败');
  }
}

function openCode(reference: CodeReference) {
  void router.push({
    name: 'search',
    query: {
      path: reference.filePath,
      startLine: String(reference.startLine ?? 1),
      endLine: String(reference.endLine ?? reference.startLine ?? 1),
    },
  });
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
    await router.push({
      name: 'graph',
      query: { symbol: target.symbol, depth: '3', analyze: '1' },
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法解析图谱目标');
  }
}
</script>

<template>
  <section class="qa-page">
    <AskConversationPanel
      v-model="conversation.question.value"
      :messages="conversation.messages.value"
      :busy="conversation.busy.value"
      :active-citation-id="conversation.activeCitationId.value"
      @send="send"
      @clear="conversation.clear"
      @select-citation="conversation.selectCitation"
    />
    <AnswerEvidencePanel
      :citations="conversation.evidenceAnswer.value?.citations ?? []"
      :active-citation-id="conversation.activeCitationId.value"
      @open-knowledge="openKnowledge"
      @open-code="openCode"
      @open-graph="openGraph"
    />
  </section>
</template>
