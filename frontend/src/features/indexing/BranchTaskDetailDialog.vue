<script setup lang="ts">
import type { BranchPreparationJob } from '@/api/branches';
defineProps<{ job: BranchPreparationJob; projectName: string; branchName: string; kindLabel: string; statusLabel: string; stageLabel: string }>();
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
        <div><dt>阶段</dt><dd>{{ stageLabel }}</dd></div>
        <div class="full"><dt>任务编号</dt><dd><code>{{ job.id }}</code></dd></div>
        <div class="full"><dt>快照编号</dt><dd><code>{{ job.snapshotId ?? '尚未锁定' }}</code></dd></div>
      </dl>
      <el-alert v-if="job.error" :title="job.error" type="error" :closable="false" show-icon />
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
.task-detail .full { grid-column: 1 / -1; }
.task-detail dt { color: var(--app-text-muted); font-size: 12px; margin-bottom: 7px; }
.task-detail dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.task-detail code { font-size: 13px; }
.task-note { margin: 20px 0 0; color: var(--app-text-muted); font-size: 13px; line-height: 1.6; }
@media (max-width: 480px) { .task-detail dl { grid-template-columns: minmax(0, 1fr); gap: 16px; } }
</style>
