<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue';
import { branchesApi, type BranchPreparationJob } from '@/api/branches';
import AppPagination from '@/components/AppPagination.vue';
const props = defineProps<{ repositoryId: string; branchId: string; revision: string }>();
const jobs = shallowRef<BranchPreparationJob[]>([]);
const pageNum = shallowRef(1), pageSize = shallowRef(15), total = shallowRef(0);
const loading = shallowRef(false), error = shallowRef('');
let version = 0, alive = true;
const kinds: Record<string, string> = { SYNC: '同步代码', CONTENT: '内容索引', GRAPH: '代码图谱', VECTORS: '向量索引', PREPARE: '一键准备' };
const states: Record<string, string> = { QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败' };
const stages: Record<string, string> = { QUEUED: '等待执行', RESOLVING: '确认目标版本', SYNC: '同步代码', PUBLISHING: '发布分支代码', INDEXING: '构建内容索引', GRAPH: '构建图谱', EMBEDDING: '构建向量', COMPLETED: '已完成', FAILED: '失败' };
async function load() {
  const current = ++version;
  loading.value = true; error.value = '';
  try {
    const result = await branchesApi.preparationHistory(props.repositoryId, pageNum.value, pageSize.value, props.branchId);
    if (alive && current === version) { jobs.value = result.items; total.value = result.total; }
  } catch (cause) { if (alive && current === version) error.value = cause instanceof Error ? cause.message : '任务记录加载失败'; }
  finally { if (alive && current === version) loading.value = false; }
}
watch(() => [props.repositoryId, props.branchId] as const, () => { pageNum.value = 1; jobs.value = []; void load(); }, { immediate: true });
watch(() => props.revision, () => void load());
watch([pageNum, pageSize], () => void load());
onBeforeUnmount(() => { alive = false; version++; });
</script>
<template>
  <section class="branch-history" aria-label="当前分支任务记录">
    <div class="history-heading"><p>任务记录属于执行时的代码版本，历史任务不会改变当前索引状态。</p><el-button :loading="loading" @click="load">刷新记录</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="history-scroll"><table><thead><tr><th>任务</th><th>状态</th><th>执行阶段</th><th>代码内容版本</th></tr></thead><tbody>
      <template v-for="job in jobs" :key="job.id"><tr><td>{{ kinds[job.kind] }}</td><td>{{ states[job.status] }}</td><td>{{ stages[job.stage] ?? job.stage }}</td><td><code>{{ job.contentVersion?.slice(0, 8) || '等待锁定' }}</code></td></tr><tr v-if="job.error" class="history-error"><td colspan="4">{{ job.error }}</td></tr></template>
      <tr v-if="!jobs.length"><td colspan="4" class="history-empty">{{ loading ? '正在加载任务记录…' : '当前分支还没有任务记录' }}</td></tr>
    </tbody></table></div>
    <AppPagination :page-num="pageNum" :page-size="pageSize" :total="total" :disabled="loading" compact @page-change="value => pageNum = value" @size-change="value => { pageSize = value; pageNum = 1; }" />
  </section>
</template>
<style scoped>
.branch-history :deep(.pagination-summary) { flex-wrap: wrap; gap: 8px 12px; font-size: 12px; white-space: normal; }
.branch-history { display: grid; gap: 16px; }
.history-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.history-heading p { margin: 0; color: var(--app-text-muted); font-size: 12px; line-height: 1.8; }
.history-scroll { overflow-x: auto; border: 1px solid var(--app-border); border-radius: 7px; }
.history-scroll table { width: 100%; min-width: 480px; border-collapse: collapse; text-align: left; font-size: 13px; }
.history-scroll th, .history-scroll td { padding: 16px; border-bottom: 1px solid var(--app-border); }
.history-scroll th { background: var(--app-surface-subtle); color: var(--app-text-muted); font-weight: 500; }
.history-scroll tbody tr:last-child td { border-bottom: 0; }
.history-scroll .history-error td { padding-top: 0; color: var(--app-color-danger); font-size: 12px; overflow-wrap: anywhere; }
.history-empty { padding: 36px !important; text-align: center; color: var(--app-text-muted); }
</style>
