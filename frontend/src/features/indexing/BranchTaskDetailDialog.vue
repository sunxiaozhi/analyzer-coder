<script setup lang="ts">
import { computed } from 'vue';
import TaskProgress from './TaskProgress.vue';
import TaskDuration from './TaskDuration.vue';
import type { BranchPreparationJob } from '@/api/branches';
const props = defineProps<{ job: BranchPreparationJob; projectName: string; branchName: string; kindLabel: string; statusLabel: string }>();
const errorMessage = computed(() => {
  const message = props.job.error;
  const suffix = `；任务编号：${props.job.id}`;
  return message?.endsWith(suffix) ? message.slice(0, -suffix.length) : message;
});
const open = defineModel<boolean>({ required: true });
const emit = defineEmits<{ manage: [] }>();
</script>

<template>
  <el-dialog v-model="open" title="分支任务详情" width="min(760px, 94vw)" destroy-on-close>
    <article class="task-detail">
      <h3>{{ branchName }} · {{ kindLabel }}</h3>
      <dl>
        <div><dt>项目</dt><dd>{{ projectName }}</dd></div>
        <div><dt>分支</dt><dd>{{ branchName }}</dd></div>
        <div><dt>状态</dt><dd>{{ statusLabel }}</dd></div>
        <div><dt>总耗时（含排队）</dt><dd><TaskDuration :task="job" mode="total" /></dd></div>
      </dl>
      <TaskProgress :task="job" />
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon />
      <p class="task-note">任务属于创建时锁定的版本；历史任务不代表当前分支的索引状态。</p>
    </article>
    <template #footer>
      <el-button @click="open = false">关闭</el-button>
      <el-button type="primary" plain @click="emit('manage')">前往分支管理</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.task-detail { color: var(--app-text); font-size: 14px; }
.task-detail h3 { margin: 0 0 24px; font-size: 18px; }
.task-detail dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 28px; margin: 0 0 24px; }
.task-detail dl > div { min-width: 0; }
.task-detail dt { color: var(--app-text-muted); font-size: 12px; margin-bottom: 7px; }
.task-detail dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.task-note { margin: 20px 0 0; color: var(--app-text-muted); font-size: 13px; line-height: 1.6; }
@media (max-width: 480px) { .task-detail dl { grid-template-columns: minmax(0, 1fr); gap: 16px; } }
</style>
