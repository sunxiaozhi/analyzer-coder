<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue';
import {
  AlertTriangle, ArrowRight, BookOpenCheck, CheckCircle2, Clipboard,
  FilePlus2, GitPullRequest, Network, RefreshCw, X,
} from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import {
  intelligenceApi,
  type CodeEvidenceContext,
  type CodeGraphArtifact,
  type GraphResult,
} from '@/api/intelligence';
import { getIndexJob } from '@/api/repositories';
import type { IndexJob } from '@/types/api';
import {
  changeSourceLabel,
  enforcementLabel,
  knowledgeKindLabel,
  statusLabel,
} from '@/utils/displayLabels';

type ContextTab = 'relations' | 'knowledge' | 'reviews';
type KnowledgeFilter = 'all' | 'trusted' | 'attention';
type ReviewFilter = 'all' | 'current' | 'historical';

interface Props {
  repositoryId: string | null;
  filePath: string | null;
  initialSymbol: string | null;
  snapshotId: string | null;
  initialDepth?: number;
  autoAnalyze?: boolean;
  canBuildGraph?: boolean;
  canMaintainKnowledge?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  initialDepth: 3, autoAnalyze: false, canBuildGraph: false, canMaintainKnowledge: false,
});
const emit = defineEmits<{
  close: [];
  openFile: [path: string, startLine: number | null, endLine: number | null];
  openKnowledge: [knowledgeId: string];
  openReview: [reviewId: string];
  createKnowledge: [];
  startReview: [];
}>();

const tab = shallowRef<ContextTab>('relations');
const context = shallowRef<CodeEvidenceContext | null>(null);
const artifact = shallowRef<CodeGraphArtifact | null>(null);
const relation = shallowRef<GraphResult | null>(null);
const symbol = shallowRef('');
const depth = shallowRef(3);
const loading = shallowRef(false);
const analyzing = shallowRef(false);
const building = shallowRef(false);
const buildJob = shallowRef<IndexJob | null>(null);
const error = shallowRef<string | null>(null);
const relationError = shallowRef<string | null>(null);
const knowledgeFilter = shallowRef<KnowledgeFilter>('all');
const reviewFilter = shallowRef<ReviewFilter>('all');
let contextVersion = 0;
let relationVersion = 0;
let buildVersion = 0;
let disposed = false;

const nodeById = computed(() => new Map((relation.value?.nodes ?? []).map(node => [node.id, node])));
const edgeById = computed(() => new Map((relation.value?.edges ?? []).map(edge => [edge.id, edge])));
const trustedKnowledgeCount = computed(() => context.value?.knowledgeReferences.filter(item => item.trusted).length ?? 0);
const attentionKnowledgeCount = computed(() => context.value?.knowledgeReferences.filter(item => !item.trusted).length ?? 0);
const currentReviewCount = computed(() => context.value?.reviewReferences.filter(item => item.currentSnapshot).length ?? 0);
const historicalReviewCount = computed(() => context.value?.reviewReferences.filter(item => !item.currentSnapshot).length ?? 0);
const visibleKnowledge = computed(() => (context.value?.knowledgeReferences ?? []).filter(item => {
  if (knowledgeFilter.value === 'trusted') return item.trusted;
  if (knowledgeFilter.value === 'attention') return !item.trusted;
  return true;
}));
const visibleReviews = computed(() => (context.value?.reviewReferences ?? []).filter(item => {
  if (reviewFilter.value === 'current') return item.currentSnapshot;
  if (reviewFilter.value === 'historical') return !item.currentSnapshot;
  return true;
}));
const impactedNodes = computed(() => (relation.value?.nodes ?? [])
  .filter(node => !node.focus)
  .sort((left, right) => left.depth - right.depth || left.symbol.localeCompare(right.symbol)));
const graphState = computed(() => {
  if (building.value) return { label: buildJobLabel(buildJob.value), tone: 'running' };
  if (!artifact.value) return { label: '图谱待构建', tone: 'attention' };
  if (relation.value) return { label: `${relation.value.affectedNodeCount} 个影响节点`, tone: 'ready' };
  return { label: '图谱可查询', tone: 'ready' };
});

function nodeLabel(nodeId: string) {
  return nodeById.value.get(nodeId)?.symbol ?? nodeId;
}

function relationLabel(edgeId: string | undefined) {
  return edgeId ? (edgeById.value.get(edgeId)?.relation ?? '依赖') : '依赖';
}

function relationArrow(edgeId: string | undefined, from: string, to: string) {
  const edge = edgeId ? edgeById.value.get(edgeId) : null;
  if (edge?.source === from && edge.target === to) return '→';
  if (edge?.source === to && edge.target === from) return '←';
  return '·';
}

function roleLabel(role: string) {
  return ({
    CHANGED_FILE: '变更文件',
    CHANGED_SYMBOL: '变更符号',
    KNOWLEDGE_EVIDENCE: '知识证据',
    REQUIRED_TEST: '要求测试',
    REQUIRED_APPROVAL: '要求审批',
    UNKNOWN: '未知项证据',
  } as Record<string, string>)[role] ?? '其他证据';
}

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
  if (value === 'SYMBOL_REQUIRED_FOR_CODEGRAPH') return '关系查询需要明确符号；可从检索结果选择符号，或在下方输入。';
  if (value === 'REVIEW_HISTORY_TRUNCATED') return '审查引用只扫描最近 100 条不可变审查记录。';
  return '其他限制说明。';
}

function buildJobLabel(job: IndexJob | null) {
  if (!job) return '正在提交图谱任务';
  if (job.status === 'QUEUED') return '图谱任务排队中';
  if (job.status === 'RUNNING') return job.currentStep || '正在构建代码图谱';
  if (job.status === 'CANCEL_REQUESTED') return '正在取消图谱任务';
  if (job.status === 'SUCCEEDED') return '图谱构建完成';
  if (job.status === 'CANCELED') return '图谱任务已取消';
  return '图谱构建失败';
}

function shortDate(value: string | null) {
  if (!value) return '未完成';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value));
}

function graphLimitationLabel(value: string) {
  if (value === 'CODEGRAPH_STATIC_ANALYSIS_ONLY') return '静态分析无法确认反射、运行时分派和数据驱动调用。';
  if (value.startsWith('CODEGRAPH_AFFECTED_NODE_UNMAPPED:')) return '部分 CLI 影响记录无法映射到可定位节点。';
  if (value.startsWith('CODEGRAPH_NODE_COUNT_MISMATCH:')) return 'CLI 节点数与可展示节点数不一致。';
  if (value.startsWith('CODEGRAPH_EDGE_COUNT_MISMATCH:')) return 'CLI 边数与可展示真实边数不一致，页面没有补造连线。';
  return '存在其他无法由静态分析覆盖的情况。';
}

async function load(resetSymbol = true, rerunRelation = false) {
  const version = ++contextVersion;
  relationVersion++;
  analyzing.value = false;
  loading.value = false;
  context.value = null;
  artifact.value = null;
  relation.value = null;
  error.value = null;
  relationError.value = null;
  if (resetSymbol) {
    symbol.value = props.initialSymbol ?? '';
    depth.value = Math.max(1, Math.min(props.initialDepth, 5));
  }
  if (!props.repositoryId || !props.filePath) return;
  loading.value = true;
  try {
    const [fileContext, graphArtifact] = await Promise.all([
      intelligenceApi.codeEvidenceContext(props.repositoryId, props.filePath, symbol.value || null),
      intelligenceApi.latestGraph(props.repositoryId).catch(() => null),
    ]);
    if (version !== contextVersion) return;
    context.value = fileContext;
    artifact.value = graphArtifact?.snapshotId === props.snapshotId ? graphArtifact : null;
    if ((props.autoAnalyze || rerunRelation) && symbol.value && artifact.value) {
      await analyze();
    }
  } catch (exception) {
    if (version === contextVersion) {
      error.value = exception instanceof Error ? exception.message : '文件证据加载失败';
    }
  } finally {
    if (version === contextVersion) loading.value = false;
  }
}

async function refresh() {
  await load(false, Boolean(relation.value));
}

async function analyze() {
  const version = ++relationVersion;
  const context = contextVersion;
  relation.value = null;
  analyzing.value = false;
  if (!props.repositoryId || !symbol.value.trim()) {
    relationError.value = '请输入明确的类、函数、方法或路由符号。';
    return;
  }
  if (!artifact.value) {
    relationError.value = '当前快照尚未发布代码图谱，不能生成真实关系路径。';
    return;
  }
  analyzing.value = true;
  relationError.value = null;
  try {
    const result = await intelligenceApi.graph(
      props.repositoryId,
      symbol.value.trim(),
      depth.value,
      'BOTH',
    );
    if (version !== relationVersion || context !== contextVersion) return;
    if (result.snapshotId !== props.snapshotId) {
      relationError.value = '代码快照已更新，请刷新文件和图谱后重新查询。';
      return;
    }
    relation.value = result;
  } catch (exception) {
    if (version !== relationVersion || context !== contextVersion) return;
    relation.value = null;
    relationError.value = exception instanceof Error ? exception.message : '关系查询失败';
  } finally {
    if (version === relationVersion && context === contextVersion) analyzing.value = false;
  }
}

async function buildGraph() {
  if (!props.repositoryId || !props.canBuildGraph || building.value) return;
  const repositoryId = props.repositoryId;
  const snapshotId = props.snapshotId;
  const version = ++buildVersion;
  building.value = true;
  buildJob.value = null;
  relationError.value = null;
  try {
    let task = await intelligenceApi.buildGraph(repositoryId);
    if (version !== buildVersion || disposed) return;
    buildJob.value = task;
    for (let attempt = 0; attempt < 240 && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status); attempt += 1) {
      await new Promise(resolve => window.setTimeout(resolve, 1500));
      if (version !== buildVersion || disposed || repositoryId !== props.repositoryId || snapshotId !== props.snapshotId) return;
      task = await getIndexJob(task.id);
      buildJob.value = task;
    }
    if (task.status !== 'SUCCEEDED') {
      throw new Error(task.errorMessage || (task.status === 'CANCELED' ? '代码图谱构建已取消' : '代码图谱构建超时或失败'));
    }
    ElMessage.success('代码图谱已发布，正在刷新关系证据');
    await load(false, Boolean(symbol.value.trim()));
  } catch (exception) {
    if (version === buildVersion && !disposed) {
      relationError.value = exception instanceof Error ? exception.message : '代码图谱构建失败';
      ElMessage.error(relationError.value);
    }
  } finally {
    if (version === buildVersion) building.value = false;
  }
}

function openNode(nodeId: string) {
  const node = nodeById.value.get(nodeId);
  if (node) emit('openFile', node.filePath, node.startLine, node.endLine);
}

async function copyEvidence() {
  if (!context.value || !props.filePath) return;
  const lines = [
    `文件证据：${props.filePath}`,
    `快照：${context.value.snapshotId ?? '无'}  提交：${context.value.commitSha ?? '无'}`,
    `分析符号：${symbol.value || '未指定'}`,
    `关系：${relation.value ? `${relation.value.affectedNodeCount} 个影响节点，${relation.value.paths.length} 条路径` : artifact.value ? '图谱可用，尚未查询' : '当前快照无图谱'}`,
    `适用知识：${context.value.knowledgeReferences.length} 条（可信 ${trustedKnowledgeCount.value}，需关注 ${attentionKnowledgeCount.value}）`,
    ...context.value.knowledgeReferences.map(item => `- [知识] ${item.title} · ${item.trusted ? '可信' : statusLabel(item.sourceVersionStatus)} · ${(item.applicability ?? []).map(reason => applicabilityLabel(reason.kind)).join('、')}`),
    `相关审查：${context.value.reviewReferences.length} 条（当前快照 ${currentReviewCount.value}，历史 ${historicalReviewCount.value}）`,
    ...context.value.reviewReferences.map(item => `- [审查] ${item.task || '未填写任务说明'} · ${item.currentSnapshot ? '当前快照' : '历史快照'} · ${item.roles.map(roleLabel).join('、')}`),
  ];
  try {
    await navigator.clipboard.writeText(lines.join('\n'));
    ElMessage.success('文件证据摘要已复制');
  } catch {
    ElMessage.error('复制失败，请检查浏览器剪贴板权限');
  }
}

watch(
  () => [props.repositoryId, props.filePath, props.initialSymbol, props.snapshotId, props.autoAnalyze, props.initialDepth] as const,
  () => void load(true),
  { immediate: true },
);
onScopeDispose(() => {
  disposed = true;
  contextVersion++;
  relationVersion++;
  buildVersion++;
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
        <button type="button" title="复制文件证据摘要" :disabled="!context" @click="copyEvidence"><Clipboard :size="14" />复制摘要</button>
        <button type="button" title="刷新文件证据和图谱状态" :disabled="loading" @click="refresh"><RefreshCw :size="14" :class="{ spinning: loading }" />刷新</button>
        <button type="button" class="close-button" title="关闭文件证据" @click="emit('close')"><X :size="16" /></button>
      </div>
    </header>

    <nav class="context-tabs" aria-label="文件证据类型">
      <button :class="{ active: tab === 'relations' }" @click="tab = 'relations'">
        <Network :size="17" /><span><b>代码关系</b><small>{{ graphState.label }}</small></span><i :data-tone="graphState.tone"></i>
      </button>
      <button :class="{ active: tab === 'knowledge' }" @click="tab = 'knowledge'">
        <BookOpenCheck :size="17" /><span><b>适用知识</b><small>可信 {{ trustedKnowledgeCount }} · 需关注 {{ attentionKnowledgeCount }}</small></span><strong>{{ context?.knowledgeReferences.length ?? 0 }}</strong>
      </button>
      <button :class="{ active: tab === 'reviews' }" @click="tab = 'reviews'">
        <GitPullRequest :size="17" /><span><b>历史审查</b><small>当前 {{ currentReviewCount }} · 历史 {{ historicalReviewCount }}</small></span><strong>{{ context?.reviewReferences.length ?? 0 }}</strong>
      </button>
    </nav>

    <div v-if="!filePath" class="context-empty">从目录或检索结果选择文件，查看它的关系、知识和审查引用。</div>
    <div v-else-if="error" class="context-error"><AlertTriangle :size="14" />{{ error }}</div>

    <div v-else-if="tab === 'relations'" class="context-body relation-body">
      <section class="analysis-command">
        <div class="command-copy"><small>关系查询目标</small><b>从明确符号追踪真实依赖路径</b></div>
        <el-input v-model="symbol" placeholder="类、函数、方法或路由符号" @keyup.enter="analyze" />
        <label>深度 <el-input-number v-model="depth" :min="1" :max="5" controls-position="right" /></label>
        <el-button type="primary" :loading="analyzing" :disabled="!artifact" @click="analyze">分析影响</el-button>
      </section>
      <section class="artifact-line" :data-ready="Boolean(artifact)" :data-running="building">
        <span class="artifact-icon"><CheckCircle2 v-if="artifact && !building" :size="17" /><RefreshCw v-else :size="17" :class="{ spinning: building }" /></span>
        <div>
          <b v-if="building">{{ buildJobLabel(buildJob) }}</b>
          <b v-else-if="artifact">当前快照图谱已发布</b>
          <b v-else>当前快照没有已发布图谱</b>
          <p v-if="building">{{ buildJob?.currentStep || '任务已提交，完成后会自动刷新并执行关系分析。' }}</p>
          <p v-else-if="artifact">CodeGraph {{ artifact.cliVersion }} · {{ artifact.nodeCount }} 节点 · {{ artifact.edgeCount }} 条关系</p>
          <p v-else>构建后才能展示可回溯到源码的调用和依赖路径。</p>
        </div>
        <el-button v-if="canBuildGraph" plain :loading="building" @click="buildGraph">{{ artifact ? '重新构建' : '构建图谱' }}</el-button>
      </section>
      <p v-if="!artifact && !canBuildGraph" class="limitation">当前账号没有构建权限，请联系项目维护者在项目总览准备代码图谱。</p>
      <div v-if="relationError" class="context-error"><AlertTriangle :size="14" />{{ relationError }}</div>
      <template v-if="relation">
        <div class="relation-summary">
          <span><small>影响节点</small><strong>{{ relation.affectedNodeCount }}</strong></span>
          <span><small>可解释路径</small><strong>{{ relation.paths.length }}</strong></span>
          <span><small>实际深度</small><strong>{{ relation.maxDepthReached }}</strong></span>
          <span><small>映射完整度</small><strong>{{ relation.coverage.representedAffectedRecordCount }}/{{ relation.coverage.affectedRecordCount }}</strong></span>
        </div>
        <p class="coverage" :data-complete="relation.coverage.complete">
          {{ relation.coverage.complete ? 'CLI 返回记录已完整映射到源码节点' : '部分 CLI 返回记录无法映射到源码节点' }}
        </p>
        <div class="relation-workspace">
          <section class="path-column">
            <header><div><small>传播路径</small><b>每条连线都来自当前图谱</b></div><span>{{ relation.paths.length }} 条</span></header>
            <div class="path-list">
              <article v-for="path in relation.paths" :key="path.targetNodeId">
                <header><span>深度 {{ path.depth }}</span><b>{{ nodeLabel(path.targetNodeId) }}</b></header>
                <div class="path-chain">
                  <template v-for="(nodeId, index) in path.nodeIds" :key="`${path.targetNodeId}:${nodeId}:${index}`">
                    <small v-if="index">{{ relationArrow(path.edgeIds[index - 1], path.nodeIds[index - 1], nodeId) }} {{ relationLabel(path.edgeIds[index - 1]) }}</small>
                    <button type="button" @click="openNode(nodeId)">{{ nodeLabel(nodeId) }}<ArrowRight :size="11" /></button>
                  </template>
                </div>
              </article>
              <p v-if="!relation.paths.length" class="context-empty">没有可由真实代码图谱关系解释的传播路径。</p>
            </div>
          </section>
          <section class="node-column">
            <header><div><small>影响清单</small><b>按距离从近到远排列</b></div><span>{{ impactedNodes.length }} 项</span></header>
            <div class="node-list">
              <button v-for="node in impactedNodes" :key="node.id" type="button" @click="openNode(node.id)">
                <i>+{{ node.depth }}</i><span><b>{{ node.symbol }}</b><small class="mono">{{ node.filePath }}:{{ node.startLine ?? 1 }}</small></span><em>{{ node.kind }}</em><ArrowRight :size="12" />
              </button>
              <p v-if="!impactedNodes.length" class="context-empty">当前符号没有其他已映射影响节点。</p>
            </div>
          </section>
        </div>
        <p v-for="item in relation.limitations" :key="item" class="limitation">{{ graphLimitationLabel(item) }}</p>
      </template>
      <p v-for="item in context?.limitations.filter(item => item === 'SYMBOL_REQUIRED_FOR_CODEGRAPH')" :key="item" class="limitation">{{ limitationLabel(item) }}</p>
    </div>

    <div v-else-if="tab === 'knowledge'" class="context-body">
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
        <BookOpenCheck :size="24" /><div><b>{{ knowledgeFilter === 'all' ? '当前文件还没有适用知识' : '当前筛选条件没有知识' }}</b><p>创建知识卡片并直接绑定当前代码，后续变更审查会自动引用。</p></div>
        <el-button v-if="canMaintainKnowledge && knowledgeFilter === 'all'" type="primary" @click="emit('createKnowledge')">创建并绑定</el-button>
      </section>
      <p v-for="item in context?.limitations.filter(item => item.includes('KNOWLEDGE'))" :key="item" class="limitation">{{ limitationLabel(item) }}</p>
    </div>

    <div v-else class="context-body">
      <header class="section-toolbar">
        <div><small>不可变记录</small><b>该文件参与过的变更审查</b></div>
        <div class="filter-switch">
          <button :class="{ active: reviewFilter === 'all' }" @click="reviewFilter = 'all'">全部 {{ context?.reviewReferences.length ?? 0 }}</button>
          <button :class="{ active: reviewFilter === 'current' }" @click="reviewFilter = 'current'">当前 {{ currentReviewCount }}</button>
          <button :class="{ active: reviewFilter === 'historical' }" @click="reviewFilter = 'historical'">历史 {{ historicalReviewCount }}</button>
        </div>
        <el-button type="primary" plain :icon="GitPullRequest" @click="emit('startReview')">发起变更审查</el-button>
      </header>
      <div class="review-list">
        <article v-for="item in visibleReviews" :key="item.reviewId" :data-current="item.currentSnapshot">
          <div class="review-time"><b>{{ shortDate(item.finishedAt ?? item.createdAt) }}</b><span>{{ item.currentSnapshot ? '当前快照' : '历史快照' }}</span></div>
          <div class="review-copy">
            <header><b>{{ changeSourceLabel(item.changeSource) }}</b><span class="mono">{{ item.snapshotId.slice(0, 8) }}</span></header>
            <button type="button" class="reference-title" @click="emit('openReview', item.reviewId)">{{ item.task || '未填写任务说明' }}<ArrowRight :size="13" /></button>
            <div class="role-list"><span v-for="role in item.roles" :key="role">{{ roleLabel(role) }}</span></div>
            <small v-if="item.symbols.length">符号：{{ item.symbols.join('、') }}</small>
          </div>
        </article>
      </div>
      <section v-if="!visibleReviews.length" class="action-empty">
        <GitPullRequest :size="24" /><div><b>{{ reviewFilter === 'all' ? '这个文件还没有审查记录' : '当前筛选条件没有审查记录' }}</b><p>从真实 Git 改动发起审查后，这里会保留对应快照和证据角色。</p></div>
        <el-button v-if="reviewFilter === 'all'" type="primary" @click="emit('startReview')">发起审查</el-button>
      </section>
      <p v-for="item in context?.limitations.filter(item => item === 'REVIEW_HISTORY_TRUNCATED')" :key="item" class="limitation">{{ limitationLabel(item) }}</p>
    </div>
  </section>
</template>

<style scoped>
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
.context-tabs { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border-bottom: 1px solid #dbe4e9; background: #f3f6f8; }
.context-tabs button { position: relative; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; min-height: 61px; align-items: center; gap: 9px; padding: 9px 14px; color: #71808a; border: 0; border-right: 1px solid #dbe4e9; background: transparent; text-align: left; cursor: pointer; }
.context-tabs button:last-child { border-right: 0; }
.context-tabs button::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 3px; background: transparent; content: ''; }
.context-tabs button.active { color: var(--blue); background: #fff; }
.context-tabs button.active::after { background: var(--blue); }
.context-tabs button > span { display: grid; min-width: 0; gap: 2px; }
.context-tabs button b { color: var(--ink); font-size: 12px; }
.context-tabs button small { overflow: hidden; color: #7b8992; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.context-tabs button > strong { display: grid; min-width: 24px; height: 24px; place-items: center; color: #536a78; border-radius: 50%; background: #e5ebef; font: 700 11px "SFMono-Regular", Consolas, monospace; }
.context-tabs i { width: 8px; height: 8px; border-radius: 50%; background: #9ba8b0; box-shadow: 0 0 0 4px rgb(155 168 176 / 14%); }
.context-tabs i[data-tone='ready'] { background: var(--green); box-shadow: 0 0 0 4px rgb(33 116 90 / 13%); }
.context-tabs i[data-tone='running'] { background: var(--blue); box-shadow: 0 0 0 4px rgb(27 102 143 / 13%); }
.context-tabs i[data-tone='attention'] { background: #b47725; box-shadow: 0 0 0 4px rgb(180 119 37 / 14%); }
.context-body { min-height: 0; padding: 15px; overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.analysis-command { display: grid; grid-template-columns: minmax(180px, .8fr) minmax(220px, 1.2fr) auto auto; align-items: end; gap: 9px; padding: 12px; border: 1px solid #d6e0e6; background: #fff; }
.command-copy, .section-toolbar > div:first-child { display: grid; gap: 3px; align-self: center; }
.command-copy small, .section-toolbar > div:first-child small, .relation-workspace > section > header small { color: var(--blue); font: 700 10px/1.2 "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
.command-copy b, .section-toolbar > div:first-child b { color: var(--ink); font-size: 12px; }
.analysis-command label { display: grid; gap: 4px; color: var(--muted); font-size: 10px; }
.analysis-command :deep(.el-input-number) { width: 86px; }
.artifact-line { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 11px; margin: 10px 0; padding: 10px 12px; color: var(--ochre); border-left: 4px solid #b87a2b; background: var(--ochre-soft); }
.artifact-line[data-ready='true'] { color: var(--green); border-color: var(--green); background: var(--green-soft); }
.artifact-line[data-running='true'] { color: var(--blue); border-color: var(--blue); background: var(--blue-soft); }
.artifact-icon { display: grid; width: 31px; height: 31px; place-items: center; border: 1px solid currentColor; border-radius: 50%; }
.artifact-line > div { display: grid; gap: 3px; }
.artifact-line b { color: currentColor; font-size: 12px; }
.artifact-line p { margin: 0; color: #63737c; font-size: 11px; }
.relation-summary { display: grid; grid-template-columns: repeat(4, 1fr); margin-top: 11px; border: 1px solid #dbe4e8; background: #fff; }
.relation-summary span { display: grid; gap: 4px; padding: 9px 11px; border-right: 1px solid #dbe4e8; }
.relation-summary span:last-child { border-right: 0; }
.relation-summary small { color: #74828b; font-size: 10px; }
.relation-summary strong { color: var(--ink); font: 700 17px "SFMono-Regular", Consolas, monospace; }
.coverage { margin: 8px 0; padding: 7px 9px; color: var(--ochre); border-left: 3px solid #b87a2b; background: var(--ochre-soft); font-size: 11px; }
.coverage[data-complete='true'] { color: var(--green); border-color: var(--green); background: var(--green-soft); }
.relation-workspace { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(230px, .8fr); gap: 11px; align-items: start; }
.relation-workspace > section { min-width: 0; border: 1px solid #dbe3e8; background: #fff; }
.relation-workspace > section > header { display: flex; min-height: 46px; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 10px; border-bottom: 1px solid #e2e8ec; background: #f7f9fa; }
.relation-workspace > section > header > div { display: grid; gap: 3px; }
.relation-workspace > section > header b { color: var(--ink); font-size: 11px; }
.relation-workspace > section > header > span { color: #657680; font: 700 10px "SFMono-Regular", Consolas, monospace; }
.path-list, .node-list { display: grid; }
.path-list article { display: grid; gap: 8px; padding: 10px; border-bottom: 1px solid #e8edf0; }
.path-list article:last-child { border-bottom: 0; }
.path-list article > header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.path-list article > header span { color: var(--blue); font: 700 10px "SFMono-Regular", Consolas, monospace; }
.path-list article > header b { overflow: hidden; color: #566771; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.path-chain { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.path-chain small { color: #87939a; font-size: 10px; }
.path-chain button { display: inline-flex; align-items: center; gap: 4px; padding: 5px 7px; color: #245f80; border: 1px solid #c7d9e3; border-radius: 3px; background: #fafdff; font: 10px "SFMono-Regular", Consolas, monospace; cursor: pointer; }
.node-list button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 7px; padding: 8px 9px; color: #445660; border: 0; border-bottom: 1px solid #e8edf0; background: #fff; text-align: left; cursor: pointer; }
.node-list button:hover { background: var(--blue-soft); }
.node-list button i { color: var(--blue); font: 700 10px "SFMono-Regular", Consolas, monospace; font-style: normal; }
.node-list button > span { display: grid; min-width: 0; gap: 2px; }
.node-list button b, .node-list button small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-list button b { color: var(--ink); font-size: 11px; }
.node-list button small { color: #79868e; font-size: 9px; }
.node-list button em { color: #6d7b84; font-size: 9px; font-style: normal; }
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
.knowledge-card > small, .review-copy > small { color: #75838b; font-size: 10px; line-height: 1.5; }
.reason-list { display: flex; flex-wrap: wrap; gap: 5px; }
.reason-list span { display: inline-flex; max-width: 100%; align-items: center; overflow: hidden; border: 1px solid #d7e1e6; background: #fff; }
.reason-list b { flex: none; padding: 4px 5px; color: #366a86; background: var(--blue-soft); font-size: 9px; }
.reason-list code { overflow: hidden; padding: 4px 5px; color: #5b6b75; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.binding { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 6px 7px; overflow: hidden; color: #376b8e; border: 1px solid #d8e2e8; border-radius: 3px; background: #fff; cursor: pointer; }
.binding span { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.binding em { color: #a44f43; font-size: 10px; font-style: normal; }
.review-list { display: grid; border: 1px solid #dbe3e8; background: #fff; }
.review-list article { display: grid; grid-template-columns: 108px minmax(0, 1fr); border-bottom: 1px solid #e3e9ed; border-left: 4px solid #9ba8af; }
.review-list article:last-child { border-bottom: 0; }
.review-list article[data-current='true'] { border-left-color: var(--green); }
.review-time { display: grid; align-content: start; gap: 5px; padding: 12px 10px; border-right: 1px solid #e3e9ed; background: #f7f9fa; }
.review-time b { color: #3f515c; font: 700 10px "SFMono-Regular", Consolas, monospace; }
.review-time span { color: #7b8890; font-size: 9px; }
.review-copy { display: grid; gap: 7px; padding: 11px; }
.review-copy > header { display: flex; justify-content: space-between; gap: 8px; color: #71808a; font-size: 9px; }
.role-list { display: flex; flex-wrap: wrap; gap: 4px; }
.role-list span { padding: 3px 5px; color: #42647b; border: 1px solid #ccd9e1; background: #f8fbfc; font-size: 9px; }
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
  .analysis-command { grid-template-columns: minmax(180px, .7fr) minmax(200px, 1fr) auto; }
  .analysis-command > .el-button { grid-column: 3; grid-row: 1 / 3; }
  .relation-workspace { grid-template-columns: 1fr; }
}
@media (max-width: 760px) {
  .context-head { grid-template-columns: 1fr auto; padding: 9px; }
  .context-actions button:not(.close-button) { width: 31px; justify-content: center; padding: 0; font-size: 0; }
  .context-title > span { display: none; }
  .context-tabs button { min-height: 52px; justify-items: center; padding: 7px 4px; }
  .context-tabs button > span small, .context-tabs button > strong, .context-tabs i { display: none; }
  .context-body { padding: 10px; }
  .analysis-command, .section-toolbar { grid-template-columns: 1fr; align-items: stretch; }
  .analysis-command > .el-button { grid-column: auto; grid-row: auto; }
  .analysis-command :deep(.el-input-number) { width: 100%; }
  .artifact-line { grid-template-columns: auto minmax(0, 1fr); }
  .artifact-line > .el-button { grid-column: 1 / -1; }
  .relation-summary, .knowledge-grid { grid-template-columns: repeat(2, 1fr); }
  .relation-summary span:nth-child(2) { border-right: 0; }
  .relation-summary span:nth-child(-n+2) { border-bottom: 1px solid #dbe4e8; }
  .filter-switch { width: 100%; }
  .filter-switch button { flex: 1; }
  .action-empty { grid-template-columns: auto minmax(0, 1fr); }
  .action-empty > .el-button { grid-column: 1 / -1; }
}
</style>
