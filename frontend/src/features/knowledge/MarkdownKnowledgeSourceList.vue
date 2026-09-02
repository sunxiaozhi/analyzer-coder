<script setup lang="ts">
import type { TagProps } from 'element-plus';
import { FileText } from 'lucide-vue-next';
import type {
  MarkdownKnowledgeSource,
  MarkdownKnowledgeSourceCounts,
  MarkdownKnowledgeSourceStatus,
} from '@/api/intelligence';

defineProps<{
  items: MarkdownKnowledgeSource[];
  counts: MarkdownKnowledgeSourceCounts;
  snapshotId: string | null;
  busyPath: string | null;
  bulkBusy: boolean;
  canGenerate: boolean;
  emptyDescription: string;
}>();

const emit = defineEmits<{
  generate: [source: MarkdownKnowledgeSource];
  viewCard: [source: MarkdownKnowledgeSource];
  viewMarkdown: [source: MarkdownKnowledgeSource];
}>();

const statusCopy: Record<MarkdownKnowledgeSourceStatus, { label: string; hint: string }> = {
  PENDING: { label: '待生成', hint: '尚未沉淀为知识卡片' },
  CURRENT: { label: '已生成', hint: '卡片与当前文件一致' },
  STALE: { label: '已过期', hint: '源文件已变化，等待同步' },
};

function statusType(status: MarkdownKnowledgeSourceStatus): TagProps['type'] {
  if (status === 'CURRENT') return 'success';
  if (status === 'STALE') return 'warning';
  return 'info';
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
</script>

<template>
  <section class="markdown-source-ledger" aria-label="Markdown 预备知识">
    <header class="source-ledger-head">
      <div class="source-ledger-copy">
        <span class="source-eyebrow">自动提取</span>
        <h2>当前快照中的 Markdown</h2>
        <p>仓库扫描时自动发现；生成后先进入草稿，由团队确认后再发布。</p>
      </div>

      <dl class="source-counts" aria-label="Markdown 处理状态统计">
        <div>
          <dt>全部</dt>
          <dd>{{ counts.total }}</dd>
        </div>
        <div>
          <dt>待生成</dt>
          <dd>{{ counts.pending }}</dd>
        </div>
        <div>
          <dt>已生成</dt>
          <dd>{{ counts.current }}</dd>
        </div>
        <div :class="{ attention: counts.stale > 0 }">
          <dt>已过期</dt>
          <dd>{{ counts.stale }}</dd>
        </div>
      </dl>
    </header>

    <div v-if="snapshotId" class="snapshot-note">
      判断基准：当前快照 <span class="mono">{{ snapshotId.slice(0, 8) }}</span>
    </div>

    <div v-if="items.length" class="source-rows">
      <article
        v-for="source in items"
        :key="source.sourceId"
        class="source-row"
        :class="`is-${source.status.toLowerCase()}`"
      >
        <div class="source-identity">
          <span class="source-icon" aria-hidden="true"><FileText :size="18" /></span>
          <div>
            <h3>{{ source.title || source.sourcePath.split('/').pop() }}</h3>
            <p class="source-path mono" :title="source.sourcePath">{{ source.sourcePath }}</p>
            <p v-if="source.excerpt" class="source-excerpt">{{ source.excerpt }}</p>
            <small>{{ source.lineCount }} 行 · {{ formatBytes(source.byteSize) }}</small>
          </div>
        </div>

        <div class="source-status">
          <el-tag :type="statusType(source.status)" effect="plain" size="small">
            {{ statusCopy[source.status].label }}
          </el-tag>
          <span>{{ statusCopy[source.status].hint }}</span>
        </div>

        <div class="source-card-link">
          <template v-if="source.cardId">
            <span :title="source.cardTitle ?? '关联卡片'">{{ source.cardTitle ?? '关联卡片' }}</span>
            <b>修订 v{{ source.cardRevision ?? 1 }}</b>
          </template>
          <template v-else>
            <span>关联卡片</span>
            <b class="muted-link">尚未生成</b>
          </template>
        </div>

        <div class="source-actions">
          <el-button
            size="small"
            plain
            :disabled="bulkBusy"
            @click="emit('viewMarkdown', source)"
          >
            查看 Markdown
          </el-button>
          <el-button
            v-if="source.cardId"
            size="small"
            :type="source.status === 'CURRENT' ? 'primary' : 'default'"
            :plain="source.status === 'CURRENT'"
            :disabled="bulkBusy"
            @click="emit('viewCard', source)"
          >
            查看卡片
          </el-button>
          <el-button
            v-if="source.status !== 'CURRENT'"
            type="primary"
            size="small"
            :loading="busyPath === source.sourcePath"
            :disabled="!canGenerate || bulkBusy || (busyPath !== null && busyPath !== source.sourcePath)"
            @click="emit('generate', source)"
          >
            {{ source.status === 'STALE' ? '同步为新修订' : '生成知识卡片' }}
          </el-button>
        </div>
      </article>
    </div>

    <div v-else class="source-empty">
      <el-empty :description="emptyDescription" :image-size="82" />
    </div>
  </section>
</template>

<style scoped>
.markdown-source-ledger {
  display: grid;
  min-height: 100%;
  align-content: start;
  gap: 10px;
}

.source-ledger-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 16px 18px;
  border: 1px solid #d6dbe2;
  border-radius: 7px;
  background: #fff;
}

.source-ledger-copy { min-width: 0; }
.source-eyebrow {
  color: var(--app-color-action);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: .08em;
}
.source-ledger-copy h2 {
  margin: 3px 0 4px;
  color: #24292f;
  font-size: 15px;
  font-weight: 650;
}
.source-ledger-copy p {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 14px;
  line-height: 1.55;
}

.source-counts {
  display: grid;
  grid-template-columns: repeat(4, minmax(62px, auto));
  flex: none;
  margin: 0;
  overflow: hidden;
  border: 1px solid #e0e3e7;
  border-radius: 6px;
  background: #fafbfc;
}
.source-counts > div {
  display: grid;
  gap: 2px;
  padding: 8px 12px;
  border-right: 1px solid #e6e8eb;
}
.source-counts > div:last-child { border-right: 0; }
.source-counts dt { color: var(--app-text-muted); font-size: 13px; }
.source-counts dd { margin: 0; color: #30363d; font-size: 14px; font-weight: 650; }
.source-counts .attention dd { color: var(--app-color-warning); }

.snapshot-note {
  padding: 0 2px;
  color: var(--app-text-muted);
  font-size: 13px;
}
.snapshot-note span { color: #4f5b66; }

.source-rows {
  overflow: hidden;
  border: 1px solid #d6dbe2;
  border-radius: 7px;
  background: #fff;
}
.source-row {
  position: relative;
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 168px 100px minmax(310px, auto);
  align-items: center;
  gap: 16px;
  min-height: 92px;
  padding: 14px 14px 14px 17px;
  border-bottom: 1px solid #e7e9ec;
}
.source-row:last-child { border-bottom: 0; }
.source-row::before {
  position: absolute;
  inset: 14px auto 14px 0;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #a8b1ba;
  content: "";
}
.source-row.is-current::before { background: var(--app-color-success); }
.source-row.is-stale::before { background: #d97706; }
.source-row:hover { background: #fbfcfd; }

.source-identity {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
  min-width: 0;
}
.source-icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: var(--app-color-action);
  border: 1px solid #d5e4f2;
  border-radius: 6px;
  background: #f0f7fd;
}
.source-identity h3 {
  margin: 0 0 3px;
  overflow: hidden;
  color: #252b31;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-path {
  margin: 0;
  overflow: hidden;
  color: #63707c;
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-excerpt {
  display: -webkit-box;
  margin: 4px 0 2px;
  overflow: hidden;
  color: #596570;
  font-size: 14px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.source-identity small { color: var(--app-text-muted); font-size: 13px; }

.source-status,
.source-card-link {
  display: grid;
  justify-items: start;
  gap: 5px;
  min-width: 0;
}
.source-status span,
.source-card-link span {
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.4;
}
.source-card-link b { color: #44515d; font-size: 14px; font-weight: 600; }
.source-card-link .muted-link { color: var(--app-text-muted); font-weight: 500; }

.source-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}
.source-actions :deep(.el-button + .el-button) { margin-left: 0; }

.source-empty {
  display: grid;
  min-height: 260px;
  place-items: center;
  border: 1px dashed #d6dbe2;
  border-radius: 7px;
  background: #fff;
}

@media (max-width: 1180px) {
  .source-row {
    grid-template-columns: minmax(260px, 1fr) 150px minmax(280px, auto);
  }
  .source-card-link { display: none; }
}

@media (max-width: 900px) {
  .source-ledger-head { align-items: flex-start; flex-direction: column; gap: 12px; }
  .source-counts { width: 100%; grid-template-columns: repeat(4, 1fr); }
  .source-row { grid-template-columns: minmax(0, 1fr) auto; gap: 12px; }
  .source-actions { grid-column: 1 / -1; justify-content: flex-start; padding-left: 44px; }
}

@media (max-width: 620px) {
  .source-ledger-head { padding: 14px; }
  .source-counts > div { padding: 8px; }
  .source-row { display: grid; padding: 14px 12px 14px 15px; }
  .source-status { grid-column: 1 / -1; padding-left: 44px; }
  .source-actions { padding-left: 44px; flex-wrap: wrap; }
}
</style>
