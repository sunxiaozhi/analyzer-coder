<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import { computed, onMounted, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { intelligenceApi, type CardInput, type CardRevision, type CodeReference, type KnowledgeCard } from '@/api/intelligence';
import KnowledgeCardDetailDialog from '@/features/knowledge/KnowledgeCardDetailDialog.vue';
import KnowledgeCardEditorDialog from '@/features/knowledge/KnowledgeCardEditorDialog.vue';
import KnowledgeCardListItem from '@/features/knowledge/KnowledgeCardListItem.vue';
import { renderMarkdown } from '@/features/knowledge/markdown';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositories = useRepositoryStore();
const router = useRouter();
const route = useRoute();
const cards = shallowRef<KnowledgeCard[]>([]);
const query = shallowRef('');
const dialog = shallowRef(false);
const detailDialog = shallowRef(false);
const historyDialog = shallowRef(false);
const busy = shallowRef(false);
const editing = shallowRef<KnowledgeCard | null>(null);
const viewing = shallowRef<KnowledgeCard | null>(null);
const historyCard = shallowRef<KnowledgeCard | null>(null);
const revisions = shallowRef<CardRevision[]>([]);
const rows = computed(() => cards.value.filter(card => {
  const value = query.value.trim().toLowerCase();
  return !value || card.title.toLowerCase().includes(value);
}));

async function load() {
  cards.value = repositories.selectedRepositoryId
    ? await intelligenceApi.cards(repositories.selectedRepositoryId) : [];
  syncRequestedCard();
}

function syncRequestedCard() {
  const cardId = typeof route.query.cardId === 'string' ? route.query.cardId : null;
  if (!cardId) return;
  const card = cards.value.find(item => item.id === cardId);
  if (!card) return;
  viewing.value = card;
  detailDialog.value = true;
}
function openCreate() { editing.value = null; dialog.value = true; }
function openEdit(card: KnowledgeCard) { editing.value = card; dialog.value = true; }
function openDetail(card: KnowledgeCard) { viewing.value = card; detailDialog.value = true; }
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

async function openGraph(reference: CodeReference) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  try {
    const target = reference.chunkId
      ? await intelligenceApi.graphTarget(repositoryId, reference.chunkId)
      : { symbol: reference.symbolName || reference.filePath, filePath: reference.filePath, startLine: reference.startLine };
    detailDialog.value = false;
    await router.push({ name: 'graph', query: { symbol: target.symbol, depth: '3', analyze: '1' } });
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
watch(() => repositories.selectedRepositoryId, () => void load());
watch(() => route.query.cardId, syncRequestedCard);
onMounted(() => void load());
</script>

<template>
  <section class="page">
    <div class="summary-strip">
      <div><b>{{ cards.length }}</b><span>知识卡片</span></div>
      <div><b>{{ cards.filter(x=>x.status==='PUBLISHED').length }}</b><span>已发布</span></div>
      <div><b>{{ cards.filter(x=>x.status==='DRAFT').length }}</b><span>草稿</span></div>
      <div><b>{{ cards.reduce((n,x)=>n+x.attachments.length,0) }}</b><span>附件</span></div>
    </div>
    <div class="surface">
      <div class="toolbar">
        <el-input v-model="query" class="app-search-input" :prefix-icon="Search" placeholder="搜索标题" clearable />
        <span class="spacer" />
        <el-button type="primary" :icon="Plus" :disabled="!repositories.selectedRepositoryId" @click="openCreate">新建卡片</el-button>
      </div>
      <el-empty v-if="!rows.length" description="当前仓库暂无知识卡片" />
      <div class="knowledge-grid">
        <KnowledgeCardListItem
          v-for="card in rows"
          :key="card.id"
          :card="card"
          @view="openDetail"
          @edit="openEdit"
          @history="showHistory"
        />
      </div>
    </div>
    <KnowledgeCardDetailDialog v-model="detailDialog" :card="viewing"
      @open-code="openCode" @open-graph="openGraph" />
    <KnowledgeCardEditorDialog v-if="repositories.selectedRepositoryId" v-model="dialog"
      :repository-id="repositories.selectedRepositoryId" :card="editing" :busy="busy"
      @submit="save" @open-code="openCode" />
    <el-dialog v-model="historyDialog" :title="`${historyCard?.title??''} · 修订历史`" width="760">
      <el-timeline><el-timeline-item v-for="item in revisions" :key="item.revision" :timestamp="new Date(item.changedAt).toLocaleString()" placement="top">
        <el-card shadow="never"><template #header><div class="toolbar"><b>v{{ item.revision }} · {{ item.status }}</b><span class="spacer" /><el-button link type="primary" @click="restore(item.revision)">恢复为新草稿</el-button></div></template>
          <div class="history-markdown" v-html="renderMarkdown(item.content, item.repositoryId)" /><small>{{ item.cardType }} · {{ item.tags.join(', ')||'无标签' }}</small>
        </el-card>
      </el-timeline-item></el-timeline>
    </el-dialog>
  </section>
</template>

<style scoped>
.history-markdown :deep(pre){overflow:auto;padding:10px;border-radius:8px;background:#18212f;color:#e6edf3}.history-markdown{line-height:1.7}
</style>