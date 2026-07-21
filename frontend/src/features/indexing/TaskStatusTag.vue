<script setup lang="ts">
import { computed } from 'vue';
import type { TaskStatus } from '@/types/tasks';

const props = defineProps<{ status: TaskStatus }>();

const label = computed(() => ({
  QUEUED: '排队中',
  RUNNING: '运行中',
  CANCEL_REQUESTED: '取消中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELED: '已取消',
}[props.status]));

const tagType = computed(() => {
  if (props.status === 'SUCCEEDED') return 'success';
  if (props.status === 'FAILED') return 'danger';
  if (props.status === 'RUNNING') return 'primary';
  if (props.status === 'CANCEL_REQUESTED') return 'warning';
  return 'info';
});
</script>

<template>
  <el-tag :type="tagType" effect="plain">{{ label }}</el-tag>
</template>
