<script setup lang="ts">
import { AlertTriangle, CheckCircle2, ChevronDown, History, RefreshCw } from 'lucide-vue-next';
import type { TaskReviewSummary } from '@/api/taskReviews';

defineProps<{ items: TaskReviewSummary[]; loading: boolean; selectedReviewId?: string | null }>();
const emit = defineEmits<{ open: [item: TaskReviewSummary] }>();
function shortDate(value: string | null) {
  if (!value) return '未完成';
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}
function sourceLabel(source: TaskReviewSummary['changeSource']) {
  return { WORKTREE: '工作区', SINGLE_COMMIT: '单次提交', COMMIT_RANGE: '提交范围' }[source];
}
</script>

<template>
  <details class="review-history">
    <summary>
      <span><History :size="15" /><strong>历史审查</strong><small>{{ items.length ? `${items.length} 条最近记录` : '暂无记录' }}</small></span>
      <RefreshCw v-if="loading" class="spinning" :size="14" /><ChevronDown v-else :size="15" />
    </summary>
    <div v-if="items.length" class="history-list">
      <button v-for="item in items" :key="item.reviewId" type="button" :class="{ selected: selectedReviewId === item.reviewId }" @click="emit('open', item)">
        <span><component :is="item.status === 'COMPLETED' ? CheckCircle2 : AlertTriangle" :size="13" />{{ item.status === 'COMPLETED' ? '已完成' : '失败' }}</span>
        <strong>{{ item.task || `${sourceLabel(item.changeSource)}审查` }}</strong>
        <small>{{ item.changedFileCount ?? '—' }} 文件 · {{ item.unknownCount ?? '—' }} 未知 · {{ shortDate(item.finishedAt) }}</small>
      </button>
    </div>
    <p v-else-if="!loading">完成一次审查后，这里会保留不可变的版本记录。</p>
  </details>
</template>

<style scoped>
.review-history { border: 1px solid #dbe2e6; border-radius: 8px; background: #fff; }
.review-history summary { display: flex; align-items: center; justify-content: space-between; min-height: 42px; padding: 0 12px; color: #52616b; cursor: pointer; list-style: none; }
.review-history summary::-webkit-details-marker { display: none; }
.review-history summary > span { display: flex; align-items: center; gap: 7px; }
.review-history summary strong { font-size: 13px; }
.review-history summary small { color: #89949b; font-size: 12px; }
.review-history summary > svg:last-child { transition: transform .18s ease; }
.review-history[open] summary > svg:last-child { transform: rotate(180deg); }
.review-history summary:focus-visible, .history-list button:focus-visible { outline: 3px solid var(--app-focus-ring); outline-offset: 2px; }
.review-history > p { margin: 0; padding: 13px; color: #77858e; border-top: 1px solid #e6ebee; font-size: 13px; }
.history-list { display: flex; gap: 7px; overflow-x: auto; padding: 8px; border-top: 1px solid #e6ebee; }
.history-list button { display: grid; flex: 0 0 220px; gap: 4px; padding: 9px 10px; color: #697780; text-align: left; border: 1px solid #e0e6e9; border-radius: 6px; background: #fff; }
.history-list button:hover, .history-list button.selected { border-color: #8fb5d5; background: #f5f9fc; }
.history-list button > span { display: flex; align-items: center; gap: 4px; color: #567182; font-size: 12px; }
.history-list strong { overflow: hidden; color: #33424c; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.history-list small { color: #7c8992; font-size: 12px; }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .review-history summary > svg:last-child { transition: none; } .spinning { animation: none; } }
</style>
