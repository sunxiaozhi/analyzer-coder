<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import type { BranchIndexStatus, RepositoryBranch } from '@/api/branches';
const props = withDefaults(defineProps<{ branches: RepositoryBranch[]; selectedId: string; disabled: boolean; canManage?: boolean; readingBranchId?: string | null; indexes?: BranchIndexStatus[] }>(), { canManage: false, indexes: () => [] });
const showArchived = shallowRef(false);
const visibleBranches = computed(() => props.branches.filter(branch => showArchived.value || branch.trackingStatus === 'ACTIVE'));
function status(branch: RepositoryBranch) { return props.indexes.find(item => item.branchId === branch.id && item.snapshotId === branch.snapshotId); }
const emit = defineEmits<{
  select: [branchId: string];
  archive: [branchId: string];
  restore: [branchId: string];
}>();
const labels = { PENDING: '未准备', BUILDING: '准备中', READY: '可查看', FAILED: '准备失败' };
</script>

<template>
  <div class="branch-list-region">
    <label class="archive-filter"><input v-model="showArchived" type="checkbox" /> 显示已归档分支</label>
    <table class="branch-list">
      <caption class="branch-list-caption">受管分支 · {{ branches.length }}</caption>
      <thead><tr><th scope="col">分支</th><th scope="col">已同步提交</th><th scope="col">代码状态</th><th scope="col">内容索引</th><th scope="col">代码图谱</th><th scope="col">操作</th></tr></thead>
      <tbody>
        <tr v-for="branch in visibleBranches" :key="branch.id" :class="{ 'branch-row-selected': selectedId === branch.id }">
          <th scope="row" class="branch-name">{{ branch.name }} <small v-if="branch.id === readingBranchId">阅读中</small></th>
          <td><code>{{ branch.commitSha?.slice(0, 12) ?? '—' }}</code></td>
          <td>{{ labels[branch.status] }}</td>
          <td>{{ status(branch)?.contentReady ? '已就绪' : '待构建' }}</td>
          <td>{{ status(branch)?.graphReady ? '已就绪' : '待构建' }}</td>
          <td class="branch-actions">
            <button v-if="branch.trackingStatus === 'ACTIVE'" type="button" class="branch-select" :disabled="disabled" :aria-pressed="selectedId === branch.id" @click="emit('select', branch.id)">{{ selectedId === branch.id ? '管理选中' : '选择分支' }}</button>
            <button v-if="canManage && branch.trackingStatus === 'ACTIVE'" type="button" class="branch-lifecycle" :disabled="disabled" @click="emit('archive', branch.id)">归档</button>
            <button v-else-if="canManage" type="button" class="branch-lifecycle" :disabled="disabled" @click="emit('restore', branch.id)">恢复跟踪</button>
          </td>
        </tr>
        <tr v-if="!visibleBranches.length"><td colspan="6">尚未添加分支。请从远程发现或输入已有分支名称。</td></tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.branch-list-region { overflow-x: auto; }
.branch-list { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; color: #334155; }
.branch-list-caption { text-align: left; font-weight: 600; padding: 0 0 10px; }
.branch-list th, .branch-list td { padding: 10px; border-bottom: 1px solid #dbe3ec; }
.branch-list thead { background: #f5f7fa; }
.branch-row-selected { background: #eff6ff; }
.branch-name { overflow-wrap: anywhere; max-width: 280px; }
.branch-select { border: 1px solid #dbe3ec; background: #fff; color: #2563eb; padding: 5px 8px; border-radius: 4px; cursor: pointer; white-space: nowrap; }
.branch-select:focus-visible { outline: 2px solid #2563eb; outline-offset: 2px; }
.branch-select:disabled { opacity: .6; cursor: wait; }
.branch-actions { display: flex; gap: 6px; }
.branch-lifecycle { padding: 5px 8px; color: #5b6672; border: 0; background: transparent; cursor: pointer; white-space: nowrap; }
.branch-lifecycle:hover { color: #1f2937; text-decoration: underline; }
.archive-filter { display: block; margin-bottom: 12px; color: #68778a; font-size: 12px; }
.branch-name small { color: #2563eb; font-weight: 400; }
</style>
