<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { intelligenceApi, type CodeGraphArtifact, type GraphResult } from '@/api/intelligence';
import CallGraphCanvas from '@/features/graph/CallGraphCanvas.vue';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { statusLabel } from '@/utils/displayLabels';

const repositories = useRepositoryStore();
const route = useRoute();
const depth = shallowRef(3);
const symbol = shallowRef('');
const result = shallowRef<GraphResult | null>(null);
const artifact = shallowRef<CodeGraphArtifact | null>(null);
const busy = shallowRef(false);
const building = shallowRef(false);
const lastAutoQuery = shallowRef('');
const nodeById = computed(
  () => new Map((result.value?.nodes ?? []).map((node) => [node.id, node])),
);
const edgeById = computed(
  () => new Map((result.value?.edges ?? []).map((edge) => [edge.id, edge])),
);

function nodeLabel(nodeId: string) {
  return nodeById.value.get(nodeId)?.symbol ?? nodeId;
}

function nodeLocation(nodeId: string) {
  const node = nodeById.value.get(nodeId);
  if (!node) return '位置不可用';
  return `${node.filePath}${node.startLine ? `:${node.startLine}` : ''}`;
}

function pathRelation(edgeId: string | undefined) {
  return edgeId ? (edgeById.value.get(edgeId)?.relation ?? '依赖') : '依赖';
}

function limitationLabel(value: string) {
  if (value === 'CODEGRAPH_STATIC_ANALYSIS_ONLY') return '静态分析无法确认反射、运行时分派和数据驱动调用';
  if (value.startsWith('CODEGRAPH_DUPLICATE_SYMBOL_DEFINITIONS:')) return `存在 ${value.split(':')[1]} 个同名定义，本次结果合并展示`;
  if (value.startsWith('CODEGRAPH_AFFECTED_NODE_UNMAPPED:')) return `有 ${value.split(':')[1]} 条 CLI 影响记录无法映射到可定位节点，未生成关系`;
  if (value.startsWith('CODEGRAPH_NODE_COUNT_MISMATCH:')) return 'CLI 影响节点数与可定位传播节点数不一致，请核对产物版本';
  if (value.startsWith('CODEGRAPH_EDGE_COUNT_MISMATCH:')) return 'CLI 影响边数与可展示真实边数不一致，页面没有补造连线';
  if (value === 'CODEGRAPH_DYNAMIC_RESOURCE_REFERENCES_PRESENT') return '检测到动态资源引用，静态路径可能不完整';
  return value;
}

async function loadArtifact() {
  artifact.value = repositories.selectedRepositoryId
    ? await intelligenceApi.latestGraph(repositories.selectedRepositoryId)
    : null;
}

async function build() {
  if (!repositories.selectedRepositoryId) {
    ElMessage.warning('请先选择仓库');
    return;
  }
  building.value = true;
  try {
    const task = await intelligenceApi.buildGraph(repositories.selectedRepositoryId);
    if (task.status === 'FAILED') {
      ElMessage.error(task.errorMessage ?? 'CodeGraph 构建失败');
    } else {
      result.value = null;
      ElMessage.info('CodeGraph 构建任务已提交，任务完成后刷新页面即可查询');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '构建失败');
  } finally {
    building.value = false;
  }
}

async function analyze() {
  if (!repositories.selectedRepositoryId) {
    ElMessage.warning('请先选择仓库');
    return;
  }
  if (!symbol.value.trim()) {
    ElMessage.warning('请输入符号名');
    return;
  }
  if (!artifact.value) {
    ElMessage.warning('当前 Snapshot 尚未发布 CodeGraph，请先提交构建任务并等待完成');
    return;
  }
  busy.value = true;
  try {
    result.value = await intelligenceApi.graph(
      repositories.selectedRepositoryId,
      symbol.value.trim(),
      depth.value,
      'BOTH',
    );
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分析失败');
  } finally {
    busy.value = false;
  }
}

watch(
  () => repositories.selectedRepositoryId,
  () => {
    result.value = null;
    void loadArtifact();
  },
  { immediate: true },
);

watch(
  () => [repositories.selectedRepositoryId, route.query.symbol, route.query.depth, route.query.analyze] as const,
  ([repositoryId, routeSymbol, routeDepth, autoAnalyze]) => {
    if (typeof routeSymbol !== 'string' || !routeSymbol.trim()) return;
    symbol.value = routeSymbol;
    const parsedDepth = typeof routeDepth === 'string' ? Number.parseInt(routeDepth, 10) : 3;
    depth.value = Number.isFinite(parsedDepth) ? Math.min(5, Math.max(1, parsedDepth)) : 3;
    const autoKey = `${repositoryId ?? ''}:${symbol.value}:${depth.value}`;
    if (autoAnalyze === '1' && repositoryId && lastAutoQuery.value !== autoKey) {
      lastAutoQuery.value = autoKey;
      void analyze();
    }
  },
  { immediate: true },
);
</script>

<template>
  <section class="graph-page">
    <div class="graph-toolbar">
      <el-input v-model="symbol" class="graph-search app-search-input" :prefix-icon="Search"
        clearable placeholder="输入类、函数、方法或路由符号" @keyup.enter="analyze" />
      <label>
        深度
        <el-input-number v-model="depth" :min="1" :max="5" size="small" />
      </label>
      <el-tag v-if="artifact" type="success">
        CodeGraph {{ artifact.cliVersion }} · {{ statusLabel(artifact.status) }}
      </el-tag>
      <el-button :loading="building" @click="build">
        {{ artifact ? '重新构建' : '构建 CodeGraph' }}
      </el-button>
      <el-button type="primary" :loading="busy" @click="analyze">影响分析</el-button>
    </div>

    <div class="graph-workspace">
      <CallGraphCanvas :result="result" />

      <aside class="impact-panel">
        <div class="pane-head">
          <b>真实传播路径</b>
          <span>CodeGraph CLI · 请求深度 {{ depth }}</span>
        </div>
        <div v-if="result" class="propagation-summary">
          <span><b>{{ result.affectedNodeCount }}</b>受影响节点</span>
          <span><b>{{ result.paths.length }}</b>条可解释路径</span>
          <span><b>{{ result.maxDepthReached }}</b>实际最大深度</span>
        </div>
        <p v-if="result" class="graph-provenance">
          Snapshot {{ result.snapshotId.slice(0, 8) }} · Artifact {{ result.graphArtifactId.slice(0, 8) }} · CLI {{ result.cliVersion }}
        </p>
        <div v-if="result" class="coverage-line" :data-complete="result.coverage.complete">
          <b>{{ result.coverage.complete ? '路径覆盖完整' : '路径覆盖不完整' }}</b>
          <span>
            CLI {{ result.coverage.affectedRecordCount }} 条影响记录，已映射
            {{ result.coverage.representedAffectedRecordCount }} 条
          </span>
        </div>
        <h3>传播链（{{ result?.paths.length ?? 0 }}）</h3>
        <ul class="impact-paths">
          <li v-for="path in result?.paths" :key="path.targetNodeId">
            <div class="path-head">
              <b>深度 {{ path.depth }}</b>
              <span>{{ nodeLabel(path.targetNodeId) }}</span>
            </div>
            <div class="path-chain">
              <template v-for="(nodeId, index) in path.nodeIds" :key="nodeId">
                <span v-if="index" class="path-arrow">← {{ pathRelation(path.edgeIds[index - 1]) }}</span>
                <div class="path-node" :class="{ focus: index === 0 }">
                  <strong>{{ nodeLabel(nodeId) }}</strong>
                  <small>{{ nodeLocation(nodeId) }}</small>
                </div>
              </template>
            </div>
          </li>
        </ul>
        <p v-if="result && !result.paths.length" class="empty-paths">
          当前符号没有可由真实边解释的下游传播路径。
        </p>
        <el-alert v-for="item in result?.limitations" :key="item" :title="limitationLabel(item)"
          type="info" :closable="false" />
      </aside>
    </div>
  </section>
</template>

<style scoped>
.graph-page {
  gap: 10px;
}
.graph-toolbar {
  min-height: 54px;
  padding: 8px 12px;
  border: 1px solid #dce4eb;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 1px 3px #24384c0a;
}
.impact-panel .pane-head {
  min-height: 44px;
  height: 44px;
}
.propagation-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  margin: 12px 14px 8px;
  border: 1px solid #e2e7ec;
  background: #e2e7ec;
}
.propagation-summary span {
  display: grid;
  gap: 3px;
  padding: 9px;
  color: #71717a;
  background: #fff;
  font-size: 13px;
}
.propagation-summary b { color: #232329; font: 650 16px "SFMono-Regular", Consolas, monospace; }
.coverage-line {
  display: grid;
  gap: 3px;
  margin: 10px 14px 12px;
  padding: 8px 10px;
  color: #8d5c24;
  border-left: 3px solid #c4852d;
  background: #fbf5ec;
  font-size: 13px;
}
.coverage-line[data-complete='true'] { color: #166347; border-color: var(--app-color-success); background: #eff8f4; }
.coverage-line span { color: #65656c; line-height: 1.45; }
.impact-paths {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0 14px 14px;
  list-style: none;
}
.impact-paths > li {
  display: grid;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #eceef1;
  border-radius: 6px;
  background: #fafbfc;
  font-size: 13px;
}
.path-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #65656c; }
.path-head b { color: var(--app-color-action); font-size: 13px; text-transform: uppercase; }
.path-head span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.path-chain { display: flex; align-items: stretch; gap: 5px; overflow-x: auto; padding-bottom: 3px; }
.path-node { display: grid; flex: 0 0 150px; gap: 3px; padding: 6px 7px; border-left: 2px solid #8292a3; background: #fff; }
.path-node.focus { border-color: var(--app-color-action); background: #edf6ff; }
.path-node strong,
.path-node small { overflow: hidden; font-family: "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.path-node strong { color: #242428; font-size: 13px; }
.path-node small { color: #71717a; font-size: 12px; }
.path-arrow { align-self: center; flex: 0 0 auto; color: var(--app-color-action); font: 13px "SFMono-Regular", Consolas, monospace; }
.empty-paths { margin: 0 14px 12px; color: #71717a; font-size: 13px; line-height: 1.5; }
.impact-panel :deep(.el-alert) { margin: 8px 14px; width: auto; }
.graph-provenance { margin: 0 14px 10px; color: var(--app-text-muted); font: 13px "SFMono-Regular", Consolas, monospace; }

@media (max-width: 760px) {
  .graph-toolbar { flex-wrap: wrap; min-height: 112px; padding: 10px; }
  .graph-search { width: 100%; }
}
</style>
