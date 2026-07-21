<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import IndexJobDetail from '@/features/indexing/IndexJobDetail.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { listIndexJobs } from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { IndexJob } from '@/types/api';

const repositoryStore = useRepositoryStore();
const jobs = ref<IndexJob[]>([]);
const selectedJobId = shallowRef<string | null>(null);
const loading = shallowRef(false);
let refreshTimer: number | undefined;

const selectedJob = computed(() => jobs.value.find((job) => job.id === selectedJobId.value) ?? jobs.value[0] ?? null);
const counts = computed(() => ({
  running: jobs.value.filter((job) => job.status === 'RUNNING').length,
  queued: jobs.value.filter((job) => job.status === 'QUEUED').length,
  succeeded: jobs.value.filter((job) => job.status === 'SUCCEEDED').length,
  failed: jobs.value.filter((job) => job.status === 'FAILED').length,
}));

async function loadJobs(silent = false) {
  if (!silent) loading.value = true;
  try {
    jobs.value = await listIndexJobs();
    if (!selectedJobId.value && jobs.value.length) selectedJobId.value = jobs.value[0].id;
  } catch (error) {
    if (!silent) ElMessage.error(error instanceof Error ? error.message : '加载任务失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void repositoryStore.loadRepositories();
  void loadJobs();
  refreshTimer = window.setInterval(() => void loadJobs(true), 2000);
});
onBeforeUnmount(() => window.clearInterval(refreshTimer));
</script>

<template>
  <section class="page">
    <div class="summary-strip">
      <div><b>{{ counts.running }}</b><span>运行中</span></div>
      <div><b>{{ counts.queued }}</b><span>排队中</span></div>
      <div><b>{{ counts.succeeded }}</b><span>已成功</span></div>
      <div><b>{{ counts.failed }}</b><span>失败</span></div>
    </div>
    <div class="split detail-split">
      <div class="surface">
        <div class="toolbar"><strong>真实任务记录</strong><span class="spacer" /><el-button :loading="loading" @click="loadJobs()">刷新</el-button></div>
        <el-table v-loading="loading" :data="jobs" highlight-current-row empty-text="尚无索引任务" @row-click="(row: IndexJob) => selectedJobId = row.id">
          <el-table-column label="仓库" min-width="180">
            <template #default="{ row }">{{ repositoryStore.repositories.find((item) => item.id === row.repositoryId)?.name ?? row.repositoryId }}</template>
          </el-table-column>
          <el-table-column label="类型" width="130"><template #default="{ row }">{{ row.type === 'FULL' ? '全量内容索引' : '增量索引' }}</template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column>
          <el-table-column prop="currentStep" label="当前阶段" min-width="180" />
          <el-table-column label="创建时间" width="190"><template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template></el-table-column>
        </el-table>
      </div>
      <IndexJobDetail :job="selectedJob" :repositories="repositoryStore.repositories" />
    </div>
  </section>
</template>
