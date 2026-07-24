<script setup lang="ts">
import { computed } from 'vue';
import type { KnowledgeCard } from '@/api/intelligence';
import KnowledgeAttachmentList from './KnowledgeAttachmentList.vue';

const props = defineProps<{
  card: KnowledgeCard | null;
}>();

const visible = defineModel<boolean>({ required: true });

const statusLabel = computed(() => {
  const labels: Record<string, string> = {
    DRAFT: 'DRAFT（草稿）',
    PUBLISHED: 'PUBLISHED（已发布）',
    NEEDS_REVIEW: 'NEEDS_REVIEW（需要复核）',
  };
  return props.card ? (labels[props.card.status] ?? props.card.status) : '';
});
</script>

<template>
  <el-dialog v-model="visible" :title="card?.title ?? '知识卡片详情'" width="760">
    <template v-if="card">
      <div class="detail-meta">
        <el-tag effect="plain">{{ card.cardType }}</el-tag>
        <el-tag :type="card.status === 'PUBLISHED' ? 'success' : card.status === 'NEEDS_REVIEW' ? 'warning' : 'info'">
          {{ statusLabel }}
        </el-tag>
        <span>修订 v{{ card.revision }}</span>
        <time>{{ new Date(card.updatedAt).toLocaleString() }}</time>
      </div>
      <div class="detail-content" v-html="card.renderedContent" />
      <div v-if="card.tags.length" class="detail-tags">
        <span v-for="tag in card.tags" :key="tag"># {{ tag }}</span>
      </div>
      <KnowledgeAttachmentList :items="card.attachments" :repository-id="card.repositoryId" />
    </template>
  </el-dialog>
</template>

<style scoped>
.detail-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 20px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.detail-content {
  line-height: 1.7;
  color: var(--el-text-color-primary);
}

.detail-content :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}

.detail-content :deep(pre) {
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: #18212f;
  color: #e6edf3;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 20px 0 12px;
  color: var(--el-color-primary);
  font-size: 13px;
}
</style>
