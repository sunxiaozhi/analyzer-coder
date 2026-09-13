<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { GitBranch, RefreshCw } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { branchesApi, type BranchContext, type RepositoryBranch } from '@/api/branches';
import type { UnifiedSearchResponse } from '@/api/intelligence';

const props = defineProps<{ repositoryId: string; canMaintain: boolean }>();
const branches = ref<RepositoryBranch[]>([]);
const selectedId = ref('');
const context = ref<BranchContext | null>(null);
const result = ref<UnifiedSearchResponse | null>(null);
const name = ref('');
const query = ref('');
const busy = ref(false);
const searching = ref(false);
const error = ref('');
let sequence = 0;
let searchSequence = 0;
let alive = true;
const selected = computed(() => branches.value.find(branch => branch.id === selectedId.value));
const statusLabels = { PENDING: '未准备', BUILDING: '准备中', READY: '可查看', FAILED: '准备失败' };
function message(cause: unknown) { return cause instanceof Error ? cause.message : '操作失败，请重试'; }
async function reload() {
  busy.value = true;
  error.value = '';
  try { const rows = await branchesApi.list(props.repositoryId); if (alive) branches.value = rows; }
  catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function select(branchId: string) {
  const version = ++sequence;
  ++searchSequence;
  selectedId.value = branchId;
  context.value = null;
  result.value = null;
  searching.value = false;
  error.value = '';
  if (!branches.value.find(branch => branch.id === branchId)?.snapshotId) return;
  try {
    const resolved = await branchesApi.context(props.repositoryId, branchId);
    if (alive && version === sequence) context.value = resolved;
  } catch (cause) { if (alive && version === sequence) error.value = message(cause); }
}
async function track() {
  if (!name.value.trim() || busy.value) return;
  busy.value = true; error.value = '';
  try {
    const branch = await branchesApi.track(props.repositoryId, name.value.trim());
    if (!alive) return;
    name.value = '';
    await reload();
    await select(branch.id);
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function prepare() {
  if (!selected.value || busy.value) return;
  const branchId = selected.value.id;
  busy.value = true; error.value = '';
  try {
    const prepared = await branchesApi.prepare(props.repositoryId, branchId);
    if (!alive) return;
    branches.value = branches.value.map(branch => branch.id === prepared.id ? prepared : branch);
    await select(branchId);
  } catch (cause) { if (alive) error.value = message(cause); }
  finally { if (alive) busy.value = false; }
}
async function search() {
  if (!context.value || !query.value.trim()) return;
  const version = ++searchSequence;
  searching.value = true; result.value = null; error.value = '';
  try {
    const response = await branchesApi.search(context.value, query.value.trim());
    if (alive && version === searchSequence) result.value = response;
  } catch (cause) { if (alive && version === searchSequence) error.value = message(cause); }
  finally { if (alive && version === searchSequence) searching.value = false; }
}
async function copyCoordinates() {
  if (!context.value) return;
  try {
    await navigator.clipboard.writeText(JSON.stringify({ repositoryId: context.value.repositoryId, branchId: context.value.branchId }, null, 2));
    ElMessage.success('已复制 MCP 分支参数');
  } catch { ElMessage.error('复制失败，请检查浏览器剪贴板权限'); }
}
onMounted(reload);
onBeforeUnmount(() => { alive = false; ++sequence; ++searchSequence; });
</script>

<template>
  <div class="branch-workspace">
    <div class="branch-controls">
      <GitBranch :size="18" />
      <el-select :model-value="selectedId" placeholder="选择分支" filterable :disabled="busy" aria-label="查看分支" @change="select">
        <el-option v-for="branch in branches" :key="branch.id" :value="branch.id" :label="`${branch.name} · ${statusLabels[branch.status]}`" />
      </el-select>
      <el-button :disabled="busy" aria-label="刷新分支" @click="reload"><RefreshCw :size="15" /></el-button>
      <el-button v-if="canMaintain" :disabled="!selected || busy" :loading="busy" @click="prepare">{{ selected?.snapshotId ? '更新分支快照' : '准备分支' }}</el-button>
    </div>
    <form v-if="canMaintain" class="branch-controls" @submit.prevent="track">
      <el-input v-model="name" placeholder="本地分支名称，如 release/1.0" aria-label="新增分支名称" :maxlength="200" :disabled="busy" />
      <el-button native-type="submit" :disabled="busy || !name.trim()">添加分支</el-button>
    </form>
    <p class="hint">本窗口独立查看分支，不切换工作目录，也不改变其他页面的当前快照。暂支持仓库中已有的本地 Git 分支。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="selected?.error" :title="selected.error" type="warning" :closable="false" />
    <div v-if="context" class="context-strip">
      <strong>{{ context.branchName }}</strong><code>{{ context.commitSha.slice(0, 12) }}</code>
      <el-button link type="primary" @click="select(selectedId)">刷新阅读版本</el-button>
      <el-button link type="primary" @click="copyCoordinates">复制 MCP 参数</el-button>
    </div>
    <el-empty v-else :description="selected ? (selected.snapshotId ? '正在载入分支阅读版本' : '该分支尚未准备，不会显示其他分支的数据') : '选择或添加一个分支'" />
    <template v-if="context">
      <form class="branch-controls" @submit.prevent="search">
        <el-input v-model="query" placeholder="检索该分支代码与适用知识" aria-label="分支检索词" :maxlength="1000" />
        <el-button native-type="submit" type="primary" :loading="searching" :disabled="!query.trim()">检索</el-button>
      </form>
      <p class="hint">带代码引用或执行要求的知识，需针对当前分支快照验证后才参与检索。</p>
      <el-alert v-if="result?.retrieval.degraded" title="部分检索通道不可用，以下仅展示可用通道的结果" type="warning" :closable="false" />
      <el-empty v-if="result && !result.evidence.length" description="该分支没有匹配的代码或适用知识" />
      <details v-for="(hit, index) in result?.evidence ?? []" :key="`${hit.sourceType}:${index}`" class="branch-hit">
        <summary><span>{{ hit.sourceType === 'CODE' ? '代码' : '知识' }}</span> {{ hit.title || hit.filePath }} <small v-if="hit.startLine">:{{ hit.startLine }}</small></summary>
        <pre>{{ hit.content }}</pre>
      </details>
    </template>
  </div>
</template>

<style scoped>
.branch-workspace { display: flex; flex-direction: column; gap: 14px; }
.branch-controls { display: flex; align-items: center; gap: 8px; }
.branch-controls .el-select, .branch-controls .el-input { flex: 1; min-width: 0; }
.hint { margin: 0; color: #68778a; font-size: 12px; line-height: 1.7; }
.context-strip { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px; background: #eff6ff; border-left: 3px solid #2563eb; color: #334155; }
code, pre { font-family: Consolas, monospace; }
.branch-hit { border: 1px solid #dbe3ec; border-radius: 6px; }
summary { cursor: pointer; padding: 12px; overflow-wrap: anywhere; font-size: 13px; }
summary span { color: #2563eb; margin-right: 8px; }
summary:focus-visible { outline: 2px solid #93c5fd; }
pre { margin: 0; padding: 14px; background: #f5f7fa; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.7; }
</style>
