<script setup lang="ts">
import { shallowRef, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { intelligenceApi, type CodeGraphArtifact, type GraphResult } from '@/api/intelligence';
import CallGraphCanvas from '@/features/graph/CallGraphCanvas.vue';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositories = useRepositoryStore();
const route = useRoute();
const depth = shallowRef(3);
const symbol = shallowRef('');
const result = shallowRef<GraphResult | null>(null);
const artifact = shallowRef<CodeGraphArtifact | null>(null);
const busy = shallowRef(false);
const building = shallowRef(false);
const lastAutoQuery = shallowRef('');

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
    await loadArtifact();
    if (task.status === 'FAILED') {
      ElMessage.error(task.errorMessage ?? 'CodeGraph 构建失败');
    } else {
      ElMessage.success('CodeGraph 产物已发布');
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
        CodeGraph {{ artifact.cliVersion }} · {{ artifact.status }}
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
          <b>确定性影响分析</b>
          <span>深度 {{ depth }}</span>
        </div>
        <div class="impact-score">
          <b>{{ result?.risk ?? '—' }}</b>
          <span>变更风险</span>
        </div>
        <h3>影响关系（{{ result?.edges.length ?? 0 }}）</h3>
        <ul class="impact-relations">
          <li v-for="(edge, index) in result?.edges"
            :key="`${edge.source}|${edge.target}|${edge.relation}|${index}`">
            <span class="relation-symbol" :title="edge.source">{{ edge.source }}</span>
            <span class="relation-arrow">→</span>
            <span class="relation-symbol" :title="edge.target">{{ edge.target }}</span>
            <em>{{ edge.relation }}</em>
          </li>
        </ul>
        <el-alert v-for="item in result?.limitations" :key="item" :title="item"
          type="info" :closable="false" />
      </aside>
    </div>
  </section>
</template>

<style scoped>
.impact-relations {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0 14px 14px;
  list-style: none;
}
.impact-relations li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 14px minmax(0, 1fr);
  align-items: center;
  gap: 4px;
  padding: 9px 10px;
  border: 1px solid #eceef1;
  border-radius: 6px;
  background: #fafbfc;
  font-size: 10px;
}
.relation-symbol {
  overflow: hidden;
  font-family: "SFMono-Regular", Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.relation-arrow { color: #0066cc; text-align: center; }
.impact-relations em {
  grid-column: 1 / -1;
  color: #718090;
  font-size: 9px;
  font-style: normal;
  letter-spacing: 0.05em;
}
.impact-panel :deep(.el-alert) { margin: 8px 14px; width: auto; }

@media (max-width: 760px) {
  .graph-toolbar { flex-wrap: wrap; min-height: 112px; padding: 8px 0; }
  .graph-search { width: 100%; }
}
</style>
