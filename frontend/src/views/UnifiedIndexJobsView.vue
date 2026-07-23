<script setup lang="ts">
import { onMounted, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import AppPagination from '@/components/AppPagination.vue';
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
  pageNum,
  pageSize,
  total,
  refresh,
  changePage,
  changePageSize,
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
  <section class="page index-jobs-design">
    <div class="summary-strip">
      <div><b>{{ counts.RUNNING }}</b><span>本页运行中</span></div>
      <div><b>{{ counts.QUEUED }}</b><span>本页排队中</span></div>
      <div><b>{{ counts.CANCEL_REQUESTED }}</b><span>本页取消中</span></div>
      <div><b>{{ counts.FAILED }}</b><span>本页失败</span></div>
    </div>

    <div class="index-jobs-content">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <div class="split detail-split index-jobs-split">
        <div class="surface index-jobs-list">
          <div class="toolbar">
            <strong>索引任务</strong>
            <span class="spacer" />
            <span class="muted">成功 {{ counts.SUCCEEDED }} · 已取消 {{ counts.CANCELED }}</span>
            <el-button :loading="loading" @click="refresh()">刷新</el-button>
          </div>
          <div class="index-jobs-table-region">
            <IndexJobTable
              :jobs="jobs"
              :repositories="repositoryStore.repositories"
              :selected-job-id="selectedJobId"
              :loading="loading"
              @select="selectJob"
            />
          </div>
          <AppPagination
            :page-num="pageNum"
            :page-size="pageSize"
            :total="total"
            :disabled="loading"
            @page-change="changePage"
            @size-change="changePageSize"
          />
        </div>
        <UnifiedIndexJobDetail
          class="index-jobs-detail"
          :job="selectedJob"
          :repositories="repositoryStore.repositories"
          :action-pending="actionPending"
          @cancel="cancelJob"
          @retry="retryJob"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.index-jobs-design {
  grid-template-rows: 80px minmax(0, 1fr);
  overflow: hidden;
}

.index-jobs-content {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
}

.index-jobs-split {
  min-height: 0;
  flex: 1;
}

.index-jobs-list {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-height: 0;
}

.index-jobs-table-region,
.index-jobs-detail {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

@media (max-width: 760px) {
  .index-jobs-design {
    grid-template-rows: auto auto;
    height: auto;
    overflow: visible;
  }

  .index-jobs-content {
    display: block;
  }

  .index-jobs-content > .el-alert {
    margin-bottom: 12px;
  }

  .index-jobs-list {
    display: block;
  }

  .index-jobs-table-region,
  .index-jobs-detail {
    overflow: visible;
  }
}
</style>
