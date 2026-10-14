<script setup lang="ts">
import type { TrackedImport } from './useRepositoryImportMonitor';
defineProps<{ tasks: TrackedImport[]; refreshing: boolean }>();
const emit = defineEmits<{ refresh: []; open: [repositoryId: string]; dismiss: [id: string] }>();
const labels = { QUEUED: '排队中', RUNNING: '接入中', SUCCEEDED: '接入完成', FAILED: '接入失败', CANCELED: '已取消' };
const steps: Record<string, string> = { queued: '等待处理', cloning: '获取并接入代码', QUEUED: '等待处理' };
</script>
<template>
  <section v-if="tasks.length" class="import-progress" aria-label="项目接入进度">
    <header><b>项目接入进度</b><el-button link :loading="refreshing" @click="emit('refresh')">刷新进度</el-button></header>
    <article v-for="item in tasks" :key="item.job.id">
      <strong>{{ item.name }}</strong><span>{{ labels[item.job.status] }}</span>
      <p v-if="item.job.status === 'RUNNING' || item.job.status === 'QUEUED'">{{ steps[item.job.currentStep] || '后台正在处理，可稍后查看进度' }}</p>
      <p v-if="item.error" role="status">进度读取暂不可用：{{ item.error }}；后台任务可能仍在运行。</p>
      <p v-if="item.job.errorMessage">{{ item.job.errorMessage }}</p>
      <el-button v-if="item.job.status === 'SUCCEEDED' && item.job.resultRepositoryId" link type="primary" @click="emit('open', item.job.resultRepositoryId)">打开项目</el-button>
      <el-button v-if="!['RUNNING', 'QUEUED'].includes(item.job.status)" link @click="emit('dismiss', item.job.id)">收起记录</el-button>
    </article>
  </section>
</template>
<style scoped>
.import-progress { margin:12px 0; padding:12px; border:1px solid var(--app-border); border-radius:8px; }
.import-progress header { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.import-progress article { padding:10px 0; border-top:1px solid var(--app-border); font-size:13px; overflow-wrap:anywhere; }
.import-progress strong { margin-right:10px; }.import-progress p { margin:6px 0; color:var(--app-text-muted); }
</style>
