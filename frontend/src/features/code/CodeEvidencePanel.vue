<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { AlertTriangle, ArrowRight, BookOpenCheck, GitPullRequest, Network, RefreshCw } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import {
  intelligenceApi,
  type CodeEvidenceContext,
  type CodeGraphArtifact,
  type GraphResult,
} from '@/api/intelligence';
import {
  changeSourceLabel,
  enforcementLabel,
  knowledgeKindLabel,
  statusLabel,
} from '@/utils/displayLabels';

type ContextTab = 'relations' | 'knowledge' | 'reviews';

interface Props {
  repositoryId: string | null;
  filePath: string | null;
  initialSymbol: string | null;
  snapshotId: string | null;
  autoAnalyze?: boolean;
}

const props = withDefaults(defineProps<Props>(), { autoAnalyze: false });
const emit = defineEmits<{
  openFile: [path: string, startLine: number | null, endLine: number | null];
  openKnowledge: [knowledgeId: string];
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
const error = shallowRef<string | null>(null);
const relationError = shallowRef<string | null>(null);
let contextVersion = 0;
let autoKey = '';

const nodeById = computed(() => new Map((relation.value?.nodes ?? []).map(node => [node.id, node])));
const edgeById = computed(() => new Map((relation.value?.edges ?? []).map(edge => [edge.id, edge])));

function nodeLabel(nodeId: string) {
  return nodeById.value.get(nodeId)?.symbol ?? nodeId;
}

function relationLabel(edgeId: string | undefined) {
  return edgeId ? (edgeById.value.get(edgeId)?.relation ?? '依赖') : '依赖';
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

function limitationLabel(value: string) {
  if (value === 'DIRECT_KNOWLEDGE_BINDINGS_ONLY') return '这里只展示直接绑定到该文件的知识，不把关键词相似结果冒充适用规则。';
  if (value === 'SYMBOL_REQUIRED_FOR_CODEGRAPH') return '关系查询需要明确符号；可从检索结果选择符号，或在下方输入。';
  if (value === 'REVIEW_HISTORY_TRUNCATED') return '审查引用只扫描最近 100 条不可变审查记录。';
  return '其他限制说明。';
}

function graphLimitationLabel(value: string) {
  if (value === 'CODEGRAPH_STATIC_ANALYSIS_ONLY') return '静态分析无法确认反射、运行时分派和数据驱动调用。';
  if (value.startsWith('CODEGRAPH_AFFECTED_NODE_UNMAPPED:')) return '部分 CLI 影响记录无法映射到可定位节点。';
  if (value.startsWith('CODEGRAPH_NODE_COUNT_MISMATCH:')) return 'CLI 节点数与可展示节点数不一致。';
  if (value.startsWith('CODEGRAPH_EDGE_COUNT_MISMATCH:')) return 'CLI 边数与可展示真实边数不一致，页面没有补造连线。';
  return '存在其他无法由静态分析覆盖的情况。';
}

async function load() {
  const version = ++contextVersion;
  context.value = null;
  relation.value = null;
  error.value = null;
  relationError.value = null;
  symbol.value = props.initialSymbol ?? '';
  if (!props.repositoryId || !props.filePath) return;
  loading.value = true;
  try {
    const [fileContext, graphArtifact] = await Promise.all([
      intelligenceApi.codeEvidenceContext(props.repositoryId, props.filePath, symbol.value || null),
      intelligenceApi.latestGraph(props.repositoryId).catch(() => null),
    ]);
    if (version !== contextVersion) return;
    context.value = fileContext;
    artifact.value = graphArtifact;
    if (props.autoAnalyze && symbol.value && artifact.value) {
      const key = `${props.repositoryId}:${props.snapshotId}:${symbol.value}:${depth.value}`;
      if (autoKey !== key) {
        autoKey = key;
        await analyze();
      }
    }
  } catch (exception) {
    if (version === contextVersion) {
      error.value = exception instanceof Error ? exception.message : '文件证据加载失败';
    }
  } finally {
    if (version === contextVersion) loading.value = false;
  }
}

async function analyze() {
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
    relation.value = await intelligenceApi.graph(
      props.repositoryId,
      symbol.value.trim(),
      depth.value,
      'BOTH',
    );
  } catch (exception) {
    relation.value = null;
    relationError.value = exception instanceof Error ? exception.message : '关系查询失败';
  } finally {
    analyzing.value = false;
  }
}

async function buildGraph() {
  if (!props.repositoryId) return;
  building.value = true;
  try {
    const task = await intelligenceApi.buildGraph(props.repositoryId);
    if (task.status === 'FAILED') ElMessage.error(task.errorMessage ?? '代码图谱构建失败');
    else ElMessage.info('代码图谱构建任务已提交，发布完成后可查询关系。');
  } catch (exception) {
    ElMessage.error(exception instanceof Error ? exception.message : '代码图谱构建失败');
  } finally {
    building.value = false;
  }
}

function openNode(nodeId: string) {
  const node = nodeById.value.get(nodeId);
  if (node) emit('openFile', node.filePath, node.startLine, node.endLine);
}

watch(
  () => [props.repositoryId, props.filePath, props.initialSymbol, props.snapshotId] as const,
  load,
  { immediate: true },
);
</script>

<template>
  <aside class="evidence-context-panel">
    <header class="context-head">
      <div>
        <b>文件证据</b>
        <span>{{ filePath ?? '尚未选择文件' }}</span>
      </div>
      <RefreshCw v-if="loading" :size="14" class="spinning" />
    </header>

    <nav class="context-tabs" aria-label="文件证据类型">
      <button :class="{ active: tab === 'relations' }" @click="tab = 'relations'">
        <Network :size="13" />关系
      </button>
      <button :class="{ active: tab === 'knowledge' }" @click="tab = 'knowledge'">
        <BookOpenCheck :size="13" />知识 {{ context?.knowledgeReferences.length ?? 0 }}
      </button>
      <button :class="{ active: tab === 'reviews' }" @click="tab = 'reviews'">
        <GitPullRequest :size="13" />审查 {{ context?.reviewReferences.length ?? 0 }}
      </button>
    </nav>

    <div v-if="!filePath" class="context-empty">从目录或检索结果选择文件，查看它的关系、知识和审查引用。</div>
    <div v-else-if="error" class="context-error"><AlertTriangle :size="14" />{{ error }}</div>

    <div v-else-if="tab === 'relations'" class="context-body relation-body">
      <div class="relation-query">
        <el-input v-model="symbol" size="small" placeholder="输入当前文件中的符号" @keyup.enter="analyze" />
        <el-input-number v-model="depth" :min="1" :max="5" size="small" controls-position="right" />
        <el-button size="small" type="primary" :loading="analyzing" @click="analyze">查询</el-button>
      </div>
      <div class="artifact-line" :data-ready="Boolean(artifact)">
        <span v-if="artifact">工具版本 {{ artifact.cliVersion }} · {{ artifact.nodeCount }} 节点 · 快照 {{ artifact.snapshotId.slice(0, 8) }}</span>
        <span v-else>当前快照没有已发布图谱</span>
        <button v-if="!artifact" type="button" :disabled="building" @click="buildGraph">{{ building ? '提交中' : '构建' }}</button>
      </div>
      <div v-if="relationError" class="context-error"><AlertTriangle :size="14" />{{ relationError }}</div>
      <template v-if="relation">
        <div class="relation-summary">
          <span><strong>{{ relation.affectedNodeCount }}</strong>受影响节点</span>
          <span><strong>{{ relation.paths.length }}</strong>真实路径</span>
          <span><strong>{{ relation.maxDepthReached }}</strong>实际深度</span>
        </div>
        <p class="coverage" :data-complete="relation.coverage.complete">
          {{ relation.coverage.complete ? '路径覆盖完整' : '覆盖不完整' }} ·
          {{ relation.coverage.representedAffectedRecordCount }}/{{ relation.coverage.affectedRecordCount }} 条 CLI 记录已映射
        </p>
        <div class="path-list">
          <article v-for="path in relation.paths" :key="path.targetNodeId">
            <header><span>深度 {{ path.depth }}</span><b>{{ nodeLabel(path.targetNodeId) }}</b></header>
            <div class="path-chain">
              <template v-for="(nodeId, index) in path.nodeIds" :key="nodeId">
                <small v-if="index">← {{ relationLabel(path.edgeIds[index - 1]) }}</small>
                <button type="button" @click="openNode(nodeId)">{{ nodeLabel(nodeId) }}<ArrowRight :size="11" /></button>
              </template>
            </div>
          </article>
        </div>
        <p v-if="!relation.paths.length" class="context-empty">没有可由真实代码图谱关系解释的传播路径。</p>
        <p v-for="item in relation.limitations" :key="item" class="limitation">{{ graphLimitationLabel(item) }}</p>
      </template>
      <p v-for="item in context?.limitations.filter(item => item === 'SYMBOL_REQUIRED_FOR_CODEGRAPH')" :key="item" class="limitation">{{ limitationLabel(item) }}</p>
    </div>

    <div v-else-if="tab === 'knowledge'" class="context-body reference-list">
      <article v-for="item in context?.knowledgeReferences" :key="item.knowledgeId" :data-trusted="item.trusted">
        <header><span>{{ item.trusted ? '可信知识' : statusLabel(item.sourceVersionStatus) }}</span><b>{{ enforcementLabel(item.enforcement) }}</b></header>
        <button type="button" class="reference-title" @click="emit('openKnowledge', item.knowledgeId)">{{ item.title }}<ArrowRight :size="12" /></button>
        <small>修订 {{ item.revision }} · {{ knowledgeKindLabel(item.kind) }} · {{ statusLabel(item.reviewStatus) }} · {{ statusLabel(item.publicationStatus) }}</small>
        <button v-for="binding in item.bindings" :key="`${binding.chunkId}:${binding.startLine}`" type="button" class="binding" @click="emit('openFile', filePath!, binding.startLine, binding.endLine)">
          {{ binding.symbolName ?? filePath }}:{{ binding.startLine ?? 1 }}
          <span v-if="binding.stale || !binding.currentSnapshot">旧版本绑定</span>
        </button>
      </article>
      <p v-if="!context?.knowledgeReferences.length" class="context-empty">当前账号可见知识中，没有直接绑定到该文件的卡片。</p>
      <p v-for="item in context?.limitations.filter(item => item === 'DIRECT_KNOWLEDGE_BINDINGS_ONLY')" :key="item" class="limitation">{{ limitationLabel(item) }}</p>
    </div>

    <div v-else class="context-body reference-list review-reference-list">
      <article v-for="item in context?.reviewReferences" :key="item.reviewId" :data-current="item.currentSnapshot">
        <header><span>{{ item.currentSnapshot ? '当前快照' : '历史快照' }}</span><b>{{ changeSourceLabel(item.changeSource) }}</b></header>
        <strong>{{ item.task || '未填写任务说明' }}</strong>
        <div class="role-list"><span v-for="role in item.roles" :key="role">{{ roleLabel(role) }}</span></div>
        <small v-if="item.symbols.length">符号：{{ item.symbols.join('、') }}</small>
      </article>
      <p v-if="!context?.reviewReferences.length" class="context-empty">最近 {{ context?.scannedReviewCount ?? 0 }} 条审查中没有引用该文件。</p>
      <p v-for="item in context?.limitations.filter(item => item === 'REVIEW_HISTORY_TRUNCATED')" :key="item" class="limitation">{{ limitationLabel(item) }}</p>
    </div>
  </aside>
</template>

<style scoped>
.evidence-context-panel { display: grid; grid-template-rows: auto auto minmax(0, 1fr); min-width: 0; min-height: 0; overflow: hidden; border-block: 1px solid #dedee3; border-right: 1px solid #dedee3; background: #fff; }
.context-head { display: flex; min-height: 56px; align-items: center; justify-content: space-between; gap: 8px; padding: 9px 12px; border-bottom: 1px solid #ececef; }
.context-head > div { display: grid; min-width: 0; gap: 3px; }
.context-head b { color: #303036; font-size: 15px; }
.context-head span { overflow: hidden; color: #7a7a81; font: 12px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.context-tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 2px; padding: 4px; border-bottom: 1px solid #e6e8eb; background: #f5f6f7; }
.context-tabs button { display: flex; min-width: 0; min-height: 29px; align-items: center; justify-content: center; gap: 4px; color: #686871; border: 0; border-radius: 3px; background: transparent; font-size: 12px; cursor: pointer; }
.context-tabs button.active { color: #075e9e; background: #fff; box-shadow: 0 1px 3px rgb(28 45 61 / 10%); font-weight: 700; }
.context-body { min-height: 0; padding: 11px; overflow: auto; }
.relation-query { display: grid; grid-template-columns: minmax(0, 1fr) 82px auto; gap: 5px; }
.artifact-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 9px 0; padding: 7px 8px; color: #9a6324; border-left: 3px solid #c28638; background: #fbf6ef; font-size: 12px; }
.artifact-line[data-ready='true'] { color: #176548; border-color: #218a60; background: #eff8f4; }
.artifact-line button { color: currentColor; border: 0; background: transparent; font-size: 12px; font-weight: 700; cursor: pointer; }
.relation-summary { display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid #e2e7ea; }
.relation-summary span { display: grid; gap: 2px; padding: 8px; color: #72727a; border-right: 1px solid #e2e7ea; font-size: 12px; }
.relation-summary span:last-child { border-right: 0; }
.relation-summary strong { color: #26262b; font: 700 15px "SFMono-Regular", Consolas, monospace; }
.coverage { margin: 8px 0; padding: 6px 8px; color: #946025; border-left: 2px solid #c28638; background: #fbf6ef; font-size: 12px; }
.coverage[data-complete='true'] { color: #176548; border-color: #218a60; background: #eff8f4; }
.path-list { display: grid; gap: 7px; }
.path-list article { display: grid; gap: 6px; padding: 8px; border: 1px solid #e7e9ec; background: #fafbfc; }
.path-list header, .reference-list article > header { display: flex; align-items: center; justify-content: space-between; gap: 7px; }
.path-list header span, .reference-list header span { color: #236f9f; font-size: 12px; font-weight: 750; text-transform: uppercase; }
.path-list header b, .reference-list header b { overflow: hidden; color: #5f6970; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.path-chain { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.path-chain small { color: #8a8a91; font-size: 12px; }
.path-chain button { display: inline-flex; align-items: center; gap: 3px; padding: 4px 5px; color: #245e86; border: 1px solid #cbd9e2; background: #fff; font: 12px "SFMono-Regular", Consolas, monospace; cursor: pointer; }
.reference-list { display: grid; align-content: start; gap: 8px; }
.reference-list article { display: grid; gap: 6px; padding: 10px; border-left: 3px solid #b58a38; background: #fcf9f3; }
.reference-list article[data-trusted='true'], .review-reference-list article[data-current='true'] { border-color: #218a60; background: #f1f8f5; }
.reference-title { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 0; color: #2e3d47; border: 0; background: transparent; font-size: 13px; font-weight: 700; text-align: left; cursor: pointer; }
.reference-list small { color: #727e86; font-size: 12px; line-height: 1.5; }
.binding { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 5px 6px; overflow: hidden; color: #376b8e; border: 1px solid #d8e2e8; background: #fff; font: 12px "SFMono-Regular", Consolas, monospace; cursor: pointer; }
.binding span { color: #a44f43; font: 12px Inter, sans-serif; }
.review-reference-list article > strong { color: #33434d; font-size: 12px; }
.role-list { display: flex; flex-wrap: wrap; gap: 4px; }
.role-list span { padding: 3px 5px; color: #42647b; border: 1px solid #ccd9e1; background: #fff; font-size: 12px; }
.context-empty, .context-error, .limitation { margin: 0; color: #7b878f; font-size: 12px; line-height: 1.6; }
.context-empty { padding: 18px 12px; text-align: center; }
.context-error { display: flex; align-items: flex-start; gap: 6px; margin: 8px 0; padding: 8px; color: #a34940; background: #fff3f1; }
.limitation { margin-top: 8px; padding: 7px 8px; border-left: 2px solid #a8b5bd; background: #f5f7f8; }
.spinning { animation: spin .85s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
