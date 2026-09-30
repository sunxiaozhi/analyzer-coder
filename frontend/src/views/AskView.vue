<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, onBeforeUnmount, onDeactivated, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  intelligenceApi,
  type AskModel,
  type Citation,
  type CodeReference,
  type QaHistoryRecord,
} from '@/api/intelligence';
import AskConversationPanel from '@/features/ask/AskConversationPanel.vue';
import AskHistorySidebar from '@/features/ask/AskHistorySidebar.vue';
import { usePageMemoryStore } from '@/stores/pageMemory';
import { useAskConversation } from '@/features/ask/useAskConversation';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { useBranchReadScope } from '@/features/branches/useBranchReadScope';
import { branchesApi } from '@/api/branches';
import { useAuthStore } from '@/stores/authStore';
import { statusLabel } from '@/utils/displayLabels';

const repositories = useRepositoryStore();
const branchContext = useBranchContextStore();
const readScope = useBranchReadScope();
const branchContentReady = shallowRef(false);
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const conversation = useAskConversation();
const history = shallowRef<QaHistoryRecord[]>([]);
const historyLoading = shallowRef(false);
const readinessLoading = shallowRef(false);
const askModels = shallowRef<AskModel[]>([]);
const selectedModelId = shallowRef('');
const modelsLoading = shallowRef(false);
let contextVersion = 0;
let historyRequest = 0;
const defaultHistoryOpen = () => typeof window.matchMedia !== 'function' || !window.matchMedia('(max-width: 900px)').matches;
const historyOpen = shallowRef(defaultHistoryOpen());
const readingTop = shallowRef(0);
const expandedEvidence = shallowRef<string[]>([]);
const memory = usePageMemoryStore();
const scopeKey = () => `ask:${repositories.selectedRepositoryId}:${branchContext.selectedBranchId ?? 'default'}`;
let stateKey = scopeKey();
let hasContext = false;
type AskState = { thread: string | null; question: string; answer: string | null; top: number; expanded: string[]; history: boolean; model: string };
function rememberConversation() {
  if (!hasContext) return;
  memory.write<AskState>(stateKey, { thread: conversation.threadId.value, question: conversation.question.value,
    answer: conversation.activeAnswerId.value, top: readingTop.value, expanded: [...expandedEvidence.value], history: historyOpen.value, model: selectedModelId.value });
}
function expandEvidence(id: string, open: boolean) {
  expandedEvidence.value = open ? [...new Set([...expandedEvidence.value, id])] : expandedEvidence.value.filter(item => item !== id);
}
onDeactivated(rememberConversation);
onBeforeUnmount(() => { rememberConversation(); contextVersion++; historyRequest++; conversation.invalidate(); });

const repository = computed(() => repositories.selectedRepository);
const canAsk = computed(() => Boolean(repository.value && !readScope.blocked.value && branchContentReady.value));
const selectedModel = computed(() =>
  askModels.value.find(item => item.id === selectedModelId.value) ?? null
);
const readinessCopy = computed(() => {
  if (!repository.value) return { label: '未选择项目', type: 'info' as const };
  return { label: readScope.blocked.value ? '分支内容版本未就绪' : branchContentReady.value ? '当前分支内容索引已就绪' : '当前分支待构建内容索引', type: branchContentReady.value ? 'success' as const : 'info' as const };
});

async function loadContext(repositoryId: string | null) {
  rememberConversation();
  hasContext = false;
  stateKey = scopeKey();
  const saved = memory.read<AskState>(stateKey);
  selectedModelId.value = saved?.model ?? '';
  historyOpen.value = saved?.history ?? defaultHistoryOpen();
  expandedEvidence.value = saved?.expanded ?? [];
  readingTop.value = saved?.top ?? 0;
  const version = ++contextVersion;
  const branchIdentity = branchContext.identity;
  conversation.invalidate();
  history.value = [];
  branchContentReady.value = false;
  askModels.value = [];
  readinessLoading.value = false;
  historyLoading.value = false;
  modelsLoading.value = false;
  if (!repositoryId) return;
  if (readScope.blocked.value) return;
  readinessLoading.value = true;
  historyLoading.value = true;
  modelsLoading.value = true;
  const isCurrent = () => version === contextVersion
    && repositoryId === repositories.selectedRepositoryId
    && branchIdentity === branchContext.identity;
  const profileTask = branchesApi.contentVersionIndexStatus(branchContext.context!).then(status => {
    if (isCurrent()) branchContentReady.value = status.contentVersion === branchContext.context?.contentVersion && status.contentReady;
  })
    .catch((error) => {
      if (isCurrent()) ElMessage.error(error instanceof Error ? error.message : '无法检查项目状态');
    })
    .finally(() => { if (isCurrent()) readinessLoading.value = false; });
  const historyTask = intelligenceApi.history(repositoryId, 50, 0, branchContext.context?.contextId)
    .then((result) => { if (isCurrent()) history.value = result; })
    .catch((error) => {
      if (isCurrent()) ElMessage.error(error instanceof Error ? error.message : '无法加载历史记录');
    })
    .finally(() => { if (isCurrent()) historyLoading.value = false; });
  const modelsTask = intelligenceApi.askModels(repositoryId)
    .then((result) => {
      if (!isCurrent()) return;
      askModels.value = result;
      const current = result.find(item => item.id === selectedModelId.value && item.available);
      selectedModelId.value = current?.id ?? '';
    })
    .catch((error) => {
      if (isCurrent()) ElMessage.error(error instanceof Error ? error.message : '无法加载问答模型');
    })
    .finally(() => { if (isCurrent()) modelsLoading.value = false; });
  await Promise.allSettled([profileTask, historyTask, modelsTask]);
  if (!isCurrent()) return;
  hasContext = true;
  const record = history.value.find(item => item.threadId === saved?.thread);
  if (record) {
    await openHistory(record, true);
    if (!isCurrent()) return;
    if (saved?.answer) conversation.selectAnswer(saved.answer);
  }
  if (typeof route.query.q === 'string') conversation.question.value = route.query.q;
  else conversation.question.value = saved?.question ?? '';
}

async function reloadHistory() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId || readScope.blocked.value) return;
  const identity = branchContext.identity;
  const isCurrent = () => repositoryId === repositories.selectedRepositoryId && identity === branchContext.identity;
  historyLoading.value = true;
  try {
    const result = await intelligenceApi.history(repositoryId, 50, 0, branchContext.context?.contextId);
    if (isCurrent()) history.value = result;
  } finally { if (isCurrent()) historyLoading.value = false; }
}

async function refreshReadinessForAsk(repositoryId: string): Promise<boolean | null> {
  if (readScope.blocked.value) return false;
  const identity = branchContext.identity;
  const status = await branchesApi.contentVersionIndexStatus(branchContext.context!);
  if (identity !== branchContext.identity || repositoryId !== repositories.selectedRepositoryId) return null;
  branchContentReady.value = status.contentVersion === branchContext.context?.contentVersion && status.contentReady;
  return branchContentReady.value;
}

async function send() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return ElMessage.warning('请先选择项目');
  if (readScope.blocked.value) {
    return ElMessage.warning('当前分支内容版本尚未就绪，请先在分支工作区完成准备');
  }
  const ready = canAsk.value || await refreshReadinessForAsk(repositoryId);
  if (ready === null || repositoryId !== repositories.selectedRepositoryId) return;
  if (!ready) return ElMessage.warning('当前项目尚未完成问答准备，请先完成索引');
  try {
    const result = await conversation.send(repositoryId, selectedModelId.value || null, branchContext.context?.contextId ?? null);
    if (!result || result.repositoryId !== repositories.selectedRepositoryId) return;
    readingTop.value = Number.MAX_SAFE_INTEGER;
    await reloadHistory();
  } catch { /* 错误保留在回答区，可直接重试。 */ }
}

async function retry() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId || readScope.blocked.value) return;
  try {
    const result = await conversation.retry(repositoryId, selectedModelId.value || null, branchContext.context?.contextId ?? null);
    if (result) await reloadHistory();
  } catch { /* 错误保留在回答区。 */ }
}

async function openHistory(record: QaHistoryRecord, preservePosition = false) {
  const request = ++historyRequest;
  const identity = branchContext.identity;
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId || record.repositoryId !== repositoryId) return;
  try {
    const result = await intelligenceApi.historyDetail(repositoryId, record.threadId, branchContext.context?.contextId);
    if (request !== historyRequest || identity !== branchContext.identity || repositoryId !== repositories.selectedRepositoryId) return;
    conversation.restore(result);
    if (!preservePosition) { readingTop.value = 0; expandedEvidence.value = []; }
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法打开历史记录'); }
}

async function renameHistory(record: QaHistoryRecord) {
  try {
    const { value } = await ElMessageBox.prompt('输入新的历史记录标题', '重命名记录', {
      inputValue: record.title, inputPattern: /^.{1,80}$/s, inputErrorMessage: '标题长度必须为 1–80 个字符',
    });
    await intelligenceApi.renameHistory(record.repositoryId, record.threadId, value.trim(), branchContext.context?.contextId);
    await reloadHistory();
    ElMessage.success('历史记录已重命名');
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '重命名失败');
  }
}

async function deleteHistory(record: QaHistoryRecord) {
  try {
    await ElMessageBox.confirm(`删除“${record.title}”及其引用证据？`, '删除历史记录', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
    await intelligenceApi.deleteHistory(record.repositoryId, record.threadId, branchContext.context?.contextId);
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

function openReadinessAction() {
  void router.push(repository.value ? '/overview' : '/repositories');
}

function openModelSettings() {
  void router.push('/settings');
}

async function resolveReference(reference: CodeReference) {
  const branchId = reference.branchId ?? conversation.activeAnswer.value?.branchId;
  return branchId ? branchesApi.context(reference.repositoryId, branchId) : branchContext.context;
}
async function openCode(reference: CodeReference) {
  try {
    rememberConversation();
    await selectTargetRepository(reference.repositoryId);
    const context = await resolveReference(reference);
    await router.push({ name: 'search', query: {
      branchId: context?.branchId, contextId: context?.contextId,
      contentVersion: reference.contentVersion ?? undefined,
      path: reference.filePath, startLine: String(reference.startLine ?? 1), endLine: String(reference.endLine ?? reference.startLine ?? 1),
    }});
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法打开源码'); }
}

async function openKnowledge(citation: Citation, sourceBranchId?: string | null) {
  if (!citation.knowledgeCardId) return;
  try {
    rememberConversation();
    await selectTargetRepository(citation.repositoryId);
    const branchId = sourceBranchId ?? branchContext.context?.branchId;
    await router.push({ name: 'knowledge', query: { cardId: citation.knowledgeCardId, branchId } });
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法打开知识卡片'); }
}

async function openGraph(reference: CodeReference) {
  try {
    await selectTargetRepository(reference.repositoryId);
    rememberConversation();
    const context = await resolveReference(reference);
    const target = reference.chunkId
      ? await intelligenceApi.graphTarget(reference.repositoryId, reference.chunkId, context?.contextId)
      : { symbol: reference.symbolName || reference.filePath };
    await router.push({ name: 'search', query: {
      path: ('filePath' in target ? target.filePath : null) || reference.filePath,
      startLine: String(('startLine' in target ? target.startLine : null) ?? reference.startLine ?? 1),
      contentVersion: reference.contentVersion ?? undefined,
      symbol: target.symbol,
      branchId: context?.branchId, contextId: context?.contextId,
      depth: '3',
      relation: '1',
    } });
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法解析图谱目标'); }
}


watch(
  () => [route.name, route.query.q] as const,
  ([routeName, routeQuestion]) => {
    if (routeName !== 'ask' || typeof routeQuestion !== 'string' || !routeQuestion.trim()) return;
    conversation.reset();
    conversation.question.value = routeQuestion.trim();
  },
  { immediate: true },
);
watch(() => [repositories.selectedRepositoryId, branchContext.identity] as const, ([repositoryId]) => loadContext(repositoryId));
onMounted(async () => {
  if (!repositories.repositories.length) await repositories.loadRepositories();
  await loadContext(repositories.selectedRepositoryId);
});
</script>

<template>
  <section class="qa-page" :class="{ 'history-closed': !historyOpen }">
    <header class="qa-command surface">
      <el-button :aria-expanded="historyOpen" @click="historyOpen = !historyOpen">{{ historyOpen ? '收起历史' : '展开历史' }}</el-button>
      <div class="scope-copy">
        <span>问答范围</span>
        <strong>{{ repository?.name ?? '未选择项目' }}</strong>
        <small>{{ branchContext.context?.branchName ?? repository?.branch ?? '无分支' }}<template v-if="branchContext.context?.commitSha ?? repository?.commit"> · {{ (branchContext.context?.commitSha ?? repository?.commit)?.slice(0, 8) }}</template></small>
      </div>
      <el-tag :type="readinessCopy?.type" effect="plain" round>{{ readinessCopy?.label }}</el-tag>
      <div v-if="!repository || !canAsk" class="command-notice">
        <span>{{ repository ? '当前项目还没有可检索的代码内容。' : '先选择项目才能开始问答。' }}</span>
        <el-button link type="primary" @click="openReadinessAction">{{ repository ? '去准备项目' : '选择项目' }}</el-button>
      </div>
      <div v-else-if="!modelsLoading && !selectedModel" class="command-notice">
        <span>本地证据模式：返回源码摘录与引用，不生成模型推理。</span>
        <el-button v-if="auth.isAdmin" link type="primary" @click="openModelSettings">配置并检测模型</el-button>
      </div>
      <div class="command-actions">
        <div class="model-selector">
          <span>回答方式</span>
          <el-select
            v-model="selectedModelId"
            :loading="modelsLoading"
            placeholder="本地证据模式"
            aria-label="问答模型"
          >
            <el-option label="本地证据 · 无需问答模型" value="" />
            <el-option
              v-for="item in askModels"
              :key="item.id"
              :label="`${item.name} · ${item.model}`"
              :value="item.id"
              :disabled="!item.available"
            >
              <span>{{ item.name }} · {{ item.model }}</span>
              <small>{{ item.available ? '可用' : statusLabel(item.availability) }}</small>
            </el-option>
          </el-select>
        </div>
        <el-button :icon="Plus" type="primary" plain @click="conversation.reset()">新会话</el-button>
      </div>
    </header>

    <AskHistorySidebar v-show="historyOpen" :records="history" :active-thread-id="conversation.threadId.value" :loading="historyLoading"
      @open="openHistory" @refresh="reloadHistory" @rename="renameHistory" @delete="deleteHistory" />

    <AskConversationPanel
      v-model="conversation.question.value"
      :turns="conversation.turns.value"
      :scroll-top="readingTop" :expanded-evidence="expandedEvidence"
      @scroll-position="readingTop = $event" @expand-evidence="expandEvidence"
      :active-answer-id="conversation.activeAnswerId.value"
      :restored-thread-id="conversation.threadId.value"
      :pending-question="conversation.pendingQuestion.value"
      :request-state="conversation.requestState.value"
      :error="conversation.error.value"
      :disabled="!repository || !canAsk"
      @send="send" @retry="retry" @select-answer="conversation.selectAnswer"
      @open-knowledge="openKnowledge" @open-code="openCode" @open-graph="openGraph"
    />

  </section>
</template>

<style scoped>
.qa-page { display:grid; grid-template-columns:280px minmax(0,1fr); grid-template-rows:auto minmax(0,1fr); gap:12px; min-height:0; height:100%; }
.qa-command { grid-column:1/-1; display:flex; min-height:62px; align-items:center; gap:12px; padding:9px 14px; border:1px solid #dedee3; border-radius:7px; background:#fff; }
.scope-copy { display:grid; grid-template-columns:auto auto; align-items:baseline; gap:2px 9px; min-width:0; }.scope-copy>span { white-space:nowrap; grid-row:1/3; align-self:center; padding-right:10px; color:var(--app-color-action); border-right:2px solid #90bde5; font-size: 13px; font-weight:700; letter-spacing:.08em; }.scope-copy strong { overflow:hidden; color:#2d3035; font-size:15px; text-overflow:ellipsis; white-space:nowrap; }.scope-copy small { color: var(--app-text-muted); font-size: 13px; }
.command-notice { display:flex; align-items:center; gap:4px; margin:0; color:#7b5a1b; font-size:13px; }
.command-notice small { color:var(--app-text-muted); }
.command-actions { display:flex; gap:8px; margin-left:auto; }
.model-selector { display:flex; align-items:center; gap:7px; }.model-selector>span { color: var(--app-text-muted); font-size: 13px; white-space:nowrap; }.model-selector :deep(.el-select) { width:240px; }.model-selector :deep(.el-select-dropdown__item) { display:flex; justify-content:space-between; gap:12px; }.model-selector small { color: var(--app-text-muted); }
@media (max-width:900px) {
  .qa-page { grid-template-columns:1fr; grid-template-rows:auto auto minmax(420px,1fr); gap:10px; overflow:auto; }
  .qa-command { grid-column:1; }
  .command-notice { width:100%; order:3; }
  .scope-copy { flex: 1 1 200px; }
  .scope-copy strong { white-space:normal; overflow-wrap:anywhere; }
  .scope-copy small { overflow-wrap:anywhere; }
  .qa-page.history-closed { grid-template-rows:auto minmax(420px,1fr); }
}
@media (max-width:760px) { .qa-page { height:auto; }.qa-command { flex-wrap:wrap; }.scope-copy { flex:1 0 100%; order:-1; grid-template-columns:auto minmax(0,1fr); }.command-actions { width:100%; margin-left:0; }.model-selector { flex:1; }.model-selector :deep(.el-select) { width:100%; }.command-actions .el-button { flex:0 0 auto; } }
.qa-page.history-closed { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); }
@media (max-width: 1200px) { .qa-command { flex-wrap: wrap; } }
</style>
