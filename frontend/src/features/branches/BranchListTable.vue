<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import { GitBranch, ChevronDown } from 'lucide-vue-next';
import type { BranchCodeOperation, BranchIndexStatus, BranchPreparationJob, RepositoryBranch } from '@/api/branches';
const props = withDefaults(defineProps<{
  branches: RepositoryBranch[]; selectedId: string; disabled: boolean;
  canManage?: boolean; canMaintain?: boolean; readingBranchId?: string | null;
  defaultBranch?: string | null; indexes?: BranchIndexStatus[]; jobs?: BranchPreparationJob[];
}>(), { canManage: false, canMaintain: false, indexes: () => [], jobs: () => [] });
const emit = defineEmits<{
  select: [branchId: string, tab?: 'status' | 'tasks']; read: [branchId: string, target?: 'search' | 'atlas'];
  operate: [branchId: string, kind: BranchCodeOperation]; vectors: [branchId: string];
  archive: [branchId: string]; restore: [branchId: string];
}>();
const showArchived = shallowRef(false);
const visibleBranches = computed(() => props.branches.filter(branch => showArchived.value || branch.trackingStatus === 'ACTIVE'));
const statuses = computed(() => new Map(props.indexes.map(item => [item.branchId, item])));
function status(branch: RepositoryBranch) {
  const value = statuses.value.get(branch.id);
  return value?.contentVersion === branch.contentVersion ? value : undefined;
}
function activeJob(branch: RepositoryBranch) { return props.jobs.find(job => job.branchId === branch.id && ['QUEUED', 'RUNNING'].includes(job.status)); }
function unavailable(branch: RepositoryBranch) { return props.disabled || Boolean(activeJob(branch)) || branch.status === 'BUILDING' || branch.trackingStatus !== 'ACTIVE'; }
function readable(branch: RepositoryBranch) { return branch.trackingStatus === 'ACTIVE' && branch.status === 'READY' && Boolean(branch.contentVersion) && Boolean(status(branch)?.contentReady); }
function syncLabel(branch: RepositoryBranch) {
  if (branch.trackingStatus === 'ARCHIVED') return '已取消跟踪';
  if (branch.status === 'BUILDING') return '同步中';
  if (branch.status === 'FAILED') return '同步失败';
  return branch.contentVersion ? '已同步' : '未同步';
}
function indexLabel(branch: RepositoryBranch, kind: 'content' | 'graph') {
  const job = activeJob(branch);
  if (job && (job.kind === (kind === 'content' ? 'CONTENT' : 'GRAPH') || (job.kind === 'PREPARE' && job.stage === (kind === 'content' ? 'INDEXING' : 'GRAPH')))) return job.status === 'QUEUED' ? '排队中' : '构建中';
  return status(branch)?.[kind === 'content' ? 'contentReady' : 'graphReady'] ? '已就绪' : '待构建';
}
function command(branch: RepositoryBranch, action: string) {
  if (props.disabled) return;
  if (action === 'details' || action === 'tasks') { emit('select', branch.id, action === 'tasks' ? 'tasks' : 'status'); return; }
  if (action === 'restore' && props.canManage && branch.trackingStatus === 'ARCHIVED') { emit('restore', branch.id); return; }
  if (action === 'search' && readable(branch)) { emit('read', branch.id, 'search'); return; }
  if (action === 'atlas' && branch.trackingStatus === 'ACTIVE' && branch.status === 'READY' && status(branch)?.graphReady) { emit('read', branch.id, 'atlas'); return; }
  if (unavailable(branch)) return;
  if (action === 'archive' && props.canManage) emit('archive', branch.id);
  else if (props.canMaintain && action === 'vectors' && status(branch)?.contentReady) emit('vectors', branch.id);
  else if (props.canMaintain && ['SYNC', 'CONTENT', 'GRAPH'].includes(action) && (action === 'SYNC' || branch.contentVersion)) emit('operate', branch.id, action as BranchCodeOperation);
}
</script>
<template>
  <div class="branch-list-region">
    <div class="list-preferences"><span>{{ visibleBranches.length }} 个分支</span><label class="archive-filter"><input v-model="showArchived" type="checkbox" /> 显示已取消跟踪</label></div>
    <p class="table-scroll-hint">左右滑动查看分支状态和操作</p>
    <div class="branch-table-scroll">
      <table class="branch-list" aria-label="项目分支列表">
        <thead><tr><th scope="col">分支</th><th scope="col">已同步提交</th><th scope="col">同步状态</th><th scope="col">内容索引</th><th scope="col">代码图谱</th><th scope="col" class="operation-heading">操作</th></tr></thead>
        <tbody>
          <tr v-for="branch in visibleBranches" :key="branch.id" :class="{ 'branch-row-reading': readingBranchId === branch.id, 'branch-row-archived': branch.trackingStatus === 'ARCHIVED' }">
            <th scope="row" class="branch-name"><div><GitBranch :size="15" /><button type="button" class="branch-detail-link" :disabled="disabled" @click="emit('select', branch.id, 'status')">{{ branch.name }}</button></div><div class="branch-badges"><small v-if="branch.name === defaultBranch" class="default-badge">默认</small><small v-if="branch.id === readingBranchId" class="reading-badge">当前阅读</small><small v-if="activeJob(branch)" class="running-badge">任务执行中</small></div></th>
            <td><code>{{ branch.commitSha?.slice(0, 12) ?? '—' }}</code></td>
            <td><button type="button" class="state-link" :data-tone="branch.status === 'FAILED' ? 'danger' : branch.contentVersion ? 'ready' : 'pending'" :disabled="disabled" @click="emit('select', branch.id, 'status')"><i></i>{{ syncLabel(branch) }}</button></td>
            <td><button type="button" class="state-link" :data-tone="status(branch)?.contentReady ? 'ready' : 'pending'" :disabled="disabled" @click="emit('select', branch.id, 'status')"><i></i>{{ indexLabel(branch, 'content') }}</button></td>
            <td><button type="button" class="state-link" :data-tone="status(branch)?.graphReady ? 'ready' : 'pending'" :disabled="disabled" @click="emit('select', branch.id, 'status')"><i></i>{{ indexLabel(branch, 'graph') }}</button></td>
            <td class="operation-cell"><div class="branch-actions">
              <button v-if="branch.trackingStatus === 'ACTIVE'" type="button" class="branch-read" :disabled="disabled || !readable(branch) || readingBranchId === branch.id" @click="emit('read', branch.id)">{{ readingBranchId === branch.id ? '当前阅读' : '切换阅读' }}</button>
              <button v-if="canMaintain && branch.trackingStatus === 'ACTIVE'" type="button" class="branch-prepare" :disabled="unavailable(branch)" @click="emit('operate', branch.id, 'PREPARE')">一键准备</button>
              <el-dropdown trigger="click" :disabled="disabled" @command="(action: string) => command(branch, action)">
                <button type="button" class="branch-more" :disabled="disabled" :aria-label="branch.name + '的更多操作'">更多<ChevronDown :size="13" /></button>
                <template #dropdown><el-dropdown-menu>
                  <el-dropdown-item command="details">分支详情</el-dropdown-item><el-dropdown-item command="tasks">任务记录</el-dropdown-item>
                  <template v-if="branch.trackingStatus === 'ACTIVE'">
                    <el-dropdown-item command="search" :disabled="!readable(branch)" divided>打开代码</el-dropdown-item>
                    <el-dropdown-item command="atlas" :disabled="branch.status !== 'READY' || !status(branch)?.graphReady">打开代码图谱</el-dropdown-item>
                    <template v-if="canMaintain"><el-dropdown-item command="SYNC" :disabled="unavailable(branch)" divided>同步代码</el-dropdown-item><el-dropdown-item command="CONTENT" :disabled="unavailable(branch) || !branch.contentVersion">重建内容索引</el-dropdown-item><el-dropdown-item command="GRAPH" :disabled="unavailable(branch) || !branch.contentVersion">重建代码图谱</el-dropdown-item><el-dropdown-item command="vectors" :disabled="unavailable(branch) || !status(branch)?.contentReady">构建向量索引</el-dropdown-item></template>
                    <el-dropdown-item v-if="canManage" command="archive" :disabled="unavailable(branch)" divided>取消跟踪</el-dropdown-item>
                  </template>
                  <el-dropdown-item v-else-if="canManage" command="restore" divided>恢复跟踪</el-dropdown-item>
                </el-dropdown-menu></template>
              </el-dropdown>
            </div></td>
          </tr>
          <tr v-if="!visibleBranches.length"><td colspan="6" class="branch-table-empty">尚未跟踪分支，点击“添加跟踪分支”选择已有分支。</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
<style scoped>
.table-scroll-hint { display: none; margin: 0 0 12px; color: var(--app-text-muted); font-size: 11px; }
@media (max-width: 1100px) { .table-scroll-hint { display: block; } }
.branch-list-region { min-width: 0; }
.list-preferences { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 2px 16px; color: var(--app-text-muted); font-size: 12px; }
.archive-filter { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.archive-filter input { accent-color: var(--app-color-action); }
.branch-table-scroll { overflow-x: auto; border: 1px solid var(--app-border); border-radius: 9px; }
.branch-list { width: 100%; min-width: 740px; border-collapse: collapse; text-align: left; color: var(--app-text-regular); font-size: 13px; }
.branch-list th, .branch-list td { padding: 19px 14px; border-bottom: 1px solid var(--app-border); vertical-align: middle; }
.branch-list thead th { padding: 15px 14px; background: var(--app-surface-subtle); color: var(--app-text-muted); font-size: 12px; font-weight: 500; white-space: nowrap; }
.branch-list tbody tr:last-child > * { border-bottom: 0; }
.branch-list tbody tr:hover { background: var(--app-surface-subtle); }
.branch-list .branch-row-reading { background: #f5f9fd; }
.branch-row-reading .branch-name { box-shadow: inset 3px 0 var(--app-color-action); }
.branch-row-archived { opacity: .7; }
.branch-name { min-width: 170px; max-width: 260px; font-weight: 500; }
.branch-name > div:first-child { display: flex; align-items: center; gap: 8px; }
.branch-name svg { flex: none; color: var(--app-text-muted); }
.branch-detail-link { padding: 0; text-align: left; overflow-wrap: anywhere; font-family: var(--app-font-mono); font-size: 13px; font-weight: 600; }
.branch-detail-link:hover { color: var(--app-color-action); }
.branch-badges { display: flex; flex-wrap: wrap; gap: 5px; padding-left: 23px; }
.branch-badges:not(:empty) { margin-top: 8px; }
.branch-badges small { padding: 2px 6px; border-radius: 4px; font-size: 10px; white-space: nowrap; }
.default-badge { background: #eaf0f4; color: var(--app-text-muted); }
.reading-badge { background: var(--app-color-action-soft); color: var(--app-color-action); }
.running-badge { color: #996119; background: var(--app-color-warning-soft); }
.branch-list code { font-size: 12px; color: var(--app-text-muted); white-space: nowrap; }
.branch-list button { border: 0; background: transparent; font: inherit; cursor: pointer; }
.branch-list button:disabled { cursor: default; opacity: .5; }
.state-link { display: inline-flex; align-items: center; gap: 7px; color: var(--app-text-muted); white-space: nowrap; font-size: 12px !important; padding: 5px 0; }
.state-link i { width: 6px; height: 6px; border-radius: 50%; background: #9aa8b2; }
.state-link[data-tone='ready'] { color: #277453; }.state-link[data-tone='ready'] i { background: #3c926c; }
.state-link[data-tone='danger'] { color: var(--app-color-danger); }.state-link[data-tone='danger'] i { background: var(--app-color-danger); }
.branch-actions { display: flex; justify-content: flex-end; align-items: center; gap: 14px; white-space: nowrap; }
.operation-heading { text-align: right; }
.branch-actions .branch-read, .branch-actions .branch-prepare { color: var(--app-color-action); font-size: 12px; padding: 6px 0; }
.branch-more { display: inline-flex; align-items: center; gap: 4px; color: var(--app-text-muted); font-size: 12px !important; padding: 6px 0; }
.branch-table-empty { padding: 50px 20px !important; text-align: center; color: var(--app-text-muted); line-height: 1.8; }
</style>
