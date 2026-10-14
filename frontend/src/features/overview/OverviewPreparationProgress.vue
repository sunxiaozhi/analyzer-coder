<script setup lang="ts">
import type { BranchPreparationJob } from '@/api/branches';
defineProps<{ jobs: readonly BranchPreparationJob[]; error: string; busy: boolean; canPrepare: boolean; readyForSearch?: boolean }>();
const emit = defineEmits<{ refresh: []; retry: [kind: BranchPreparationJob['kind']]; openSearch: []; openAsk: []; }>();
const kinds: Record<string, string> = { SYNC: '同步代码', CONTENT: '内容索引', GRAPH: '代码图谱', PREPARE: '分支准备', VECTORS: '向量索引' };
const states: Record<string, string> = { QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败' };
const stages: Record<string, string> = { QUEUED: '等待执行', RESOLVING: '确认目标版本', SYNC: '同步代码', PUBLISHING: '发布分支代码', INDEXING: '构建内容索引', GRAPH: '构建代码图谱', EMBEDDING: '构建向量', COMPLETED: '已完成', FAILED: '失败' };
</script>

<template>
  <section v-if="jobs.length || error" class="preparation-progress" aria-label="当前分支任务进度" aria-live="polite">
    <p v-if="error" class="task-error">暂时无法读取任务状态：{{ error }}。任务可能仍在运行。<el-button link @click="emit('refresh')">重试读取</el-button></p>
    <div v-for="job in jobs" :key="job.id" class="task-row">
      <strong>{{ kinds[job.kind] }}</strong>
      <span>{{ states[job.status] }} · {{ stages[job.stage] ?? job.stage }}</span>
      <span v-if="job.status === 'QUEUED' || job.status === 'RUNNING'">完成后自动刷新，可留在本页等待</span>
      <span v-if="job.error" class="task-error">{{ job.error }}</span>
      <el-button v-if="job.status === 'FAILED' && canPrepare" link type="primary" :disabled="busy" @click="emit('retry', job.kind)">重试{{ kinds[job.kind] }}</el-button>
    </div>
    <div v-if="readyForSearch && jobs.some(job => job.status === 'SUCCEEDED') && !busy" class="task-row">
      <span>当前分支已可检索</span><el-button link type="primary" @click="emit('openSearch')">继续检索</el-button><el-button link type="primary" @click="emit('openAsk')">继续提问</el-button>
    </div>
  </section>
</template>

<style scoped>
.preparation-progress { margin-top:14px; padding:12px; border:1px solid var(--app-border); border-radius:6px; background:var(--app-surface,#fff); font-size:13px; }
.task-row { display:flex; align-items:center; flex-wrap:wrap; gap:8px 14px; padding:5px 0; }
.task-row>span { color:var(--app-text-muted); }.task-error,.task-row>.task-error { color:#a94430; overflow-wrap:anywhere; }.task-error { margin:0; }
</style>
