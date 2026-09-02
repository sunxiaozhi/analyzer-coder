<script setup lang="ts">
import { CheckCircle2, Database, FileCode2, TriangleAlert } from 'lucide-vue-next';
import type { VectorIndexSummary } from '@/api/vectorIndex';

defineProps<{
  summary: VectorIndexSummary | null;
}>();

function shortHash(value: string | null) {
  return value?.slice(0, 10) ?? '尚无快照';
}
</script>

<template>
  <section v-if="summary" class="vector-summary">
    <article>
      <span class="summary-icon"><FileCode2 :size="17" /></span>
      <div><strong>{{ summary.totalChunks }}</strong><small>当前代码分块</small></div>
    </article>
    <article>
      <span class="summary-icon success"><CheckCircle2 :size="17" /></span>
      <div><strong>{{ summary.vectorizedChunks }}</strong><small>已生成向量</small></div>
    </article>
    <article :class="{ warning: summary.missingChunks > 0 }">
      <span class="summary-icon warning"><TriangleAlert :size="17" /></span>
      <div><strong>{{ summary.missingChunks }}</strong><small>缺失向量</small></div>
    </article>
    <article>
      <span class="summary-icon"><Database :size="17" /></span>
      <div>
        <strong>{{ summary.capabilityLabel }}</strong>
        <small class="model-name">{{ summary.vectorModel ?? '未配置' }}{{ summary.dimension ? ` · ${summary.dimension} 维` : '' }}</small>
      </div>
    </article>
    <footer>
      <span>当前快照 <code>{{ shortHash(summary.snapshotId) }}</code></span>
      <span>Commit <code>{{ shortHash(summary.commitSha) }}</code></span>
      <span>知识卡片 {{ summary.vectorizedKnowledgeCards }}/{{ summary.knowledgeCards }}</span>
      <span v-if="summary.retrievalCapability === 'CHARACTER_HASH'">字符哈希不理解同义词或代码语义</span>
      <span>更新于 {{ summary.updatedAt ? new Date(summary.updatedAt).toLocaleString() : '尚未生成' }}</span>
    </footer>
  </section>
</template>

<style scoped>
.vector-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  background: #fff;
  border: 1px solid #d6dbe2;
  border-radius: 7px;
  box-shadow: 0 2px 8px rgb(24 39 58 / 6%);
}

.vector-summary article {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
  padding: 15px 16px;
  border-right: 1px solid #eeeeef;
}

.vector-summary article:nth-child(4) {
  border-right: 0;
}

.summary-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 6px;
  background: #edf5fd;
  color: var(--app-color-action);
}

.summary-icon.success {
  background: var(--app-color-success-soft);
  color: var(--app-color-success);
}

.summary-icon.warning {
  background: #fff5df;
  color: #996511;
}

.vector-summary article > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.vector-summary strong {
  overflow: hidden;
  color: #1d1d1f;
  font-size: 20px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vector-summary .model-name {
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 15px;
}

.vector-summary small,
.vector-summary footer {
  color: var(--app-text-muted);
  font-size: 13px;
}

.vector-summary footer {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 9px 16px;
  background: #fafafa;
  border-top: 1px solid #eeeeef;
}

.vector-summary code {
  color: #4a4a4f;
}

@media (max-width: 900px) {
  .vector-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .vector-summary article:nth-child(2) {
    border-right: 0;
  }
  .vector-summary article:nth-child(-n + 2) {
    border-bottom: 1px solid #eeeeef;
  }
}

@media (max-width: 560px) {
  .vector-summary {
    grid-template-columns: 1fr;
  }
  .vector-summary article {
    border-right: 0;
    border-bottom: 1px solid #eeeeef;
  }
}
</style>
