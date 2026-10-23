<script setup lang="ts">
import { computed, nextTick, shallowRef, useTemplateRef, watch } from 'vue';
import type { Citation, CodeReference } from '@/api/intelligence';
import EvidenceGroupCard from './EvidenceGroupCard.vue';
import CitationSnapshotDrawer from './CitationSnapshotDrawer.vue';
import { groupEvidence, type CitationLocation } from './evidenceGroups';

const props = defineProps<{ citations: Citation[]; relatedEvidence?: Citation[]; branchId?: string | null; branchName?: string | null; knowledgeOnly?: boolean; location?: CitationLocation | null }>();
const emit = defineEmits<{ openKnowledge: [citation: Citation]; openCode: [reference: CodeReference]; openGraph: [reference: CodeReference] }>();
const groups = computed(() => groupEvidence(props.citations, props.relatedEvidence, props.branchId));
const citedGroups = computed(() => groups.value.filter(group => group.cited));
const relatedGroups = computed(() => groups.value.filter(group => !group.cited));
const showAll = shallowRef(false);
const showRelated = shallowRef(false);
const showAllRelated = shallowRef(false);
const visibleGroups = computed(() => showAll.value ? citedGroups.value : citedGroups.value.slice(0, 3));
const visibleRelated = computed(() => showAllRelated.value ? relatedGroups.value : relatedGroups.value.slice(0, 3));
const titleElement = useTemplateRef<HTMLElement>('titleElement');
const relatedTitle = useTemplateRef<HTMLElement>('relatedTitle');
const snapshot = shallowRef<Citation | null>(null);
const reading = shallowRef(false);
watch(() => props.location, location => {
  if (!location) return;
  const index = citedGroups.value.findIndex(group => group.members.some(member => member.ranks.includes(location.rank)));
  if (index >= 3) showAll.value = true;
  if (index < 0 && relatedGroups.value.some(group => group.members.some(member => member.ranks.includes(location.rank)))) {
    showRelated.value = true; showAllRelated.value = true;
  }
}, { immediate: true });
async function collapseAll() {
  showAll.value = false;
  await nextTick();
  titleElement.value?.scrollIntoView({ block: 'nearest', behavior: 'auto' });
  titleElement.value?.focus({ preventScroll: true });
}
async function collapseRelated() {
  showRelated.value = false; showAllRelated.value = false;
  await nextTick();
  relatedTitle.value?.scrollIntoView({ block: 'nearest', behavior: 'auto' });
  relatedTitle.value?.focus({ preventScroll: true });
}
function readFull(citation: Citation) { snapshot.value = citation; reading.value = true; }
</script>

<template>
  <section class="answer-evidence" aria-label="引用原文">
    <div v-if="citedGroups.length" class="evidence-heading">
      <b ref="titleElement" tabindex="-1">引用原文 · {{ citedGroups.length }} 组</b>
      <button v-if="citedGroups.length > 3" class="list-toggle" type="button" :aria-expanded="showAll" @click="showAll ? collapseAll() : showAll = true">{{ showAll ? '收起引用列表' : `查看全部引用（${citedGroups.length}）` }}</button>
    </div>
    <EvidenceGroupCard v-for="group in visibleGroups" :key="group.key" :group="group" :branch-id="branchId" :branch-name="branchName" :location="location"
      @read-full="readFull" @open-knowledge="emit('openKnowledge', $event)" @open-code="emit('openCode', $event)" @open-graph="emit('openGraph', $event)" />
    <button v-if="showAll && citedGroups.length > 3" class="list-toggle list-bottom" type="button" @click="collapseAll">收起引用列表</button>
    <section v-if="relatedGroups.length" class="related-evidence">
      <button ref="relatedTitle" class="list-toggle" type="button" :aria-expanded="showRelated" @click="showRelated ? collapseRelated() : showRelated = true">{{ showRelated ? '收起相关知识' : `更多相关知识（${relatedGroups.length}）` }} · 本轮回答未引用</button>
      <template v-if="showRelated">
        <EvidenceGroupCard v-for="group in visibleRelated" :key="group.key" :group="group" :branch-id="branchId" :branch-name="branchName" :location="location"
          @read-full="readFull" @open-knowledge="emit('openKnowledge', $event)" @open-code="emit('openCode', $event)" @open-graph="emit('openGraph', $event)" />
        <button v-if="!showAllRelated && relatedGroups.length > 3" class="list-toggle" type="button" @click="showAllRelated = true">查看全部相关知识（{{ relatedGroups.length }}）</button>
        <button class="list-toggle list-bottom" type="button" @click="collapseRelated">收起相关知识</button>
      </template>
    </section>
    <CitationSnapshotDrawer v-model="reading" :citation="snapshot" />
  </section>
</template>

<style scoped>
.answer-evidence, .related-evidence { display:grid; gap:10px; min-width:0; }
.evidence-heading { display:flex; justify-content:space-between; align-items:baseline; flex-wrap:wrap; gap:8px; }
.evidence-heading b { color:var(--app-text-muted, #66717c); font-size:13px; font-weight:600; }
.list-toggle { padding:4px 0; border:0; background:transparent; color:var(--app-color-action, #005eb8); font-size:13px; text-align:left; cursor:pointer; }
.list-bottom { justify-self:end; }.related-evidence { border-top:1px solid var(--app-border, #dfe6ec); padding-top:8px; }
.list-toggle:focus-visible, .evidence-heading b:focus { outline:2px solid var(--app-color-action, #005eb8); outline-offset:3px; }
</style>
