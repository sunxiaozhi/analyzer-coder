<script setup lang="ts">
import type { TruthSource } from '@/types/evidence';

defineProps<{ source: TruthSource }>();

const labels: Record<TruthSource, string> = {
  GIT_FACT: 'Git 事实',
  CODE_FACT: '代码事实',
  PLATFORM_FACT: '平台关系事实',
  VERIFIED_KNOWLEDGE: '已验证知识',
  GRAPH_INFERENCE: '图谱推断',
  RETRIEVAL_CANDIDATE: '检索候选',
  MODEL_SUGGESTION: '模型建议',
  UNKNOWN: '未知边界',
};
</script>

<template>
  <span class="truth-badge" :data-source="source">{{ labels[source] }}</span>
</template>

<style scoped>
.truth-badge { display: inline-flex; align-items: center; min-height: 20px; padding: 2px 7px; color: #4d5a64; border: 1px solid #d7dfe4; border-radius: 999px; background: #f6f8f9; font-size: 9px; font-weight: 700; white-space: nowrap; }
.truth-badge[data-source='GIT_FACT'], .truth-badge[data-source='CODE_FACT'] { color: #285f8d; border-color: #bfd4e5; background: #edf5fb; }
.truth-badge[data-source='PLATFORM_FACT'] { color: #315f68; border-color: #b9d5d9; background: #edf7f8; }
.truth-badge[data-source='VERIFIED_KNOWLEDGE'] { color: #27664d; border-color: #bad9cc; background: #edf7f2; }
.truth-badge[data-source='GRAPH_INFERENCE'] { color: #68518a; border-color: #d3c8e1; background: #f4f0f8; }
.truth-badge[data-source='RETRIEVAL_CANDIDATE'], .truth-badge[data-source='MODEL_SUGGESTION'] { color: #855b24; border-color: #e4d1b5; background: #faf4ea; }
.truth-badge[data-source='UNKNOWN'] { color: #66727a; }
</style>
