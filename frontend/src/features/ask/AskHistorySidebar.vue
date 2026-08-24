<script setup lang="ts">
import { Clock, Delete, EditPen, Refresh } from '@element-plus/icons-vue';
import type { QaHistoryRecord } from '@/api/intelligence';

defineProps<{ records: readonly QaHistoryRecord[]; activeThreadId: string | null; loading: boolean }>();
const emit = defineEmits<{ open: [record: QaHistoryRecord]; refresh: []; rename: [record: QaHistoryRecord]; delete: [record: QaHistoryRecord] }>();

function statusLabel(status: QaHistoryRecord['evidenceStatus']) {
  return ({ SUPPORTED: '引用已校验', DEGRADED: '本地证据', MODEL_OUTPUT_REJECTED: '已安全降级', INSUFFICIENT: '证据不足' })[status];
}
</script>

<template>
  <aside class="history-sidebar surface">
    <header class="history-head">
      <div><b>历史记录</b><small>{{ records.length }} 个多轮会话</small></div>
      <el-button link :icon="Refresh" :loading="loading" title="刷新" @click="emit('refresh')" />
    </header>
    <el-empty v-if="!loading && !records.length" :image-size="54" description="还没有历史记录" />
    <div v-loading="loading" class="history-list">
      <article v-for="record in records" :key="record.threadId" :class="{ active: record.threadId === activeThreadId }">
        <button class="history-main" type="button" @click="emit('open', record)">
          <b>{{ record.title }}</b><p>{{ record.question }}</p>
          <span><Clock />{{ new Date(record.updatedAt).toLocaleString() }}</span>
          <small>{{ statusLabel(record.evidenceStatus) }} · {{ record.turnCount }} 轮 · {{ record.citationCount }} 条证据</small>
        </button>
        <div class="history-actions">
          <el-button link :icon="EditPen" title="重命名" @click="emit('rename', record)" />
          <el-button link type="danger" :icon="Delete" title="删除" @click="emit('delete', record)" />
        </div>
      </article>
    </div>
  </aside>
</template>

<style scoped>
.history-sidebar { display:grid; grid-template-rows:auto minmax(0,1fr); min-width:0; min-height:0; }.history-head { display:flex; align-items:center; justify-content:space-between; min-height:54px; padding:10px 12px; border-bottom:1px solid #e7e8eb; }.history-head>div { display:grid; gap:3px; }.history-head b { font-size:13px; }.history-head small { color: var(--app-text-muted); font-size: 11px; }
.history-list { min-height:0; overflow:auto; }.history-list article { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:2px; padding:12px 8px 10px; border-bottom:1px solid #eceef0; box-shadow:inset 3px 0 transparent; }.history-list article:hover { background:#f8fafc; }.history-list article.active { background:#f2f7fc; box-shadow:inset 3px 0 #0066cc; }
.history-main { display:grid; min-width:0; gap:5px; padding:0 4px; text-align:left; border:0; background:transparent; }.history-main b,.history-main p { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.history-main b { color:#2f3338; font-size:11px; }.history-main p { margin:0; color:#656c73; font-size: 11px; }.history-main span { display:flex; align-items:center; gap:4px; color: var(--app-text-muted); font-size: 11px; }.history-main span svg { width:10px; }.history-main small { color: var(--app-text-muted); font-size: 11px; }
.history-actions { display:flex; align-items:start; opacity:0; transition:opacity .15s ease; }.history-list article:hover .history-actions,.history-list article.active .history-actions { opacity:1; }
@media (max-width:900px) { .history-sidebar { max-height:280px; }.history-actions { opacity:1; } }
</style>
