<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { intelligenceApi, type CodeReference } from '@/api/intelligence';
import AskConversationPanel from '@/features/ask/AskConversationPanel.vue';
import AnswerEvidencePanel from '@/features/ask/AnswerEvidencePanel.vue';
import { useAskConversation } from '@/features/ask/useAskConversation';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { onMounted, shallowRef, watch } from 'vue';
import type { QaSession } from '@/api/intelligence';

const repositories = useRepositoryStore();
const router = useRouter();
const conversation = useAskConversation();
const selectedRepositoryIds=shallowRef<string[]>([]);const sessions=shallowRef<QaSession[]>([]);const activeSessionId=shallowRef('');
async function loadSessions(){sessions.value=await intelligenceApi.sessions()}
async function newSession(){const ids=selectedRepositoryIds.value.length?selectedRepositoryIds.value:[repositories.selectedRepositoryId!].filter(Boolean);if(!ids.length)return;const session=await intelligenceApi.createSession(ids);await loadSessions();activeSessionId.value=session.id;conversation.clear()}
async function openSession(id:string){activeSessionId.value=id;if(!id){conversation.clear();return}const session=sessions.value.find(item=>item.id===id);if(session)selectedRepositoryIds.value=session.repositoryIds;conversation.restore(await intelligenceApi.sessionMessages(id))}
onMounted(async()=>{await repositories.loadRepositories();if(repositories.selectedRepositoryId)selectedRepositoryIds.value=[repositories.selectedRepositoryId];await loadSessions()});
watch(()=>repositories.selectedRepositoryId,id=>{if(id&&!selectedRepositoryIds.value.length)selectedRepositoryIds.value=[id]});

async function send() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return ElMessage.warning('请先选择仓库');
  try {
    if(!activeSessionId.value)await newSession();
    await conversation.send(repositoryId,{sessionId:activeSessionId.value||undefined,repositoryIds:selectedRepositoryIds.value});
    await loadSessions();
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
    <div class="qa-toolbar surface">
      <el-select v-model="activeSessionId" clearable placeholder="问答会话" @change="openSession" style="width:220px"><el-option v-for="item in sessions" :key="item.id" :value="item.id" :label="item.title"/></el-select>
      <el-button @click="newSession">新会话</el-button>
      <el-select v-model="selectedRepositoryIds" multiple collapse-tags placeholder="选择一个或多个仓库" style="min-width:320px"><el-option v-for="item in repositories.repositories" :key="item.id" :value="item.id" :label="item.name"/></el-select>
    </div>
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
<style scoped>.qa-page{grid-template-rows:auto minmax(0,1fr)}.qa-toolbar{grid-column:1/-1;display:flex;align-items:center;gap:10px;padding:10px 14px}</style>
