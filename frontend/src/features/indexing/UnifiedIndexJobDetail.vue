<script setup lang="ts">
import { computed } from 'vue';
import TaskStatusTag from '@/features/indexing/TaskStatusTag.vue';
import type { Repository } from '@/types/api';
import type { Task } from '@/types/tasks';

const props = defineProps<{ job: Task | null; repositories: Repository[]; actionPending: boolean }>();
const emit = defineEmits<{ cancel: [taskId: string]; retry: [taskId: string] }>();

const canCancel = computed(() => props.job?.status === 'QUEUED' || props.job?.status === 'RUNNING');
const canRetry = computed(() => props.job?.status === 'FAILED');

function repositoryName(repositoryId: string) {
  return props.repositories.find((item) => item.id === repositoryId)?.name ?? repositoryId;
}
</script>

<template>
  <aside class="surface detail-panel">
    <template v-if="job">
      <div class="section-head"><h2>任务详情</h2><TaskStatusTag :status="job.status" /></div>
      <dl class="meta-grid">
        <div><dt>任务 ID</dt><dd class="mono">{{ job.id }}</dd></div>
        <div><dt>仓库</dt><dd>{{ repositoryName(job.repositoryId) }}</dd></div>
        <div><dt>阶段</dt><dd class="mono">{{ job.currentStep ?? '—' }}</dd></div>
        <div><dt>创建时间</dt><dd>{{ new Date(job.createdAt).toLocaleString() }}</dd></div>
        <div><dt>开始时间</dt><dd>{{ job.startedAt ? new Date(job.startedAt).toLocaleString() : '—' }}</dd></div>
        <div><dt>结束时间</dt><dd>{{ job.finishedAt ? new Date(job.finishedAt).toLocaleString() : '—' }}</dd></div>
      </dl>
      <el-alert v-if="job.errorMessage" :title="job.errorMessage" type="error" :closable="false" show-icon />
      <el-alert
        v-else-if="job.status === 'CANCEL_REQUESTED'"
        title="取消请求已受理，任务将在下一个安全检查点停止。"
        type="warning"
        :closable="false"
      />
      <p v-else class="terminal-note">终态任务不会被改写；失败重试会创建一个新的排队任务。</p>
      <div class="task-actions">
        <el-button v-if="canCancel" :loading="actionPending" type="warning" @click="emit('cancel', job.id)">取消任务</el-button>
        <el-button v-if="canRetry" :loading="actionPending" type="primary" @click="emit('retry', job.id)">重试任务</el-button>
      </div>
    </template>
    <el-empty v-else description="选择任务查看详情" />
  </aside>
</template>

<style scoped>
.terminal-note {
  margin: 12px 16px 0;
  color: var(--app-text-muted);
  font-size: 11px;
  line-height: 1.55;
}

.task-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
