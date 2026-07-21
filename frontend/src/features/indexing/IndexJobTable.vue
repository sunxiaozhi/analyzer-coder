<script setup lang="ts">
import TaskStatusTag from '@/features/indexing/TaskStatusTag.vue';
import type { Repository } from '@/types/api';
import type { Task } from '@/types/tasks';

const props = defineProps<{
  jobs: readonly Task[];
  repositories: Repository[];
  selectedJobId: string | null;
  loading: boolean;
}>();

const emit = defineEmits<{ select: [taskId: string] }>();

function repositoryName(repositoryId: string) {
  return props.repositories.find((item) => item.id === repositoryId)?.name ?? repositoryId;
}
</script>

<template>
  <el-table
    v-loading="loading"
    :data="jobs"
    :current-row-key="selectedJobId ?? undefined"
    row-key="id"
    highlight-current-row
    empty-text="尚无任务"
    @row-click="(row: Task) => emit('select', row.id)"
  >
    <el-table-column label="仓库" min-width="180">
      <template #default="{ row }">{{ repositoryName(row.repositoryId) }}</template>
    </el-table-column>
    <el-table-column label="类型" width="130">
      <template #default="{ row }">{{ row.type === 'FULL' ? '全量内容索引' : '增量内容索引' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="110">
      <template #default="{ row }"><TaskStatusTag :status="row.status" /></template>
    </el-table-column>
    <el-table-column prop="currentStep" label="当前阶段" min-width="170" />
    <el-table-column label="创建时间" width="190">
      <template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template>
    </el-table-column>
  </el-table>
</template>
