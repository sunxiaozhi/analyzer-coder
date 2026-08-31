<script setup lang="ts">
import type { Provenance } from '@/types/evidence';
import EvidenceLink from './EvidenceLink.vue';
import TruthSourceBadge from './TruthSourceBadge.vue';

defineProps<{ sources: Provenance[] }>();
const emit = defineEmits<{ open: [source: Provenance] }>();
</script>

<template>
  <section v-if="sources.length" class="provenance-summary" aria-label="真实性来源">
    <h3>真实性来源</h3>
    <article v-for="source in sources" :key="source.id">
      <TruthSourceBadge :source="source.sourceType" />
      <div>
        <EvidenceLink :source="source" @open="emit('open', $event)" />
        <p>{{ source.detail }}</p>
        <small v-if="source.engineeringProjectId">
          工程项目 {{ source.engineeringProjectId.slice(0, 8) }}
          <template v-if="source.serviceName"> · {{ source.serviceName }}</template>
          <template v-if="source.contractId"> · 契约 {{ source.contractId.slice(0, 8) }}</template>
        </small>
        <small v-else-if="source.retrievalChannel">检索通道：{{ source.retrievalChannel }}（仅用于排序候选）</small>
        <small v-else-if="source.relationPath.length">关系路径：{{ source.relationPath.join(' → ') }}</small>
        <small v-else-if="source.snapshotId">Snapshot {{ source.snapshotId.slice(0, 8) }}</small>
      </div>
    </article>
  </section>
</template>

<style scoped>
.provenance-summary { display: grid; gap: 7px; }
.provenance-summary h3 { margin: 0 0 2px; color: #33424c; font-size: 11px; }
.provenance-summary article { display: grid; grid-template-columns: max-content minmax(0, 1fr); align-items: start; gap: 8px; padding: 8px; border: 1px solid #e0e6ea; border-radius: 7px; background: #fafcfd; }
.provenance-summary article > div { display: grid; min-width: 0; gap: 3px; }
.provenance-summary p { margin: 0; color: #5d6972; font-size: 10px; line-height: 1.5; }
.provenance-summary small { color: #7b8790; font: 9px "SFMono-Regular", Consolas, monospace; }
</style>
