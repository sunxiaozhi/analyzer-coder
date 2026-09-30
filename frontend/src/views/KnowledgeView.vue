<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue';
import { BookOpenCheck } from 'lucide-vue-next';
import { useRoute, useRouter } from 'vue-router';
import { computed, nextTick, onMounted, onBeforeUnmount, onActivated, onDeactivated, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ApiError } from '@/api/http';
import { branchesApi, type BranchValidationCard, type BranchValidationState } from '@/api/branches';
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
import { usePageMemoryStore } from '@/stores/pageMemory';
import { branchScopeLabel } from '@/features/knowledge/knowledgePresentation';
import { cardForBranch } from '@/features/knowledge/knowledgeBranchScope';
import KnowledgeCardDetailDialog from '@/features/knowledge/KnowledgeCardDetailDialog.vue';
import KnowledgeCardEditorDialog from '@/features/knowledge/KnowledgeCardEditorDialog.vue';
import KnowledgeCardListItem from '@/features/knowledge/KnowledgeCardListItem.vue';
import MarkdownKnowledgeSourceList from '@/features/knowledge/MarkdownKnowledgeSourceList.vue';
import { renderMarkdown } from '@/features/knowledge/markdown';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { useBranchReadScope } from '@/features/branches/useBranchReadScope';
import { enforcementLabel, knowledgeKindLabel, statusLabel } from '@/utils/displayLabels';

const repositories = useRepositoryStore();
const branchContext = useBranchContextStore();
const readScope = useBranchReadScope();
let cardsVersion=0;
let sourcesVersion=0;
let driftVersion=0;
let saveVersion=0;
const router = useRouter();
const route = useRoute();
type KnowledgeMode = 'cards' | 'markdown';
const activeMode = shallowRef<KnowledgeMode>('cards');
const cards = shallowRef<KnowledgeCard[]>([]);
const branchValidations = shallowRef<BranchValidationCard[]>([]);
const validationFilter = shallowRef<BranchValidationState | 'ALL'>('ALL');
const validationLabels: Record<BranchValidationState, string> = { CURRENT: '已验证', UNVERIFIED: '未验证', REVIEW_REQUIRED: '待复核', INVALID: '不适用' };
function validationState(card: KnowledgeCard): BranchValidationState {
  return branchValidations.value.find(item => item.cardId === card.id && item.revision === card.revision)?.state ?? 'UNVERIFIED';
}
const markdownSources = shallowRef<MarkdownKnowledgeSourceOverview | null>(null);
const cardQuery = shallowRef('');
const sourceQuery = shallowRef('');
const allKnowledgeKinds = '__ALL__';
const allSourceStatuses = '__ALL__';
const selectedKnowledgeKind = shallowRef<KnowledgeCard['knowledgeKind'] | typeof allKnowledgeKinds>(allKnowledgeKinds);
const selectedSourceStatus = shallowRef<MarkdownKnowledgeSourceStatus | typeof allSourceStatuses>(allSourceStatuses);
const dialog = shallowRef(false);
const detailDialog = shallowRef(false);
const historyDialog = shallowRef(false);
const busy = shallowRef(false);
const saveError = shallowRef<string | null>(null);
const cardLoadError = shallowRef<string | null>(null);
const cardsLoading = shallowRef(false);
const sourcesLoading = shallowRef(false);
const sourceBusyPath = shallowRef<string | null>(null);
const bulkGenerating = shallowRef(false);
const sourceLoadError = shallowRef<string | null>(null);
const editing = shallowRef<KnowledgeCard | null>(null);
const initialReference = shallowRef<{
  filePath: string;
  symbolName: string | null;
  contentVersion: string | null;
} | null>(null);
const viewing = shallowRef<KnowledgeCard | null>(null);
const driftEvent = shallowRef<KnowledgeDriftEvent | null>(null);
const driftLoading = shallowRef(false);
const sourceReviewLoading = shallowRef(false);
const historyCard = shallowRef<KnowledgeCard | null>(null);
const revisions = shallowRef<CardRevision[]>([]);
let handledCreateRequest = '';
const memory = usePageMemoryStore();
const listElement = shallowRef<HTMLElement | null>(null);
const scrollTop = shallowRef(0);
const resumeCardId = shallowRef<string | null>(null);
const selectedCardId = shallowRef<string | null>(null);
let triggerElement: HTMLElement | null = null;
const pageKey = () => `knowledge:${repositories.selectedRepositoryId}:${branchContext.selectedBranchId ?? 'default'}`;
let stateKey = pageKey();
type ReadingState = { query: string; sourceQuery: string; kind: typeof selectedKnowledgeKind.value; sourceStatus: typeof selectedSourceStatus.value; validation: typeof validationFilter.value; mode: KnowledgeMode; top: number; selected: string | null; resume: string | null };
function remember() {
  memory.write<ReadingState>(stateKey, { query: cardQuery.value, sourceQuery: sourceQuery.value, kind: selectedKnowledgeKind.value,
    sourceStatus: selectedSourceStatus.value, validation: validationFilter.value, mode: activeMode.value,
    top: scrollTop.value, selected: selectedCardId.value, resume: resumeCardId.value });
}
function restoreReadingState() {
  const state = memory.read<ReadingState>(stateKey);
  cardQuery.value = state?.query ?? ''; sourceQuery.value = state?.sourceQuery ?? '';
  selectedKnowledgeKind.value = state?.kind ?? allKnowledgeKinds;
  selectedSourceStatus.value = state?.sourceStatus ?? allSourceStatuses;
  validationFilter.value = state?.validation ?? 'ALL'; activeMode.value = state?.mode ?? 'cards';
  scrollTop.value = state?.top ?? 0; selectedCardId.value = state?.selected ?? null; resumeCardId.value = state?.resume ?? null;
}
restoreReadingState();
onBeforeUnmount(() => { remember(); cardsVersion++; sourcesVersion++; driftVersion++; saveVersion++; });
onDeactivated(remember);
onActivated(() => { const card = cards.value.find(item => item.id === resumeCardId.value); if (card) openDetail(card); void restoreListPosition(); });
function trackScroll(event: Event) { scrollTop.value = (event.target as HTMLElement).scrollTop; }
async function restoreListPosition() {
  await nextTick();
  if (listElement.value) listElement.value.scrollTop = scrollTop.value;
}
function detailClosed() {
  if (route.name === 'knowledge') resumeCardId.value = null;
  if (triggerElement?.isConnected) triggerElement.focus({ preventScroll: true });
}
async function locateCard(card: KnowledgeCard) {
  selectedCardId.value = card.id;
  await nextTick();
  const row = Array.from(listElement.value?.querySelectorAll<HTMLElement>('[data-card-id]') ?? [])
    .find(item => item.dataset.cardId === card.id);
  row?.scrollIntoView?.({ block: 'nearest' });
  row?.querySelector<HTMLButtonElement>('.card-title')?.focus({ preventScroll: true });
  if (!row) { viewing.value = card; detailDialog.value = true; }
}
const emptySourceCounts = { total: 0, pending: 0, current: 0, stale: 0 };
const canMaintain = computed(() => repositories.selectedRepository?.capabilities.canUpdate ?? false);
const canManage = computed(() => repositories.selectedRepository?.capabilities.canConfigure ?? false);
const knowledgeKinds = computed(() => [...new Set(cards.value.map(card => card.knowledgeKind))]
  .sort((left, right) => knowledgeKindLabel(left).localeCompare(knowledgeKindLabel(right), 'zh-CN')));
const cardRows = computed(() => cards.value.filter(card => {
  const value = cardQuery.value.trim().toLowerCase();
  const matchesTitle = !value || [card.title, card.content, ...card.tags].join(' ').toLowerCase().includes(value);
  const matchesType = selectedKnowledgeKind.value === allKnowledgeKinds
    || card.knowledgeKind === selectedKnowledgeKind.value;
  return matchesTitle && matchesType && (!readScope.requiresContext.value || validationFilter.value === 'ALL' || validationState(card) === validationFilter.value);
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
  if (!repositories.selectedRepositoryId) return '请先选择项目';
  if (!cards.value.length) return '当前项目暂无知识卡片';
  return '没有符合筛选条件的知识卡片';
});
const sourceEmptyDescription = computed(() => {
  if (!repositories.selectedRepositoryId) return '请先选择项目';
  if (sourceLoadError.value) return sourceLoadError.value;
  if (!markdownSources.value?.items.length) return '当前内容版本未发现 Markdown 文件，重新扫描项目代码后会自动更新';
  return '没有符合筛选条件的 Markdown';
});

async function branchValidationSaved() {
  const previous = branchContext.context;
  if (!previous) return;
  const viewingId = viewing.value?.id;
  const wasOpen = detailDialog.value;
  await branchContext.select(previous.branchId);
  if (repositories.selectedRepositoryId !== previous.repositoryId
    || branchContext.context?.branchId !== previous.branchId) return;
  await loadCards();
  if (viewingId) {
    viewing.value = cards.value.find(card => card.id === viewingId) ?? null;
    detailDialog.value = wasOpen && Boolean(viewing.value);
  }
}

async function loadCards() {
  const version=++cardsVersion;
  const identity=branchContext.identity;
  const repositoryId=repositories.selectedRepositoryId;
  cardLoadError.value=null; cardsLoading.value=false;
  if(!repositoryId || readScope.blocked.value) { cards.value=[]; branchValidations.value=[]; return; }
  const isCurrent=()=>version===cardsVersion && repositoryId===repositories.selectedRepositoryId && identity===branchContext.identity;
  cardsLoading.value=true;
  try {
    const contextId=branchContext.context?.contextId;
    const [loadedCards,loadedValidations]=await Promise.all([
      contextId?intelligenceApi.cards(repositoryId,contextId):intelligenceApi.cards(repositoryId),
      branchContext.context ? branchesApi.validations(branchContext.context) : Promise.resolve([]),
    ]);
    if(!isCurrent())return;
    cards.value=loadedCards.map(card => cardForBranch(card, branchContext.context, loadedValidations)); branchValidations.value=loadedValidations;
    syncRequestedCard(); syncRequestedCreate();
    if (resumeCardId.value && !route.query.cardId) { const card = cards.value.find(item => item.id === resumeCardId.value); if (card) openDetail(card); }
    await restoreListPosition();
  }catch(error){
    if(isCurrent()) { cardLoadError.value=error instanceof Error?error.message:'知识卡片加载失败'; cards.value=[]; branchValidations.value=[]; }
  }finally{if(isCurrent())cardsLoading.value=false;}
}

async function loadMarkdownSources() {
  const version=++sourcesVersion;
  const identity=branchContext.identity;
  const repositoryId=repositories.selectedRepositoryId;
  markdownSources.value=null; sourceLoadError.value=null; sourcesLoading.value=false;
  if(!repositoryId){activeMode.value='cards';return;}
  if(readScope.blocked.value){sourceLoadError.value=readScope.reason.value;return;}
  const isCurrent=()=>version===sourcesVersion && repositoryId===repositories.selectedRepositoryId && identity===branchContext.identity;
  sourcesLoading.value=true;
  try{
    const loaded=branchContext.context?.contextId
      ?await intelligenceApi.markdownSources(repositoryId,branchContext.context.contextId)
      :await intelligenceApi.markdownSources(repositoryId);
    if(isCurrent())markdownSources.value=loaded;
  }catch(error){
    if(isCurrent())sourceLoadError.value=error instanceof ApiError && error.status===409
      ?'当前分支尚无可用内容索引，请在项目管理中构建'
      :error instanceof Error?error.message:'Markdown 预备知识加载失败';
  }finally{if(isCurrent())sourcesLoading.value=false;}
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
function openCreate() { saveError.value = null; initialReference.value = null; editing.value = null; dialog.value = true; }
function scopeLabel(card: KnowledgeCard) {
  return branchScopeLabel(card.branchScope, branchContext.branches, branchContext.context?.branchName);
}
function openEdit(card: KnowledgeCard) { saveError.value = null; initialReference.value = null; editing.value = card; detailDialog.value = false; dialog.value = true; }
function syncRequestedCreate() {
  const path = typeof route.query.path === 'string' ? route.query.path : null;
  if (route.query.create !== '1' || !path || !canMaintain.value) return;
  const requestedContentVersion = typeof route.query.contentVersion === 'string' ? route.query.contentVersion : null;
  const requestedSymbol = typeof route.query.symbol === 'string' ? route.query.symbol : null;
  const requestKey = `${repositories.selectedRepositoryId}:${path}:${requestedContentVersion}:${requestedSymbol}`;
  if (handledCreateRequest === requestKey) return;
  handledCreateRequest = requestKey;
  activeMode.value = 'cards';
  initialReference.value = { filePath: path, symbolName: requestedSymbol, contentVersion: requestedContentVersion };
  editing.value = null;
  dialog.value = true;
}
function openDetail(card: KnowledgeCard) {
  triggerElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  selectedCardId.value = card.id;
  viewing.value = card;
  detailDialog.value = true;
  void loadDrift(card);
}
async function loadDrift(card: KnowledgeCard) {
  const repositoryId = repositories.selectedRepositoryId;
  const request = ++driftVersion;
  const identity = branchContext.identity;
  const isCurrent = () => request === driftVersion && repositoryId === repositories.selectedRepositoryId
    && identity === branchContext.identity && viewing.value?.id === card.id;
  if (!repositoryId) return;
  driftEvent.value = null;
  driftLoading.value = true;
  try {
    const result = await intelligenceApi.sourceDrift(repositoryId, card.id, branchContext.context?.contextId);
    if (isCurrent()) {
      driftEvent.value = result;
    }
  } catch (error) {
    if (isCurrent()) {
      ElMessage.error(error instanceof Error ? error.message : '知识漂移证据加载失败');
    }
  } finally {
    if (isCurrent()) driftLoading.value = false;
  }
}
function openDrift(event: KnowledgeDriftEvent) {
  detailDialog.value = false;
  const reason = event.reasons.find(item => item.filePath);
  if (!reason?.filePath) {
    ElMessage.info('该记录没有可定位的代码文件');
    return;
  }
  void router.push({
    name: 'search',
    query: {
      path: reason.filePath,
      startLine: reason.startLine ?? undefined,
      contentVersion: event.toContentVersion,
      branchId: branchContext.context?.branchId,
      contextId: branchContext.context?.contextId,
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
        ? '说明你核对了哪些当前代码事实。确认后将绑定当前提交版本和内容版本。'
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
      branchContext.context?.contextId,
    );
    cards.value = cards.value.map(item => item.id === response.card.id ? response.card : item);
    await branchValidationSaved();
    viewing.value = response.card;
    detailDialog.value = true;
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
async function referenceContext(reference: CodeReference) {
  const context = branchContext.context;
  if (!reference.branchId || reference.branchId === context?.branchId) return context;
  return branchesApi.context(reference.repositoryId, reference.branchId);
}
async function openCode(reference: CodeReference) {
  try {
  const context = await referenceContext(reference);
  resumeCardId.value = viewing.value?.id ?? null;
  remember();
  detailDialog.value = false;
  dialog.value = false;
  await router.push({
    name: 'search',
    query: {
      contentVersion: reference.contentVersion ?? undefined,
      path: reference.filePath,
      startLine: String(reference.startLine ?? 1),
      endLine: String(reference.endLine ?? reference.startLine ?? 1),
      branchId: context?.branchId,
      contextId: context?.contextId,
    },
  });
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法打开引用分支'); }
}

function openMarkdown(source: MarkdownKnowledgeSource) {
  void router.push({ name: 'search', query: {
    path: source.sourcePath,
    branchId: branchContext.context?.branchId,
    contextId: branchContext.context?.contextId,
  } });
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
      expectedContentVersion: source.sourceContentVersion,
      expectedContentHash: source.sourceContentHash,
    }, branchContext.context?.contextId);
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
  const expectedContentVersion = markdownSources.value?.contentVersion;
  const pending = markdownSources.value?.counts.pending ?? 0;
  if (!repositoryId || !expectedContentVersion || pending <= 0) return;
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
    const result = await intelligenceApi.generatePendingMarkdownSources(repositoryId, expectedContentVersion, branchContext.context?.contextId);
    await Promise.all([loadCards(), loadMarkdownSources()]);
    ElMessage.success(result.generated > 0
      ? result.remaining > 0
        ? `已生成 ${result.generated} 个草稿，剩余 ${result.remaining} 个可继续分批生成`
        : `已生成 ${result.generated} 个知识卡片草稿`
      : '没有新的 Markdown 需要生成');
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      await loadMarkdownSources();
      ElMessage.warning('项目内容版本已变化，列表已刷新，请确认最新待处理内容后重试');
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
    const context = await referenceContext(reference);
    const target = reference.chunkId
      ? await intelligenceApi.graphTarget(repositoryId, reference.chunkId, context?.contextId)
      : { symbol: reference.symbolName || reference.filePath, filePath: reference.filePath, startLine: reference.startLine };
    resumeCardId.value = viewing.value?.id ?? null;
    remember();
    detailDialog.value = false;
    await router.push({ name: 'search', query: {
      path: target.filePath || reference.filePath,
      startLine: String(target.startLine ?? reference.startLine ?? 1),
      contentVersion: reference.contentVersion ?? undefined,
      symbol: target.symbol,
      depth: '3',
      relation: '1',
      branchId: context?.branchId,
      contextId: context?.contextId,
    } });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法解析图谱目标');
  }
}
async function save(input: CardInput) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId || busy.value) return;
  const card = editing.value;
  const contextId = branchContext.context?.contextId;
  const identity = branchContext.identity;
  const request = ++saveVersion;
  const isCurrent = () => request === saveVersion && repositoryId === repositories.selectedRepositoryId
    && identity === branchContext.identity;
  busy.value = true;
  saveError.value = null;
  try {
    const saved = card ? await intelligenceApi.updateCard(repositoryId, card.id, input, contextId)
      : await intelligenceApi.createCard(repositoryId, input, contextId);
    if (!isCurrent()) return;
    dialog.value = false;
    await load();
    if (!isCurrent()) return;
    await locateCard(cards.value.find(item => item.id === saved.id) ?? saved);
    ElMessage.success(card ? '已保存为新修订，请确认后发布' : '已保存为草稿，可确认并发布');
  } catch (error) {
    if (!isCurrent()) return;
    saveError.value = error instanceof ApiError && error.status === 409
      ? '知识已被修改，请刷新后重新核对；当前输入保留'
      : error instanceof Error ? error.message : '保存失败，当前输入保留';
  } finally { if (isCurrent()) busy.value = false; }
}
async function reviewCard(card: KnowledgeCard, reviewStatus: 'APPROVED' | 'CHANGES_REQUESTED') {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  const action = reviewStatus === 'APPROVED' ? '通过人工评审' : '标记为要求修改';
  try {
    await ElMessageBox.confirm(`${action}“${card.title}”？该操作不会自动改变发布状态。`, '人工评审', { type: 'warning' });
    await intelligenceApi.reviewCard(repositoryId, card.id, reviewStatus, branchContext.context?.contextId);
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
    const context = branchContext.context;
    if (!context) return;
    await ElMessageBox.confirm(publicationStatus === 'PUBLISHED'
      ? `确认“${card.title}”适用于 ${context.branchName} 分支并发布？发布后可用于检索和问答。`
      : `${action}“${card.title}”？`, '发布知识', { confirmButtonText: publicationStatus === 'PUBLISHED' ? '确认并发布' : '确定', cancelButtonText: '取消' });
    if (publicationStatus === 'PUBLISHED') {
      await intelligenceApi.publishCard(repositoryId, card.id, card.revision, context.contextId);
    } else {
      await intelligenceApi.setCardPublication(repositoryId, card.id, publicationStatus, context.contextId);
    }
    await branchValidationSaved();
    ElMessage.success(publicationStatus === 'PUBLISHED' ? `已发布，共享范围：${scopeLabel(card)}；其他使用分支需分别确认适用性` : `${action}完成`);
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
  historyDialog.value = false;
  if (historyCard.value) await locateCard(historyCard.value);
  ElMessage.success('历史内容及附件已恢复为新草稿');
}
watch(() => [repositories.selectedRepositoryId, branchContext.identity] as const, () => {
  handledCreateRequest = '';
  driftVersion++; saveVersion++;
  driftLoading.value = false; busy.value = false; saveError.value = null;
  if (stateKey !== pageKey()) { remember(); stateKey = pageKey(); restoreReadingState(); }
  cards.value = []; branchValidations.value = [];
  dialog.value = false; historyDialog.value = false;
  viewing.value = null;
  driftEvent.value = null;
  detailDialog.value = false;
  void load();
});
watch(() => route.query.cardId, syncRequestedCard);
watch(
  () => [route.query.create, route.query.path, route.query.contentVersion, route.query.symbol] as const,
  syncRequestedCreate,
);
onMounted(() => void load());
</script>

<template>
  <section class="page knowledge-page">
    <div v-if="!repositories.selectedRepositoryId" class="knowledge-gate">
      <BookOpenCheck :size="28" />
      <h1>先选择一个项目</h1>
      <p>知识卡片必须归属明确项目，才能绑定代码范围、负责人和当前内容版本。</p>
      <el-button type="primary" @click="router.push('/repositories')">前往项目管理</el-button>
    </div>
    <div v-else class="surface knowledge-surface">
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

        <el-select v-if="activeMode === 'cards' && readScope.requiresContext.value" v-model="validationFilter" aria-label="分支验证筛选" placeholder="分支验证">
          <el-option value="ALL" label="全部验证状态" />
          <el-option v-for="(label, state) in validationLabels" :key="state" :value="state" :label="label" />
        </el-select>
        <el-input
          v-if="activeMode === 'cards'"
          v-model="cardQuery"
          class="app-search-input knowledge-search"
          :prefix-icon="Search"
          placeholder="搜索标题、正文或标签"
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
          v-model="selectedKnowledgeKind"
          class="knowledge-type-filter"
          placeholder="全部类型"
          aria-label="按知识类型筛选"
          clearable
          @clear="selectedKnowledgeKind = allKnowledgeKinds"
        >
          <el-option label="全部类型" :value="allKnowledgeKinds" />
          <el-option
            v-for="kind in knowledgeKinds"
            :key="kind"
            :label="knowledgeKindLabel(kind)"
            :value="kind"
          />
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
          v-if="canMaintain && activeMode === 'cards'"
          type="primary"
          :icon="Plus"
          :disabled="!repositories.selectedRepositoryId || !canMaintain"
          @click="openCreate"
        >
          新建卡片
        </el-button>
        <el-button
          v-else-if="canMaintain"
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
        ref="listElement"
        class="knowledge-scroll"
        @scroll="trackScroll"
        role="tabpanel"
        v-loading="cardsLoading"
      >
        <el-alert v-if="cardLoadError" type="error" :closable="false" :title="cardLoadError"><el-button @click="loadCards">重新加载</el-button></el-alert>
        <el-empty v-else-if="!cardsLoading && !cardRows.length" :description="cardEmptyDescription" />
        <div v-else class="knowledge-grid">
          <KnowledgeCardListItem
            v-for="card in cardRows"
            :key="card.id"
            :card="card"
            :can-manage="canManage"
            :can-maintain="canMaintain"
            :scope-label="scopeLabel(card)"
            :selected="selectedCardId === card.id"
            :branch-context="branchContext.context" :validation-state="validationState(card)"
            :validation-label="readScope.requiresContext.value ? validationLabels[validationState(card)] : undefined"
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
          :content-version="markdownSources?.contentVersion ?? null"
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
      :validation-state="viewing ? validationState(viewing) : undefined"
      @edit="openEdit" @closed="detailClosed"
      :scope-label="viewing ? scopeLabel(viewing) : undefined"
      :drift-event="driftEvent"
      :drift-loading="driftLoading"
      :can-maintain="canMaintain"
      :source-review-loading="sourceReviewLoading"
      :branch-context="readScope.requiresContext.value ? branchContext.context : null"
      :can-manage="canManage"
      @branch-validated="branchValidationSaved"
      @open-code="openCode"
      @open-graph="openGraph"
      @open-drift="openDrift"
      @source-review="sourceReview"
    />
    <KnowledgeCardEditorDialog v-if="canMaintain && repositories.selectedRepositoryId" v-model="dialog"
      :context-id="branchContext.context?.contextId"
      :repository-id="repositories.selectedRepositoryId" :card="editing" :busy="busy" :save-error="saveError"
      :initial-reference="initialReference"
      :branch-name="branchContext.context?.branchName ?? null"
      :branch-id="branchContext.context?.branchId" :branches="branchContext.branches"
      @submit="save" @open-code="openCode" />
    <el-dialog v-model="historyDialog" :title="`${historyCard?.title??''} · 修订历史`" width="760">
      <el-timeline><el-timeline-item v-for="item in revisions" :key="item.revision" :timestamp="new Date(item.changedAt).toLocaleString()" placement="top">
        <el-card shadow="never"><template #header><div class="toolbar"><b>v{{ item.revision }} · {{ statusLabel(item.publicationStatus) }}</b><span class="spacer" /><el-button link type="primary" @click="restore(item.revision)">恢复为新草稿</el-button></div></template>
          <div class="history-markdown" v-html="renderMarkdown(item.content, item.repositoryId)" />
          <small>{{ knowledgeKindLabel(item.knowledgeKind) }} · {{ enforcementLabel(item.enforcement) }} · {{ item.tags.join('、')||'无标签' }}</small>
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
.knowledge-gate { display: grid; min-height: 360px; place-content: center; justify-items: center; padding: 32px; color: var(--app-text-muted); border: 1px dashed var(--app-border-strong); border-radius: 8px; background: #fff; text-align: center; }
.knowledge-gate h1 { margin: 12px 0 4px; color: var(--app-text-primary); font-size: 18px; }
.knowledge-gate p { max-width: 500px; margin: 0 0 16px; font-size: 13px; line-height: 1.6; }
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
  font-size: 14px;
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
  font-size: 13px;
}
.knowledge-mode-switch button.active span {
  color: #005eb8;
  background: var(--app-color-action-soft);
}
.knowledge-scroll .knowledge-grid { grid-template-columns: minmax(0, 1fr); padding: 0 0 12px; }
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
