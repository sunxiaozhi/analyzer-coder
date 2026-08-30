<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import { computed, onMounted, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ApiError } from '@/api/http';
import {
  intelligenceApi,
  type CardInput,
  type CardRevision,
  type CodeReference,
  type KnowledgeCard,
  type KnowledgeDriftEvent,
  type MarkdownKnowledgeSource,
  type MarkdownKnowledgeSourceList as MarkdownKnowledgeSourceOverview,
  type MarkdownKnowledgeSourceStatus,
} from '@/api/intelligence';
import KnowledgeCardDetailDialog from '@/features/knowledge/KnowledgeCardDetailDialog.vue';
import KnowledgeCardEditorDialog from '@/features/knowledge/KnowledgeCardEditorDialog.vue';
import KnowledgeCardListItem from '@/features/knowledge/KnowledgeCardListItem.vue';
import MarkdownKnowledgeSourceList from '@/features/knowledge/MarkdownKnowledgeSourceList.vue';
import { renderMarkdown } from '@/features/knowledge/markdown';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { statusLabel } from '@/utils/displayLabels';

const repositories = useRepositoryStore();
const router = useRouter();
const route = useRoute();
type KnowledgeMode = 'cards' | 'markdown';
const activeMode = shallowRef<KnowledgeMode>('cards');
const cards = shallowRef<KnowledgeCard[]>([]);
const markdownSources = shallowRef<MarkdownKnowledgeSourceOverview | null>(null);
const cardQuery = shallowRef('');
const sourceQuery = shallowRef('');
const allCardTypes = '__ALL__';
const allSourceStatuses = '__ALL__';
const selectedType = shallowRef(allCardTypes);
const selectedSourceStatus = shallowRef<MarkdownKnowledgeSourceStatus | typeof allSourceStatuses>(allSourceStatuses);
const dialog = shallowRef(false);
const detailDialog = shallowRef(false);
const historyDialog = shallowRef(false);
const busy = shallowRef(false);
const cardsLoading = shallowRef(false);
const sourcesLoading = shallowRef(false);
const sourceBusyPath = shallowRef<string | null>(null);
const bulkGenerating = shallowRef(false);
const sourceLoadError = shallowRef<string | null>(null);
const editing = shallowRef<KnowledgeCard | null>(null);
const viewing = shallowRef<KnowledgeCard | null>(null);
const driftEvent = shallowRef<KnowledgeDriftEvent | null>(null);
const driftLoading = shallowRef(false);
const sourceReviewLoading = shallowRef(false);
const historyCard = shallowRef<KnowledgeCard | null>(null);
const revisions = shallowRef<CardRevision[]>([]);
const emptySourceCounts = { total: 0, pending: 0, current: 0, stale: 0 };
const canMaintain = computed(() => repositories.selectedRepository?.capabilities.canUpdate ?? false);
const canManage = computed(() => repositories.selectedRepository?.capabilities.canConfigure ?? false);
const cardTypes = computed(() => [...new Set(cards.value
  .map(card => card.cardType?.trim())
  .filter((value): value is string => Boolean(value)))]
  .sort((left, right) => left.localeCompare(right, 'zh-CN')));
const cardRows = computed(() => cards.value.filter(card => {
  const value = cardQuery.value.trim().toLowerCase();
  const matchesTitle = !value || card.title.toLowerCase().includes(value);
  const matchesType = selectedType.value === allCardTypes || card.cardType === selectedType.value;
  return matchesTitle && matchesType;
}));
const sourceRows = computed(() => (markdownSources.value?.items ?? []).filter(source => {
  const value = sourceQuery.value.trim().toLowerCase();
  const matchesQuery = !value
    || source.title.toLowerCase().includes(value)
    || source.sourcePath.toLowerCase().includes(value);
  const matchesStatus = selectedSourceStatus.value === allSourceStatuses
    || source.status === selectedSourceStatus.value;
  return matchesQuery && matchesStatus;
}));
const cardEmptyDescription = computed(() => {
  if (!repositories.selectedRepositoryId) return '请先选择仓库';
  if (!cards.value.length) return '当前仓库暂无知识卡片';
  return '没有符合筛选条件的知识卡片';
});
const sourceEmptyDescription = computed(() => {
  if (!repositories.selectedRepositoryId) return '请先选择仓库';
  if (sourceLoadError.value) return sourceLoadError.value;
  if (!markdownSources.value?.items.length) return '当前快照未发现 Markdown 文件，仓库重新扫描后会自动更新';
  return '没有符合筛选条件的 Markdown';
});

async function loadCards() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) {
    cards.value = [];
    return;
  }
  cardsLoading.value = true;
  try {
    cards.value = await intelligenceApi.cards(repositoryId);
    syncRequestedCard();
  } catch (error) {
    cards.value = [];
    ElMessage.error(error instanceof Error ? error.message : '知识卡片加载失败');
  } finally {
    cardsLoading.value = false;
  }
}

async function loadMarkdownSources() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) {
    markdownSources.value = null;
    sourceLoadError.value = null;
    return;
  }
  sourcesLoading.value = true;
  sourceLoadError.value = null;
  try {
    markdownSources.value = await intelligenceApi.markdownSources(repositoryId);
  } catch (error) {
    markdownSources.value = null;
    sourceLoadError.value = error instanceof ApiError && error.status === 409
      ? '仓库尚无可用快照，请先完成仓库扫描'
      : error instanceof Error ? error.message : 'Markdown 预备知识加载失败';
  } finally {
    sourcesLoading.value = false;
  }
}

async function load() {
  await Promise.all([loadCards(), loadMarkdownSources()]);
}

function syncRequestedCard() {
  const cardId = typeof route.query.cardId === 'string' ? route.query.cardId : null;
  if (!cardId) return;
  const card = cards.value.find(item => item.id === cardId);
  if (!card) return;
  activeMode.value = 'cards';
  viewing.value = card;
  detailDialog.value = true;
  void loadDrift(card);
}
function openCreate() { editing.value = null; dialog.value = true; }
function openEdit(card: KnowledgeCard) { editing.value = card; dialog.value = true; }
function openDetail(card: KnowledgeCard) {
  viewing.value = card;
  detailDialog.value = true;
  void loadDrift(card);
}
async function loadDrift(card: KnowledgeCard) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  driftEvent.value = null;
  driftLoading.value = true;
  try {
    const result = await intelligenceApi.sourceDrift(repositoryId, card.id);
    if (repositoryId === repositories.selectedRepositoryId && viewing.value?.id === card.id) {
      driftEvent.value = result;
    }
  } catch (error) {
    if (repositoryId === repositories.selectedRepositoryId) {
      ElMessage.error(error instanceof Error ? error.message : '知识漂移证据加载失败');
    }
  } finally {
    if (repositoryId === repositories.selectedRepositoryId) driftLoading.value = false;
  }
}
function openDrift(event: KnowledgeDriftEvent) {
  detailDialog.value = false;
  void router.push({
    name: 'change-impact',
    query: {
      source: 'COMMIT_RANGE',
      baseRef: event.fromCommit ?? undefined,
      headRef: event.toCommit ?? undefined,
      task: `核对知识“${viewing.value?.title ?? event.cardId}”的来源漂移证据`,
    },
  });
}
async function sourceReview(action: 'CONFIRM_CURRENT' | 'MARK_STALE') {
  const repositoryId = repositories.selectedRepositoryId;
  const card = viewing.value;
  if (!repositoryId || !card) return;
  const confirming = action === 'CONFIRM_CURRENT';
  try {
    const prompt = await ElMessageBox.prompt(
      confirming
        ? '说明你核对了哪些当前代码事实。确认后将绑定当前 Commit 和 Snapshot。'
        : '说明知识的哪部分已经不再适用于当前代码。',
      confirming ? '确认知识仍然有效' : '确认知识已经失效',
      {
        confirmButtonText: confirming ? '确认当前' : '标记失效',
        cancelButtonText: '取消',
        inputPlaceholder: confirming ? '例如：已核对当前退款审批实现和测试要求' : '例如：审批流程已被新规则替代',
        inputValidator: value => {
          const length = value.trim().length;
          return length >= 1 && length <= 1000 ? true : '请输入 1 到 1000 个字符的复核说明';
        },
      },
    );
    sourceReviewLoading.value = true;
    const response = await intelligenceApi.reviewKnowledgeSource(
      repositoryId,
      card.id,
      action,
      card.revision,
      prompt.value.trim(),
    );
    cards.value = cards.value.map(item => item.id === response.card.id ? response.card : item);
    viewing.value = response.card;
    driftEvent.value = response.event;
    ElMessage.success(confirming ? '已绑定当前代码版本' : '知识已标记为失效');
  } catch (error) {
    if (error instanceof Error) {
      if (error instanceof ApiError && error.code === 'KNOWLEDGE_REVISION_CONFLICT') {
        await loadCards();
        ElMessage.warning('知识修订已变化，列表已刷新，请重新打开后核对');
      } else {
        ElMessage.error(error.message);
      }
    }
  } finally {
    sourceReviewLoading.value = false;
  }
}
function openCode(reference: CodeReference) {
  detailDialog.value = false;
  dialog.value = false;
  void router.push({
    name: 'search',
    query: {
      path: reference.filePath,
      startLine: String(reference.startLine ?? 1),
      endLine: String(reference.endLine ?? reference.startLine ?? 1),
    },
  });
}

function openMarkdown(source: MarkdownKnowledgeSource) {
  void router.push({ name: 'search', query: { path: source.sourcePath } });
}

async function openGeneratedCard(source: MarkdownKnowledgeSource) {
  if (!source.cardId) return;
  let card = cards.value.find(item => item.id === source.cardId);
  if (!card) {
    await loadCards();
    card = cards.value.find(item => item.id === source.cardId);
  }
  if (!card) {
    ElMessage.error('关联知识卡片不存在或当前账号无权查看');
    return;
  }
  openDetail(card);
}

async function confirmStaleSync(source: MarkdownKnowledgeSource) {
  if (source.status !== 'STALE') return true;
  try {
    await ElMessageBox.confirm(
      `“${source.title}”的 Markdown 已变化。同步会为原知识卡片创建新修订，历史内容仍会保留。`,
      '同步 Markdown 变更',
      { type: 'warning', confirmButtonText: '同步为新修订' },
    );
    return true;
  } catch {
    return false;
  }
}

async function generateMarkdownCard(source: MarkdownKnowledgeSource) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId || source.status === 'CURRENT' || !(await confirmStaleSync(source))) return;
  sourceBusyPath.value = source.sourcePath;
  try {
    const card = await intelligenceApi.generateMarkdownSource(repositoryId, {
      sourcePath: source.sourcePath,
      expectedSnapshotId: source.sourceSnapshotId,
      expectedContentHash: source.sourceContentHash,
    });
    await Promise.all([loadCards(), loadMarkdownSources()]);
    ElMessage.success(source.status === 'STALE'
      ? `已同步为知识卡片 v${card.revision}`
      : '已生成知识卡片草稿');
  } catch (error) {
    if (error instanceof ApiError && (error.status === 409 || error.code === 'MARKDOWN_SOURCE_CHANGED')) {
      await loadMarkdownSources();
      ElMessage.warning('Markdown 已发生变化，列表已刷新，请确认最新内容后重试');
    } else {
      ElMessage.error(error instanceof Error ? error.message : '知识卡片生成失败');
    }
  } finally {
    sourceBusyPath.value = null;
  }
}

async function generateAllPending() {
  const repositoryId = repositories.selectedRepositoryId;
  const expectedSnapshotId = markdownSources.value?.snapshotId;
  const pending = markdownSources.value?.counts.pending ?? 0;
  if (!repositoryId || !expectedSnapshotId || pending <= 0) return;
  try {
    await ElMessageBox.confirm(
      `将 ${pending} 个待处理 Markdown 生成知识卡片草稿。已生成和已过期内容不会被修改。`,
      '批量生成知识卡片',
      { type: 'info', confirmButtonText: `生成 ${pending} 个草稿` },
    );
  } catch {
    return;
  }
  bulkGenerating.value = true;
  try {
    const result = await intelligenceApi.generatePendingMarkdownSources(repositoryId, expectedSnapshotId);
    await Promise.all([loadCards(), loadMarkdownSources()]);
    ElMessage.success(result.generated > 0
      ? result.remaining > 0
        ? `已生成 ${result.generated} 个草稿，剩余 ${result.remaining} 个可继续分批生成`
        : `已生成 ${result.generated} 个知识卡片草稿`
      : '没有新的 Markdown 需要生成');
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      await loadMarkdownSources();
      ElMessage.warning('仓库快照已变化，列表已刷新，请确认最新待处理内容后重试');
    } else {
      ElMessage.error(error instanceof Error ? error.message : '批量生成失败');
    }
  } finally {
    bulkGenerating.value = false;
  }
}

async function openGraph(reference: CodeReference) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  try {
    const target = reference.chunkId
      ? await intelligenceApi.graphTarget(repositoryId, reference.chunkId)
      : { symbol: reference.symbolName || reference.filePath, filePath: reference.filePath, startLine: reference.startLine };
    detailDialog.value = false;
    await router.push({ name: 'search', query: {
      path: target.filePath || reference.filePath,
      startLine: String(target.startLine ?? reference.startLine ?? 1),
      symbol: target.symbol,
      depth: '3',
      relation: '1',
    } });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法解析图谱目标');
  }
}
async function save(input: CardInput) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  busy.value = true;
  try {
    if (editing.value) await intelligenceApi.updateCard(repositoryId, editing.value.id, input);
    else await intelligenceApi.createCard(repositoryId, input);
    dialog.value = false;
    await load();
    ElMessage.success(editing.value ? '新修订已保存' : '知识卡片已创建');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally { busy.value = false; }
}
async function reviewCard(card: KnowledgeCard, reviewStatus: 'APPROVED' | 'CHANGES_REQUESTED') {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  const action = reviewStatus === 'APPROVED' ? '通过人工评审' : '标记为要求修改';
  try {
    await ElMessageBox.confirm(`${action}“${card.title}”？该操作不会自动改变发布状态。`, '人工评审', { type: 'warning' });
    await intelligenceApi.reviewCard(repositoryId, card.id, reviewStatus);
    await loadCards();
    ElMessage.success(`${action}完成`);
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message);
  }
}
async function setPublication(card: KnowledgeCard, publicationStatus: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED') {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  const action = publicationStatus === 'PUBLISHED' ? '发布' : publicationStatus === 'ARCHIVED' ? '归档' : '撤回为草稿';
  try {
    await ElMessageBox.confirm(`${action}“${card.title}”？`, '发布状态', { type: 'warning' });
    await intelligenceApi.setCardPublication(repositoryId, card.id, publicationStatus);
    await loadCards();
    ElMessage.success(`${action}完成`);
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message);
  }
}
async function showHistory(card: KnowledgeCard) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  historyCard.value = card;
  revisions.value = await intelligenceApi.cardHistory(repositoryId, card.id);
  historyDialog.value = true;
}
async function restore(revision: number) {
  const repositoryId = repositories.selectedRepositoryId, card = historyCard.value;
  if (!repositoryId || !card) return;
  await ElMessageBox.confirm(`把 v${revision} 恢复为新的草稿修订？当前历史和附件不会被覆盖。`, '恢复历史修订', { type: 'warning' });
  await intelligenceApi.restoreCardRevision(repositoryId, card.id, revision);
  revisions.value = await intelligenceApi.cardHistory(repositoryId, card.id);
  await load();
  historyCard.value = cards.value.find(item => item.id === card.id) ?? null;
  ElMessage.success('历史内容及附件已恢复为新草稿');
}
watch(() => repositories.selectedRepositoryId, () => {
  selectedType.value = allCardTypes;
  selectedSourceStatus.value = allSourceStatuses;
  cardQuery.value = '';
  sourceQuery.value = '';
  viewing.value = null;
  driftEvent.value = null;
  detailDialog.value = false;
  void load();
});
watch(() => route.query.cardId, syncRequestedCard);
onMounted(() => void load());
</script>

<template>
  <section class="page knowledge-page">
    <div class="surface knowledge-surface">
      <div class="toolbar">
        <div class="knowledge-mode-switch" role="tablist" aria-label="知识内容类型">
          <button
            type="button"
            role="tab"
            :aria-selected="activeMode === 'cards'"
            :class="{ active: activeMode === 'cards' }"
            @click="activeMode = 'cards'"
          >
            知识卡片 <span>{{ cards.length }}</span>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeMode === 'markdown'"
            :class="{ active: activeMode === 'markdown' }"
            @click="activeMode = 'markdown'"
          >
            Markdown 预备知识 <span>{{ markdownSources?.counts.total ?? 0 }}</span>
          </button>
        </div>

        <el-input
          v-if="activeMode === 'cards'"
          v-model="cardQuery"
          class="app-search-input knowledge-search"
          :prefix-icon="Search"
          placeholder="搜索卡片标题"
          clearable
        />
        <el-input
          v-else
          v-model="sourceQuery"
          class="app-search-input knowledge-search"
          :prefix-icon="Search"
          placeholder="搜索 Markdown 标题或路径"
          clearable
        />
        <el-select
          v-if="activeMode === 'cards'"
          v-model="selectedType"
          class="knowledge-type-filter"
          placeholder="全部类型"
          aria-label="按知识类型筛选"
          clearable
          @clear="selectedType = allCardTypes"
        >
          <el-option label="全部类型" :value="allCardTypes" />
          <el-option v-for="type in cardTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-select
          v-else
          v-model="selectedSourceStatus"
          class="knowledge-status-filter"
          aria-label="按 Markdown 处理状态筛选"
        >
          <el-option label="全部状态" :value="allSourceStatuses" />
          <el-option label="待生成" value="PENDING" />
          <el-option label="已生成" value="CURRENT" />
          <el-option label="已过期" value="STALE" />
        </el-select>
        <span class="spacer" />
        <el-button
          v-if="activeMode === 'cards'"
          type="primary"
          :icon="Plus"
          :disabled="!repositories.selectedRepositoryId || !canMaintain"
          @click="openCreate"
        >
          新建卡片
        </el-button>
        <el-button
          v-else
          type="primary"
          :loading="bulkGenerating"
          :disabled="!repositories.selectedRepositoryId
            || !canMaintain
            || (markdownSources?.counts.pending ?? 0) === 0
            || sourceBusyPath !== null"
          @click="generateAllPending"
        >
          生成待处理（{{ markdownSources?.counts.pending ?? 0 }}）
        </el-button>
      </div>

      <div
        v-if="activeMode === 'cards'"
        class="knowledge-scroll"
        role="tabpanel"
        v-loading="cardsLoading"
      >
        <el-empty v-if="!cardRows.length" :description="cardEmptyDescription" />
        <div v-else class="knowledge-grid">
          <KnowledgeCardListItem
            v-for="card in cardRows"
            :key="card.id"
            :card="card"
            :can-manage="canManage"
            @view="openDetail"
            @edit="openEdit"
            @history="showHistory"
            @review="reviewCard"
            @publish="setPublication"
          />
        </div>
      </div>
      <div
        v-else
        class="knowledge-scroll markdown-source-pane"
        role="tabpanel"
        v-loading="sourcesLoading"
      >
        <MarkdownKnowledgeSourceList
          :items="sourceRows"
          :counts="markdownSources?.counts ?? emptySourceCounts"
          :snapshot-id="markdownSources?.snapshotId ?? null"
          :busy-path="sourceBusyPath"
          :bulk-busy="bulkGenerating"
          :can-generate="canMaintain"
          :empty-description="sourceEmptyDescription"
          @generate="generateMarkdownCard"
          @view-card="openGeneratedCard"
          @view-markdown="openMarkdown"
        />
      </div>
    </div>
    <KnowledgeCardDetailDialog
      v-model="detailDialog"
      :card="viewing"
      :drift-event="driftEvent"
      :drift-loading="driftLoading"
      :can-maintain="canMaintain"
      :source-review-loading="sourceReviewLoading"
      @open-code="openCode"
      @open-graph="openGraph"
      @open-drift="openDrift"
      @source-review="sourceReview"
    />
    <KnowledgeCardEditorDialog v-if="repositories.selectedRepositoryId" v-model="dialog"
      :repository-id="repositories.selectedRepositoryId" :card="editing" :busy="busy"
      @submit="save" @open-code="openCode" />
    <el-dialog v-model="historyDialog" :title="`${historyCard?.title??''} · 修订历史`" width="760">
      <el-timeline><el-timeline-item v-for="item in revisions" :key="item.revision" :timestamp="new Date(item.changedAt).toLocaleString()" placement="top">
        <el-card shadow="never"><template #header><div class="toolbar"><b>v{{ item.revision }} · {{ statusLabel(item.publicationStatus) }}</b><span class="spacer" /><el-button link type="primary" @click="restore(item.revision)">恢复为新草稿</el-button></div></template>
          <div class="history-markdown" v-html="renderMarkdown(item.content, item.repositoryId)" />
          <small>{{ item.knowledgeKind }} · {{ item.enforcement }} · {{ item.cardType }} · {{ item.tags.join(', ')||'无标签' }}</small>
        </el-card>
      </el-timeline-item></el-timeline>
    </el-dialog>
  </section>
</template>

<style scoped>
.knowledge-page {
  display: grid !important;
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}
.knowledge-surface {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  row-gap: 12px;
  min-height: 0;
  overflow: hidden !important;
}
.knowledge-scroll {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.knowledge-surface > .toolbar {
  flex-wrap: wrap;
}
.knowledge-mode-switch {
  display: flex;
  flex: none;
  gap: 2px;
  padding: 3px;
  border: 1px solid #dde1e5;
  border-radius: 6px;
  background: #f3f5f7;
}
.knowledge-mode-switch button {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  color: #59636d;
  border: 0;
  border-radius: 4px;
  background: transparent;
  font-size: 12px;
  font-weight: 550;
}
.knowledge-mode-switch button:hover { color: #1d1d1f; }
.knowledge-mode-switch button:focus-visible {
  outline: 2px solid #80b8eb;
  outline-offset: 1px;
}
.knowledge-mode-switch button.active {
  color: #005eb8;
  background: #fff;
  box-shadow: 0 1px 3px rgb(26 39 54 / 12%);
}
.knowledge-mode-switch button span {
  display: inline-grid;
  min-width: 20px;
  height: 18px;
  place-items: center;
  padding: 0 5px;
  color: #66717c;
  border-radius: 9px;
  background: #e7eaed;
  font-size: 11px;
}
.knowledge-mode-switch button.active span {
  color: #005eb8;
  background: #eaf3fd;
}
.knowledge-scroll .knowledge-grid {
  padding-top: 0;
}
.knowledge-type-filter,
.knowledge-status-filter {
  width: 168px;
}
.markdown-source-pane { padding-bottom: 12px; }
.history-markdown :deep(pre){overflow:auto;padding:10px;border-radius:8px;background:#18212f;color:#e6edf3}.history-markdown{line-height:1.7}
@media (max-width: 760px) {
  .knowledge-page,
  .knowledge-surface {
    display: block !important;
    height: auto;
    overflow: visible !important;
  }
  .knowledge-scroll { overflow: visible; }
  .knowledge-surface { row-gap: 12px; }
  .knowledge-surface > .toolbar { align-items: stretch; }
  .knowledge-mode-switch { width: 100%; }
  .knowledge-mode-switch button { flex: 1; justify-content: center; }
  .knowledge-search,
  .knowledge-type-filter,
  .knowledge-status-filter { width: 100% !important; }
  .knowledge-surface > .toolbar .spacer { display: none; }
  .knowledge-surface > .toolbar > .el-button { width: 100%; margin-left: 0; }
  .markdown-source-pane { padding-bottom: 0; }
}
</style>
