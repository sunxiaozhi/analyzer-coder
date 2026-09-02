<script setup lang="ts">
import { AlertTriangle, CheckCircle2, ExternalLink, GitCompareArrows, RefreshCw } from 'lucide-vue-next';
import type { KnowledgeCard, KnowledgeDriftEvent } from '@/api/intelligence';

defineProps<{
  card: KnowledgeCard;
  event: KnowledgeDriftEvent | null;
  loading: boolean;
  canMaintain: boolean;
  reviewing: boolean;
}>();
const emit = defineEmits<{
  openDiff: [event: KnowledgeDriftEvent];
  review: [action: 'CONFIRM_CURRENT' | 'MARK_STALE'];
}>();

const reasonLabels: Record<string, string> = {
  CODE_REFERENCE_HASH_CHANGED: '绑定代码内容已变化',
  PATH_SCOPE_MATCHED: '路径范围命中',
  SYMBOL_SCOPE_MATCHED: '符号范围命中',
  MANUAL_CONFIRMATION: '人工确认当前',
  MANUAL_STALE_DECISION: '人工确认失效',
};
</script>

<template>
  <section class="drift-panel" :data-state="card.sourceVersionStatus">
    <header>
      <component :is="card.sourceVersionStatus === 'CURRENT' ? CheckCircle2 : AlertTriangle" :size="17" />
      <div>
        <small>Source drift</small>
        <h3>{{ card.sourceVersionStatus === 'SUSPECT' ? '代码变化命中知识范围，等待复核' : card.sourceVersionStatus === 'STALE' ? '知识已确认不适用于当前代码' : '知识来源版本已核对' }}</h3>
      </div>
      <RefreshCw v-if="loading" class="spinning" :size="14" />
    </header>

    <template v-if="event">
      <div class="drift-version">
        <span>v{{ event.cardRevision }}</span>
        <code>{{ event.fromCommit?.slice(0, 8) ?? '无基线' }}</code>
        <span>→</span>
        <code>{{ event.toCommit?.slice(0, 8) ?? '无提交' }}</code>
        <time>{{ new Date(event.createdAt).toLocaleString() }}</time>
      </div>
      <article v-for="reason in event.reasons" :key="`${reason.kind}:${reason.rule}:${reason.filePath}`">
        <b>{{ reasonLabels[reason.kind] ?? reason.kind }}</b>
        <span>{{ reason.detail }}</span>
        <code v-if="reason.filePath">{{ reason.filePath }}<template v-if="reason.startLine">:{{ reason.startLine }}</template></code>
        <small v-if="reason.rule">规则/哈希：{{ reason.rule }}</small>
      </article>
      <p v-if="event.note"><b>复核说明：</b>{{ event.note }}</p>
      <button
        v-if="event.fromCommit && event.toCommit && event.fromCommit !== event.toCommit"
        type="button"
        class="diff-link"
        @click="emit('openDiff', event)"
      >
        <GitCompareArrows :size="14" />打开触发本状态的提交范围审查<ExternalLink :size="12" />
      </button>
    </template>
    <p v-else-if="!loading">当前没有可展示的来源漂移审计。</p>

    <footer v-if="canMaintain && ['SUSPECT', 'STALE'].includes(card.sourceVersionStatus)">
      <el-button type="primary" plain :loading="reviewing" @click="emit('review', 'CONFIRM_CURRENT')">
        确认当前内容仍有效
      </el-button>
      <el-button v-if="card.sourceVersionStatus === 'SUSPECT'" type="warning" plain :disabled="reviewing" @click="emit('review', 'MARK_STALE')">
        确认知识已失效
      </el-button>
    </footer>
  </section>
</template>

<style scoped>
.drift-panel { display: grid; gap: 9px; margin: 17px 0 4px; padding: 13px; color: #775224; border: 1px solid #ead8c1; border-left: 4px solid var(--app-color-warning); border-radius: 7px; background: var(--app-color-warning-soft); }
.drift-panel[data-state="CURRENT"] { color: #286149; border-color: #cfe3da; border-left-color: var(--app-color-success); background: #f5faf7; }
.drift-panel > header { display: grid; grid-template-columns: 22px minmax(0, 1fr) auto; align-items: center; gap: 7px; }
.drift-panel > header div { display: grid; gap: 1px; }
.drift-panel > header small { font: 700 12px "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
.drift-panel h3 { margin: 0; color: #43382d; font-size: 14px; }
.drift-version { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 7px 8px; color: #6e665c; border: 1px solid #eadfce; border-radius: 5px; background: #fff; font-size: 12px; }
.drift-version code, .drift-panel article code { font-family: "SFMono-Regular", Consolas, monospace; }
.drift-version time { margin-left: auto; }
.drift-panel article { display: grid; grid-template-columns: minmax(130px, auto) minmax(0, 1fr); gap: 3px 9px; padding: 8px 9px; border-left: 2px solid #d6954d; background: #fff; }
.drift-panel article b { color: #6f471e; font-size: 13px; }
.drift-panel article span { color: #6d6255; font-size: 13px; }
.drift-panel article code, .drift-panel article small { grid-column: 1 / -1; overflow-wrap: anywhere; color: #7c6c59; font-size: 12px; }
.drift-panel > p { margin: 0; color: #756959; font-size: 13px; line-height: 1.5; }
.diff-link { display: inline-flex; justify-self: start; align-items: center; gap: 6px; min-height: 32px; padding: 0 9px; color: #365c73; border: 1px solid #bfd0db; border-radius: 5px; background: #f7fafc; font-size: 13px; font-weight: 650; }
.diff-link:hover { color: var(--app-color-action); border-color: #7ca6c7; }
.diff-link:focus-visible { outline: 3px solid var(--app-focus-ring); outline-offset: 2px; }
.drift-panel footer { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; padding-top: 3px; border-top: 1px solid #eadfce; }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 620px) {
  .drift-panel article { grid-template-columns: 1fr; }
  .drift-panel article code, .drift-panel article small { grid-column: 1; }
  .drift-version time { width: 100%; margin-left: 0; }
}
</style>
