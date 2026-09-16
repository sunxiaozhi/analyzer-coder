<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue';
import {
  ArrowRight, BookOpenCheck, Clipboard,
  FilePlus2, Network, RefreshCw, X,
} from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { ElMessage } from 'element-plus';
import {
  intelligenceApi,
  type CodeEvidenceContext,
} from '@/api/intelligence';
import {
  enforcementLabel,
  knowledgeKindLabel,
  statusLabel,
} from '@/utils/displayLabels';

type KnowledgeFilter = 'all' | 'trusted' | 'attention';

interface Props {
  repositoryId: string | null;
  filePath: string | null;
  initialSymbol: string | null;
  snapshotId: string | null;
  contextId?: string | null;
  canMaintainKnowledge?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  canMaintainKnowledge: false,
});
const router = useRouter();
const branches = useBranchContextStore();
const emit = defineEmits<{
  close: [];
  openFile: [path: string, startLine: number | null, endLine: number | null];
  openKnowledge: [knowledgeId: string];
  createKnowledge: [];
}>();

const context = shallowRef<CodeEvidenceContext | null>(null);
const loading = shallowRef(false);
const error = shallowRef<string | null>(null);
const knowledgeFilter = shallowRef<KnowledgeFilter>('all');
let contextVersion = 0;

const trustedKnowledgeCount = computed(() => context.value?.knowledgeReferences.filter(item => item.trusted).length ?? 0);
const attentionKnowledgeCount = computed(() => context.value?.knowledgeReferences.filter(item => !item.trusted).length ?? 0);
const visibleKnowledge = computed(() => (context.value?.knowledgeReferences ?? []).filter(item => {
  if (knowledgeFilter.value === 'trusted') return item.trusted;
  if (knowledgeFilter.value === 'attention') return !item.trusted;
  return true;
}));
function applicabilityLabel(kind: string) {
  return ({
    DIRECT_BINDING: '直接代码绑定',
    PATH_SCOPE: '路径范围命中',
    SYMBOL_SCOPE: '符号范围命中',
    REPOSITORY_SCOPE: '仓库范围命中',
  } as Record<string, string>)[kind] ?? kind;
}

function limitationLabel(value: string) {
  if (value === 'DETERMINISTIC_KNOWLEDGE_MATCHING_ONLY') return '只展示代码绑定、路径、符号或仓库范围能够确定命中的知识；关键词相似内容不会被当作适用规则。';
  if (value === 'DIRECT_KNOWLEDGE_BINDINGS_ONLY') return '这里只展示直接绑定到该文件的知识，不把关键词相似结果冒充适用规则。';
  return '其他限制说明。';
}

function shortDate(value: string | null) {
  if (!value) return '未完成';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value));
}

async function load() {
  const version = ++contextVersion;
  context.value = null; error.value = null; loading.value = false;
  if (!props.repositoryId || !props.filePath) return;
  loading.value = true;
  try {
    const result = props.contextId
      ? await intelligenceApi.codeEvidenceContext(props.repositoryId, props.filePath, props.initialSymbol, props.contextId)
      : await intelligenceApi.codeEvidenceContext(props.repositoryId, props.filePath, props.initialSymbol);
    if (version === contextVersion) context.value = result;
  } catch (exception) {
    if (version === contextVersion) error.value = exception instanceof Error ? exception.message : '适用知识加载失败';
  } finally { if (version === contextVersion) loading.value = false; }
}
function openAtlas() {
  if (!props.filePath) return;
  void router.push({ name: 'atlas', query: {
    path: props.filePath, symbol: props.initialSymbol || undefined,
    snapshotId: props.snapshotId || undefined, contextId: props.contextId || undefined,
    branchId: branches.context?.branchId,
  } });
}
async function copyEvidence() {
  if (!context.value || !props.filePath) return;
  const lines = [
    `文件证据：${props.filePath}`,
    `快照：${context.value.snapshotId ?? '无'}  提交：${context.value.commitSha ?? '无'}`,
    `符号：${props.initialSymbol || '未指定'}`,
    `适用知识：${context.value.knowledgeReferences.length} 条（可信 ${trustedKnowledgeCount.value}，需关注 ${attentionKnowledgeCount.value}）`,
    ...context.value.knowledgeReferences.map(item => `- [知识] ${item.title} · ${item.trusted ? '可信' : statusLabel(item.sourceVersionStatus)} · ${(item.applicability ?? []).map(reason => applicabilityLabel(reason.kind)).join('、')}`),
  ];
  try {
    await navigator.clipboard.writeText(lines.join('\n'));
    ElMessage.success('文件证据摘要已复制');
  } catch {
    ElMessage.error('复制失败，请检查浏览器剪贴板权限');
  }
}

watch(
  () => [props.repositoryId, props.filePath, props.initialSymbol, props.snapshotId, props.contextId] as const,
  () => void load(),
  { immediate: true },
);
onScopeDispose(() => {
  contextVersion++;
});
</script>

<template>
  <section class="evidence-context-panel" aria-label="文件关联证据工作区">
    <header class="context-head">
      <div class="context-title">
        <span>FILE EVIDENCE</span>
        <div><b>文件关联证据</b><p class="mono" :title="filePath ?? ''">{{ filePath ?? '尚未选择文件' }}</p></div>
      </div>
      <div class="context-facts">
        <span>快照 <b class="mono">{{ context?.snapshotId?.slice(0, 8) ?? snapshotId?.slice(0, 8) ?? '未发布' }}</b></span>
        <span>更新 <b>{{ context ? shortDate(context.generatedAt) : '读取中' }}</b></span>
      </div>
      <div class="context-actions">
        <button type="button" :disabled="!filePath" @click="openAtlas"><Network :size="14" />在代码图谱中查看</button>
        <button type="button" title="复制文件证据摘要" :disabled="!context" @click="copyEvidence"><Clipboard :size="14" />复制摘要</button>
        <button type="button" title="刷新适用知识" :disabled="loading" @click="load"><RefreshCw :size="14" :class="{ spinning: loading }" />刷新</button>
        <button type="button" class="close-button" title="关闭文件证据" @click="emit('close')"><X :size="16" /></button>
      </div>
    </header>

    <div class="knowledge-heading"><BookOpenCheck :size="17" /><b>适用知识</b><span>可信 {{ trustedKnowledgeCount }} · 需关注 {{ attentionKnowledgeCount }}</span></div>
    <div v-if="loading" class="context-empty">正在加载适用知识…</div>
    <div v-else-if="!filePath" class="context-empty">从目录或检索结果选择文件，查看适用知识。</div>
    <div v-else-if="error" class="context-error">{{ error }}</div>
    <div v-else class="context-body">
      <header class="section-toolbar">
        <div><small>确定性匹配</small><b>直接绑定及适用范围</b></div>
        <div class="filter-switch">
          <button :class="{ active: knowledgeFilter === 'all' }" @click="knowledgeFilter = 'all'">全部 {{ context?.knowledgeReferences.length ?? 0 }}</button>
          <button :class="{ active: knowledgeFilter === 'trusted' }" @click="knowledgeFilter = 'trusted'">可信 {{ trustedKnowledgeCount }}</button>
          <button :class="{ active: knowledgeFilter === 'attention' }" @click="knowledgeFilter = 'attention'">需关注 {{ attentionKnowledgeCount }}</button>
        </div>
        <el-button v-if="canMaintainKnowledge" type="primary" plain :icon="FilePlus2" @click="emit('createKnowledge')">创建关联知识</el-button>
      </header>
      <div class="knowledge-grid">
        <article v-for="item in visibleKnowledge" :key="item.knowledgeId" class="knowledge-card" :data-trusted="item.trusted">
          <header><span>{{ item.trusted ? '可信知识' : statusLabel(item.sourceVersionStatus) }}</span><b>{{ enforcementLabel(item.enforcement) }}</b></header>
          <button type="button" class="reference-title" @click="emit('openKnowledge', item.knowledgeId)">{{ item.title }}<ArrowRight :size="13" /></button>
          <small>修订 {{ item.revision }} · {{ knowledgeKindLabel(item.kind) }} · {{ statusLabel(item.reviewStatus) }} · {{ statusLabel(item.publicationStatus) }}</small>
          <div class="reason-list">
            <span v-for="reason in (item.applicability ?? [])" :key="`${reason.kind}:${reason.rule}`" :title="reason.detail"><b>{{ applicabilityLabel(reason.kind) }}</b><code>{{ reason.rule }}</code></span>
          </div>
          <button v-for="binding in item.bindings" :key="`${binding.chunkId}:${binding.startLine}`" type="button" class="binding" :disabled="binding.stale || !binding.currentSnapshot" @click="emit('openFile', filePath!, binding.startLine, binding.endLine)">
            <span class="mono">{{ binding.symbolName ?? filePath }}:{{ binding.startLine ?? 1 }}</span><em v-if="binding.stale || !binding.currentSnapshot">旧版本绑定</em><ArrowRight v-else :size="12" />
          </button>
        </article>
      </div>
      <section v-if="!visibleKnowledge.length" class="action-empty">
        <BookOpenCheck :size="24" /><div><b>{{ knowledgeFilter === 'all' ? '当前文件还没有适用知识' : '当前筛选条件没有知识' }}</b><p>创建知识卡片并绑定当前代码，让后续检索可以同时返回说明与实现。</p></div>
        <el-button v-if="canMaintainKnowledge && knowledgeFilter === 'all'" type="primary" @click="emit('createKnowledge')">创建并绑定</el-button>
      </section>
      <p v-for="item in context?.limitations.filter(item => item.includes('KNOWLEDGE'))" :key="item" class="limitation">{{ limitationLabel(item) }}</p>
    </div>
  </section>
</template>

<style scoped>
.knowledge-heading { display: flex; align-items: center; gap: 9px; padding: 12px 14px; border-bottom: 1px solid #dbe4e9; color: #1b668f; font-size: 12px; }
.knowledge-heading span { margin-left: auto; color: #7b8992; font-size: 11px; }
.evidence-context-panel { --ink: #1f2b35; --muted: #6d7a84; --blue: #1b668f; --blue-soft: #edf5f9; --green: #21745a; --green-soft: #edf7f3; --ochre: #9a6424; --ochre-soft: #fbf5ea; display: grid; grid-template-rows: auto auto minmax(0, 1fr); min-width: 0; min-height: 0; overflow: hidden; border: 1px solid #d8e1e6; border-left: 0; background: #fbfcfd; }
.context-head { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; min-height: 68px; align-items: center; gap: 18px; padding: 10px 14px; border-bottom: 1px solid #dce5ea; background: #fff; }
.context-title { display: flex; min-width: 0; align-items: center; gap: 11px; }
.context-title > span { flex: none; padding: 5px 7px; color: #fff; background: var(--blue); font: 700 10px/1 "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; }
.context-title > div { display: grid; min-width: 0; gap: 3px; }
.context-title b { color: var(--ink); font-size: 16px; }
.context-title p { overflow: hidden; max-width: 460px; margin: 0; color: var(--muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.context-facts { display: flex; gap: 12px; }
.context-facts span { display: grid; gap: 2px; color: #85919a; font-size: 10px; }
.context-facts b { color: #465761; font-size: 11px; font-weight: 650; }
.context-actions { display: flex; align-items: center; gap: 5px; }
.context-actions button { display: inline-flex; min-height: 31px; align-items: center; gap: 5px; padding: 0 8px; color: #416276; border: 1px solid #d2dee5; border-radius: 4px; background: #fff; font-size: 11px; cursor: pointer; }
.context-actions button:hover { color: var(--blue); border-color: #9fc0d3; background: var(--blue-soft); }
.context-actions .close-button { width: 31px; justify-content: center; padding: 0; color: #78858d; }
.context-body { min-height: 0; padding: 15px; overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.section-toolbar > div:first-child { display: grid; gap: 3px; align-self: center; }
.section-toolbar > div:first-child small { color: var(--blue); font: 700 10px/1.2 "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
.section-toolbar > div:first-child b { color: var(--ink); font-size: 12px; }
.section-toolbar { display: grid; grid-template-columns: minmax(180px, 1fr) auto auto; align-items: center; gap: 12px; margin-bottom: 11px; padding-bottom: 11px; border-bottom: 1px solid #dce4e9; }
.filter-switch { display: flex; gap: 2px; padding: 3px; border: 1px solid #d7e0e5; border-radius: 5px; background: #eef2f4; }
.filter-switch button { min-height: 27px; padding: 0 8px; color: #687781; border: 0; border-radius: 3px; background: transparent; font-size: 10px; cursor: pointer; }
.filter-switch button.active { color: var(--blue); background: #fff; box-shadow: 0 1px 3px rgb(30 55 70 / 12%); font-weight: 700; }
.knowledge-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.knowledge-card { display: grid; align-content: start; gap: 8px; min-width: 0; padding: 11px; border: 1px solid #e2d9c9; border-left: 4px solid #b3843d; background: #fffdf9; }
.knowledge-card[data-trusted='true'] { border-color: #cfe0d8; border-left-color: var(--green); background: #fbfefd; }
.knowledge-card > header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.knowledge-card > header span { color: var(--blue); font-size: 9px; font-weight: 750; letter-spacing: .05em; }
.knowledge-card > header b { color: #617079; font-size: 10px; }
.reference-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0; color: var(--ink); border: 0; background: transparent; font-size: 13px; font-weight: 700; text-align: left; cursor: pointer; }
.knowledge-card > small { color: #75838b; font-size: 10px; line-height: 1.5; }
.reason-list { display: flex; flex-wrap: wrap; gap: 5px; }
.reason-list span { display: inline-flex; max-width: 100%; align-items: center; overflow: hidden; border: 1px solid #d7e1e6; background: #fff; }
.reason-list b { flex: none; padding: 4px 5px; color: #366a86; background: var(--blue-soft); font-size: 9px; }
.reason-list code { overflow: hidden; padding: 4px 5px; color: #5b6b75; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.binding { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 6px 7px; overflow: hidden; color: #376b8e; border: 1px solid #d8e2e8; border-radius: 3px; background: #fff; cursor: pointer; }
.binding span { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.binding em { color: #a44f43; font-size: 10px; font-style: normal; }
.action-empty { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 13px; min-height: 90px; padding: 17px; color: #78909e; border: 1px dashed #becdd6; background: #f7fafb; }
.action-empty div { display: grid; gap: 4px; }
.action-empty b { color: #42545e; font-size: 12px; }
.action-empty p { margin: 0; color: #75838b; font-size: 10px; }
.context-empty, .context-error, .limitation { margin: 0; color: #7b878f; font-size: 11px; line-height: 1.6; }
.context-empty { padding: 22px 12px; text-align: center; }
.context-error { display: flex; align-items: flex-start; gap: 6px; margin: 8px 0; padding: 8px; color: #a34940; border-left: 3px solid #bd5b50; background: #fff3f1; }
.limitation { margin-top: 8px; padding: 7px 8px; border-left: 3px solid #a8b5bd; background: #f0f4f6; }
.spinning { animation: spin .85s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1080px) {
  .context-head { grid-template-columns: minmax(0, 1fr) auto; }
  .context-facts { display: none; }
}
@media (max-width: 760px) {
  .context-head { grid-template-columns: 1fr auto; padding: 9px; }
  .context-actions button:not(.close-button) { width: 31px; justify-content: center; padding: 0; font-size: 0; }
  .context-title > span { display: none; }
  .context-body { padding: 10px; }
  .section-toolbar { grid-template-columns: 1fr; align-items: stretch; }
  .knowledge-grid { grid-template-columns: repeat(2, 1fr); }
  .filter-switch { width: 100%; }
  .filter-switch button { flex: 1; }
  .action-empty { grid-template-columns: auto minmax(0, 1fr); }
  .action-empty > .el-button { grid-column: 1 / -1; }
}

</style>
