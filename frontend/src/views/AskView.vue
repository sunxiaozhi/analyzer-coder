<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { intelligenceApi, type Citation, type CodeReference, type QaHistoryRecord } from '@/api/intelligence';
import { getRepositoryProfile, type RepositoryPreparation } from '@/api/repositories';
import AskConversationPanel from '@/features/ask/AskConversationPanel.vue';
import AskHistorySidebar from '@/features/ask/AskHistorySidebar.vue';
import { useAskConversation } from '@/features/ask/useAskConversation';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositories = useRepositoryStore();
const router = useRouter();
const conversation = useAskConversation();
const history = shallowRef<QaHistoryRecord[]>([]);
const historyLoading = shallowRef(false);
const readiness = shallowRef<RepositoryPreparation | null>(null);
const readinessLoading = shallowRef(false);
let contextVersion = 0;

const repository = computed(() => repositories.selectedRepository);
const canAsk = computed(() => Boolean(
  repository.value && readiness.value?.profile.chunkCount,
));
const readinessCopy = computed(() => {
  if (!repository.value) return { label: '未选择仓库', type: 'info' as const };
  if (readinessLoading.value) return { label: '检查中', type: 'info' as const };
  return ({
    READY: { label: '问答已就绪', type: 'success' as const },
    DEGRADED: { label: '关键词检索可用', type: 'warning' as const },
    PROCESSING: { label: '正在准备', type: 'warning' as const },
    ACTION_REQUIRED: { label: '准备失败', type: 'danger' as const },
    NOT_READY: { label: '尚未准备', type: 'info' as const },
  })[readiness.value?.state ?? 'NOT_READY'];
});

async function loadContext(repositoryId: string | null) {
  const version = ++contextVersion;
  conversation.invalidate();
  history.value = [];
  readiness.value = null;
  if (!repositoryId) return;
  readinessLoading.value = true;
  historyLoading.value = true;
  const isCurrent = () => version === contextVersion
    && repositoryId === repositories.selectedRepositoryId;
  const profileTask = getRepositoryProfile(repositoryId)
    .then((result) => { if (isCurrent()) readiness.value = result; })
    .catch((error) => {
      if (isCurrent()) ElMessage.error(error instanceof Error ? error.message : '无法检查仓库状态');
    })
    .finally(() => { if (isCurrent()) readinessLoading.value = false; });
  const historyTask = intelligenceApi.history(repositoryId)
    .then((result) => { if (isCurrent()) history.value = result; })
    .catch((error) => {
      if (isCurrent()) ElMessage.error(error instanceof Error ? error.message : '无法加载历史记录');
    })
    .finally(() => { if (isCurrent()) historyLoading.value = false; });
  await Promise.allSettled([profileTask, historyTask]);
}

async function reloadHistory() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  historyLoading.value = true;
  try { history.value = await intelligenceApi.history(repositoryId); }
  finally { historyLoading.value = false; }
}

async function refreshReadinessForAsk(repositoryId: string): Promise<boolean | null> {
  readinessLoading.value = true;
  try {
    const latest = await getRepositoryProfile(repositoryId);
    if (repositoryId !== repositories.selectedRepositoryId) return null;
    readiness.value = latest;
    return latest.profile.chunkCount > 0;
  } catch (error) {
    if (repositoryId === repositories.selectedRepositoryId) {
      ElMessage.error(error instanceof Error ? error.message : '无法检查仓库状态');
    }
    return null;
  } finally {
    if (repositoryId === repositories.selectedRepositoryId) readinessLoading.value = false;
  }
}

async function send() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return ElMessage.warning('请先选择仓库');
  const ready = canAsk.value || await refreshReadinessForAsk(repositoryId);
  if (ready === null || repositoryId !== repositories.selectedRepositoryId) return;
  if (!ready) return ElMessage.warning('当前仓库尚未完成问答准备，请先完成索引');
  try {
    const result = await conversation.send(repositoryId);
    if (!result || result.repositoryId !== repositories.selectedRepositoryId) return;
    await reloadHistory();
  } catch { /* 错误保留在回答区，可直接重试。 */ }
}

async function retry() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  try {
    const result = await conversation.retry(repositoryId);
    if (result) await reloadHistory();
  } catch { /* 错误保留在回答区。 */ }
}

async function openHistory(record: QaHistoryRecord) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId || record.repositoryId !== repositoryId) return;
  try {
    const result = await intelligenceApi.historyDetail(repositoryId, record.threadId);
    if (repositoryId !== repositories.selectedRepositoryId) return;
    conversation.restore(result);
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法打开历史记录'); }
}

async function renameHistory(record: QaHistoryRecord) {
  try {
    const { value } = await ElMessageBox.prompt('输入新的历史记录标题', '重命名记录', {
      inputValue: record.title, inputPattern: /^.{1,80}$/s, inputErrorMessage: '标题长度必须为 1–80 个字符',
    });
    await intelligenceApi.renameHistory(record.repositoryId, record.threadId, value.trim());
    await reloadHistory();
    ElMessage.success('历史记录已重命名');
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '重命名失败');
  }
}

async function deleteHistory(record: QaHistoryRecord) {
  try {
    await ElMessageBox.confirm(`删除“${record.title}”及其引用证据？`, '删除历史记录', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
    await intelligenceApi.deleteHistory(record.repositoryId, record.threadId);
    if (conversation.threadId.value === record.threadId) conversation.reset();
    await reloadHistory();
    ElMessage.success('历史记录已删除');
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

async function selectTargetRepository(repositoryId: string) {
  if (repositories.selectedRepositoryId !== repositoryId) await repositories.selectRepository(repositoryId);
}

async function openCode(reference: CodeReference) {
  try {
    await selectTargetRepository(reference.repositoryId);
    await router.push({ name: 'search', query: {
      path: reference.filePath, startLine: String(reference.startLine ?? 1), endLine: String(reference.endLine ?? reference.startLine ?? 1),
    }});
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法打开源码'); }
}

async function openKnowledge(citation: Citation) {
  if (!citation.knowledgeCardId) return;
  try {
    await selectTargetRepository(citation.repositoryId);
    await router.push({ name: 'knowledge', query: { cardId: citation.knowledgeCardId } });
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法打开知识卡片'); }
}

async function openGraph(reference: CodeReference) {
  try {
    await selectTargetRepository(reference.repositoryId);
    const target = reference.chunkId
      ? await intelligenceApi.graphTarget(reference.repositoryId, reference.chunkId)
      : { symbol: reference.symbolName || reference.filePath };
    await router.push({ name: 'graph', query: { symbol: target.symbol, depth: '3', analyze: '1' } });
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法解析图谱目标'); }
}


watch(() => repositories.selectedRepositoryId, loadContext);
onMounted(async () => {
  if (!repositories.repositories.length) await repositories.loadRepositories();
  await loadContext(repositories.selectedRepositoryId);
});
</script>

<template>
  <section class="qa-page">
    <header class="qa-command surface">
      <div class="scope-copy">
        <span>问答范围</span>
        <strong>{{ repository?.name ?? '未选择仓库' }}</strong>
        <small>{{ repository?.branch ?? '无分支' }}<template v-if="repository?.commit"> · {{ repository.commit.slice(0, 8) }}</template></small>
      </div>
      <el-tag :type="readinessCopy?.type" effect="plain" round>{{ readinessCopy?.label }}</el-tag>
      <p v-if="readiness && !canAsk">当前仓库还没有可检索的代码内容，请先完成索引。</p>
      <div class="command-actions">
        <el-button :icon="Plus" type="primary" plain @click="conversation.reset()">新会话</el-button>
      </div>
    </header>

    <AskHistorySidebar :records="history" :active-thread-id="conversation.threadId.value" :loading="historyLoading"
      @open="openHistory" @refresh="reloadHistory" @rename="renameHistory" @delete="deleteHistory" />

    <AskConversationPanel
      v-model="conversation.question.value"
      :turns="conversation.turns.value"
      :active-answer-id="conversation.activeAnswerId.value"
      :restored-thread-id="conversation.threadId.value"
      :pending-question="conversation.pendingQuestion.value"
      :request-state="conversation.requestState.value"
      :error="conversation.error.value"
      :disabled="!repository"
      @send="send" @retry="retry" @select-answer="conversation.selectAnswer"
      @open-knowledge="openKnowledge" @open-code="openCode" @open-graph="openGraph"
    />

  </section>
</template>

<style scoped>
.qa-page { display:grid; grid-template-columns:280px minmax(0,1fr); grid-template-rows:auto minmax(0,1fr); gap:12px; min-height:0; height:100%; }
.qa-command { grid-column:1/-1; display:flex; min-height:62px; align-items:center; gap:12px; padding:9px 14px; border:1px solid #dedee3; border-radius:7px; background:#fff; }
.scope-copy { display:grid; grid-template-columns:auto auto; align-items:baseline; gap:2px 9px; min-width:0; }.scope-copy>span { grid-row:1/3; align-self:center; padding-right:10px; color:#0066cc; border-right:2px solid #90bde5; font-size:9px; font-weight:700; letter-spacing:.08em; }.scope-copy strong { overflow:hidden; color:#2d3035; font-size:13px; text-overflow:ellipsis; white-space:nowrap; }.scope-copy small { color:#858a90; font-size:9px; }
.qa-command>p { margin:0; color:#7b5a1b; font-size:10px; }.command-actions { display:flex; gap:8px; margin-left:auto; }
@media (max-width:900px) { .qa-page { grid-template-columns:1fr; grid-template-rows:auto auto minmax(620px,1fr); gap:10px; overflow:auto; }.qa-command { grid-column:1; }.qa-command>p { display:none; } }
@media (max-width:760px) { .qa-page { height:auto; }.qa-command { flex-wrap:wrap; }.scope-copy { flex:1; }.command-actions { width:100%; margin-left:0; }.command-actions .el-button { flex:1; } }
</style>
