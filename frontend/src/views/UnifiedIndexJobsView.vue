<script setup lang="ts">
import { onMounted, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import IndexJobTable from '@/features/indexing/IndexJobTable.vue';
import UnifiedIndexJobDetail from '@/features/indexing/UnifiedIndexJobDetail.vue';
import { useIndexJobs } from '@/features/indexing/useIndexJobs';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositoryStore = useRepositoryStore();
const actionPending = shallowRef(false);
const {
  jobs,
  selectedJobId,
  selectedJob,
  counts,
  loading,
  error,
  refresh,
  selectJob,
  cancel,
  retry,
  startPolling,
} = useIndexJobs();

async function cancelJob(taskId: string) {
  actionPending.value = true;
  try {
    await cancel(taskId);
    ElMessage.success('取消请求已提交');
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '取消任务失败');
  } finally {
    actionPending.value = false;
  }
}

async function retryJob(taskId: string) {
  actionPending.value = true;
  try {
    await retry(taskId);
    ElMessage.success('已创建新的重试任务');
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '重试任务失败');
  } finally {
    actionPending.value = false;
  }
}

onMounted(async () => {
  await Promise.all([repositoryStore.loadRepositories(), refresh()]);
  startPolling();
});
</script>

<template>
  <section class="page">
    <div class="summary-strip">
      <div><b>{{ counts.RUNNING }}</b><span>运行中</span></div>
      <div><b>{{ counts.QUEUED }}</b><span>排队中</span></div>
      <div><b>{{ counts.CANCEL_REQUESTED }}</b><span>取消中</span></div>
      <div><b>{{ counts.FAILED }}</b><span>失败</span></div>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <div class="split detail-split">
      <div class="surface">
        <div class="toolbar">
          <strong>索引任务</strong>
          <span class="spacer" />
          <span class="muted">成功 {{ counts.SUCCEEDED }} · 已取消 {{ counts.CANCELED }}</span>
          <el-button :loading="loading" @click="refresh()">刷新</el-button>
        </div>
        <IndexJobTable
          :jobs="jobs"
          :repositories="repositoryStore.repositories"
          :selected-job-id="selectedJobId"
          :loading="loading"
          @select="selectJob"
        />
      </div>
      <UnifiedIndexJobDetail
        :job="selectedJob"
        :repositories="repositoryStore.repositories"
        :action-pending="actionPending"
        @cancel="cancelJob"
        @retry="retryJob"
      />
    </div>
  </section>
</template>
