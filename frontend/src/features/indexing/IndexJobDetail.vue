<script setup lang="ts">
import StatusBadge from '@/components/StatusBadge.vue';
import type { IndexJob, Repository } from '@/types/api';

const props = defineProps<{ job: IndexJob | null; repositories: Repository[] }>();
function repositoryName(repositoryId: string) {
  return props.repositories.find((item) => item.id === repositoryId)?.name ?? repositoryId;
}
</script>

<template>
  <aside class="surface detail-panel">
    <template v-if="job">
      <div class="section-head"><h2>任务详情</h2><StatusBadge :status="job.status" /></div>
      <dl class="meta-grid">
        <div><dt>仓库</dt><dd>{{ repositoryName(job.repositoryId) }}</dd></div>
        <div><dt>类型</dt><dd>{{ job.type === 'FULL' ? '全量内容索引' : '增量索引' }}</dd></div>
        <div><dt>阶段</dt><dd class="mono">{{ job.currentStep ?? '—' }}</dd></div>
        <div><dt>创建时间</dt><dd>{{ new Date(job.createdAt).toLocaleString() }}</dd></div>
      </dl>
      <el-alert v-if="job.errorMessage" :title="job.errorMessage" type="error" :closable="false" show-icon />
      <p v-else class="muted">任务完成后，新的 chunk 集合会一次性替换该仓库的旧集合。</p>
    </template>
    <el-empty v-else description="选择任务查看详情" />
  </aside>
</template>
