<script setup lang="ts">
import { BookOpen, CornerDownRight, Search } from 'lucide-vue-next';
import type { KnowledgeMatch, KnowledgeReferenceCandidate } from '@/api/taskReviews';
import {
  enforcementLabel,
  knowledgeKindLabel,
  retrievalSourceLabel,
  statusLabel,
} from '@/utils/displayLabels';

defineProps<{
  matches?: KnowledgeMatch[];
  references?: KnowledgeReferenceCandidate[];
  stale?: boolean;
}>();
const emit = defineEmits<{
  selectMatch: [match: KnowledgeMatch];
  selectReference: [candidate: KnowledgeReferenceCandidate];
}>();

</script>

<template>
  <div class="knowledge-findings">
    <button
      v-for="item in matches ?? []"
      :key="item.knowledgeId"
      type="button"
      class="knowledge-row"
      :data-stale="stale"
      @click="emit('selectMatch', item)"
    >
      <BookOpen :size="15" />
      <span>
        <small>{{ knowledgeKindLabel(item.kind) }} · {{ enforcementLabel(item.enforcement) }}</small>
        <strong>{{ item.title }}</strong>
        <em>{{ item.reasons.length }} 条确定性命中原因 · 修订 v{{ item.revision }}</em>
      </span>
      <b>{{ stale ? statusLabel(item.sourceVersionStatus) : '已验证' }}</b>
      <CornerDownRight :size="13" />
    </button>

    <div v-if="references?.length" class="reference-divider">
      <span>仅供参考的检索候选</span>
      <small>不产生测试或审批要求</small>
    </div>
    <button
      v-for="item in references ?? []"
      :key="item.knowledgeId"
      type="button"
      class="knowledge-row reference"
      @click="emit('selectReference', item)"
    >
      <Search :size="15" />
      <span>
        <small>{{ knowledgeKindLabel(item.kind) }} · {{ retrievalSourceLabel(item.retrievalSource) }}</small>
        <strong>{{ item.title }}</strong>
        <em>{{ item.detail }}</em>
      </span>
      <b>参考</b>
      <CornerDownRight :size="13" />
    </button>

    <p v-if="!(matches?.length || references?.length)" class="empty-copy">
      {{ stale ? '没有知识因本次改动进入待重新验证状态。' : '本次改动没有命中已发布且已审核的工程知识。' }}
    </p>
  </div>
</template>

<style scoped>
.knowledge-findings { display: grid; gap: 7px; }
.knowledge-row { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto 16px; align-items: center; gap: 8px; width: 100%; padding: 10px 11px; color: #52616c; text-align: left; border: 1px solid #dce5e2; border-radius: 7px; background: #fbfdfc; }
.knowledge-row:hover { border-color: #7eb19e; background: #f4faf7; }
.knowledge-row:focus-visible { outline: 3px solid rgb(33 138 96 / 18%); outline-offset: 2px; }
.knowledge-row > span { display: grid; min-width: 0; gap: 3px; }
.knowledge-row small { color: #728078; font-size: 13px; }
.knowledge-row strong { overflow: hidden; color: #26352f; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-row em { overflow: hidden; color: #76837c; font-size: 13px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-row > b { padding: 3px 6px; color: var(--app-color-success); border-radius: 4px; background: #e8f4ef; font-size: 13px; white-space: nowrap; }
.knowledge-row[data-stale="true"] { border-color: #ead9c4; background: var(--app-color-warning-soft); }
.knowledge-row[data-stale="true"] > b { color: #9b5e1c; background: #f9ead7; }
.knowledge-row.reference { color: #667681; border-color: #e0e5e8; background: #fafbfc; }
.knowledge-row.reference > b { color: #667681; background: #edf1f3; }
.reference-divider { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 4px; padding: 8px 2px 2px; color: #667681; border-top: 1px dashed #d8e0e4; font-size: 13px; }
.reference-divider span { font-weight: 700; }
.empty-copy { margin: 0; padding: 12px; color: #7a8790; border: 1px dashed #dbe2e6; border-radius: 7px; font-size: 13px; }
</style>
