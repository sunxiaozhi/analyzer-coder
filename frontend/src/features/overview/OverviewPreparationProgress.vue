<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import type { BranchPreparationJob } from '@/api/branches';
import { taskStageLabel } from '@/features/indexing/taskProgress';

const props = defineProps<{ jobs: readonly BranchPreparationJob[]; error: string; busy: boolean; canPrepare: boolean; refreshPending?: boolean; contentVersion?: string | null }>();
const emit = defineEmits<{ refresh: []; retry: [kind: BranchPreparationJob['kind']]; }>();
const observed = shallowRef(new Set<string>());
const active = (job: BranchPreparationJob) => job.status === 'QUEUED' || job.status === 'RUNNING';
watch(() => props.jobs, rows => {
  observed.value = new Set(rows.filter(job => active(job) || observed.value.has(job.id)).map(job => job.id));
}, { immediate: true });
const visibleJobs = computed(() => props.jobs.filter(job => active(job) || job.status === 'FAILED' && observed.value.has(job.id)
  && (!job.contentVersion || !props.contentVersion || job.contentVersion === props.contentVersion)));
const kinds: Record<string, string> = { SYNC: '同步代码', CONTENT: '内容索引', GRAPH: '代码图谱', PREPARE: '一键准备', VECTORS: '向量索引' };
const states: Record<string, string> = { QUEUED: '排队中', RUNNING: '执行中', FAILED: '本次任务失败' };
function errorMessage(job: BranchPreparationJob) {
  const suffix = `；任务编号：${job.id}`;
  return job.error?.endsWith(suffix) ? job.error.slice(0, -suffix.length) : job.error;
}
</script>

<template>
  <section v-if="visibleJobs.length || error || refreshPending" class="preparation-progress" aria-label="当前分支任务进度" aria-live="polite">
    <p v-if="error" class="task-error">{{ refreshPending ? '任务已完成，页面状态刷新未完成：' : '暂时无法读取任务状态：' }}{{ error }}{{ refreshPending ? '' : '。任务可能仍在运行。' }}<el-button v-if="!refreshPending" link @click="emit('refresh')">重试读取</el-button></p>
    <p v-if="refreshPending" class="refresh-pending">任务已完成，页面状态待刷新。有未保存内容或问答执行中时会延后刷新。<el-button link type="primary" @click="emit('refresh')">重试刷新页面状态</el-button></p>
    <div v-for="job in visibleJobs" :key="job.id" class="task-row">
      <strong>{{ kinds[job.kind] }}</strong>
      <span>{{ states[job.status] }} · {{ taskStageLabel(job.stage, job.status) }}</span>
      <small v-if="active(job)">完成后自动更新当前分支状态</small>
      <span v-if="job.error" class="task-error">{{ errorMessage(job) }}</span>
      <el-button v-if="job.status === 'FAILED' && canPrepare" link type="primary" :disabled="busy" @click="emit('retry', job.kind)">重试{{ kinds[job.kind] }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.preparation-progress { margin: 0 0 16px; font-size: 13px; }
.task-row { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 10px; padding: 8px 0; }
.task-row > span, .task-row > small, .refresh-pending { color: var(--app-text-muted); }
.task-row > small { flex-basis: 100%; font-size: 12px; }
.task-error, .task-row > .task-error { color: var(--app-color-danger); overflow-wrap: anywhere; }
.task-error, .refresh-pending { margin: 0 0 8px; line-height: 1.7; }
</style>
