<script setup lang="ts">
import { onMounted, shallowRef } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import BranchTasksPanel from '@/features/indexing/BranchTasksPanel.vue';
import { ElMessage } from 'element-plus';
import AppPagination from '@/components/AppPagination.vue';
import CurrentVectorIndexPanel from '@/features/indexing/CurrentVectorIndexPanel.vue';
import IndexJobTable from '@/features/indexing/IndexJobTable.vue';
import UnifiedIndexJobDetail from '@/features/indexing/UnifiedIndexJobDetail.vue';
import { useIndexJobs } from '@/features/indexing/useIndexJobs';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositoryStore = useRepositoryStore();
const auth = useAuthStore();
const route = useRoute();
const section = shallowRef<'jobs' | 'vectors' | 'branches'>(auth.isAdmin && (route.query.section === 'jobs' || route.query.section === 'vectors') ? route.query.section : 'branches');
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
  await repositoryStore.loadRepositories();
  if (auth.isAdmin) { await refresh(); startPolling(); }
});
</script>

<template>
  <section class="page index-jobs-design">
    <div class="surface index-view-shell">
      <nav class="index-view-tabs" aria-label="任务页面">
        <button :class="{ active: section === 'branches' }" @click="section = 'branches'">分支任务</button>
        <button v-if="auth.isAdmin" :class="{ active: section === 'jobs' }" @click="section = 'jobs'">其他任务</button>
        <button v-if="auth.isAdmin" :class="{ active: section === 'vectors' }" @click="section = 'vectors'">当前向量索引</button>
      </nav>

      <div v-if="section === 'jobs' && auth.isAdmin" class="index-view-body index-jobs-content">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <div class="split detail-split index-jobs-split">
        <div class="surface index-jobs-list">
          <div class="toolbar">
            <span class="index-guidance">单版本索引、部分图谱构建和知识失效检查任务；由项目管理或项目总览发起</span>
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
      <CurrentVectorIndexPanel v-else-if="section === 'vectors' && auth.isAdmin" class="index-view-body" />
      <BranchTasksPanel v-else class="index-view-body" :repositories="repositoryStore.repositories"
        :initial-repository-id="typeof route.query.repositoryId === 'string' ? route.query.repositoryId : repositoryStore.selectedRepositoryId ?? undefined"
        :initial-branch-id="typeof route.query.branchId === 'string' ? route.query.branchId : undefined" />
    </div>
  </section>
</template>

<style scoped>
.index-jobs-design {
  grid-template-rows: minmax(0, 1fr);
  overflow: hidden;
}

.index-view-shell {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
}

.index-view-tabs {
  display: flex;
  flex: none;
  min-height: 54px;
  gap: 4px;
  padding: 0 16px;
  border-bottom: 1px solid #ececef;
}

.index-view-tabs button {
  display: flex;
  align-items: center;
  align-self: stretch;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  color: #65656c;
  font-size: 15px;
}

.index-view-tabs button:hover {
  color: #1d1d1f;
}

.index-view-tabs button.active {
  border-color: var(--app-color-action);
  color: #005eb8;
  font-weight: 600;
}

.index-view-body {
  min-height: 0;
  flex: 1;
  padding: 12px;
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
.index-guidance { color: var(--app-text-muted); font-size: 13px; }

.index-jobs-table-region,
.index-jobs-detail {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

@media (max-width: 760px) {
  .index-jobs-design {
    display: block;
    height: auto;
    overflow: visible;
  }

  .index-view-shell {
    height: auto;
    overflow: visible;
  }

  .index-view-tabs {
    padding: 0 8px;
  }

  .index-view-body {
    padding: 10px;
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
