<script setup lang="ts">
import { computed } from 'vue';
import type { CodeReference, KnowledgeCard } from '@/api/intelligence';
import { statusLabel as localizeStatus } from '@/utils/displayLabels';
import KnowledgeAttachmentList from './KnowledgeAttachmentList.vue';

const props = defineProps<{
  card: KnowledgeCard | null;
}>();
const emit = defineEmits<{
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
}>();

const visible = defineModel<boolean>({ required: true });

const statusLabel = computed(() => props.card ? localizeStatus(props.card.status) : '');
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
      <section v-if="card?.codeReferences.length" class="detail-code-links">
        <h3>关联代码</h3>
        <article v-for="reference in card?.codeReferences ?? []" :key="reference.chunkId ?? reference.filePath">
          <div>
            <b>{{ reference.symbolName || reference.filePath.split('/').pop() }}</b>
            <span class="mono">{{ reference.filePath }} · L{{ reference.startLine ?? '?' }}–{{ reference.endLine ?? '?' }}</span>
          </div>
          <el-tag v-if="reference.stale" type="warning" size="small">代码已变化</el-tag>
          <el-button link type="primary" @click="emit('openCode', reference)">查看源码</el-button>
          <el-button link @click="emit('openGraph', reference)">调用图谱</el-button>
        </article>
      </section>
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
.detail-code-links {
  display: grid;
  gap: 8px;
  margin: 20px 0 12px;
  padding-top: 14px;
  border-top: 1px solid #eceef1;
}

.detail-code-links h3 { margin: 0 0 2px; font-size: 13px; }

.detail-code-links article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto auto;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #d9e5f1;
  border-radius: 6px;
  background: #f6faff;
}

.detail-code-links article > div { display: grid; min-width: 0; }
.detail-code-links b,
.detail-code-links span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.detail-code-links span { color: #71717a; font-size: 11px; }

@media (max-width: 760px) {
  .detail-code-links article { grid-template-columns: minmax(0, 1fr) auto; }
}
</style>
