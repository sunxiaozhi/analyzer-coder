<script setup lang="ts">
import type { RepositoryBranch } from '@/api/branches';
defineProps<{ branches: RepositoryBranch[]; selectedId: string; disabled: boolean }>();
const emit = defineEmits<{ select: [branchId: string] }>();
const labels = { PENDING: '未准备', BUILDING: '准备中', READY: '可查看', FAILED: '准备失败' };
</script>

<template>
  <div class="branch-list-region">
    <table class="branch-list">
      <caption class="branch-list-caption">受管分支 · {{ branches.length }}</caption>
      <thead><tr><th scope="col">分支</th><th scope="col">已发布提交</th><th scope="col">准备状态</th><th scope="col">操作</th></tr></thead>
      <tbody>
        <tr v-for="branch in branches" :key="branch.id" :class="{ 'branch-row-selected': selectedId === branch.id }">
          <th scope="row" class="branch-name">{{ branch.name }}</th>
          <td><code>{{ branch.commitSha?.slice(0, 12) ?? '—' }}</code></td>
          <td>{{ labels[branch.status] }}</td>
          <td><button type="button" class="branch-select" :disabled="disabled" :aria-pressed="selectedId === branch.id" @click="emit('select', branch.id)">{{ selectedId === branch.id ? '当前选择' : '选择分支' }}</button></td>
        </tr>
        <tr v-if="!branches.length"><td colspan="4">尚未添加分支。请从远程发现或输入已有分支名称。</td></tr>
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
</style>
