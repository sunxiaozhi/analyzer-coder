<script setup lang="ts">
import { computed, nextTick, shallowRef, useId, useTemplateRef, watch } from 'vue';
import type { Citation, CodeReference } from '@/api/intelligence';
import CitationExcerpt from './CitationExcerpt.vue';
import { isMarkdown, type CitationLocation, type EvidenceGroup } from './evidenceGroups';

const props = defineProps<{ group: EvidenceGroup; branchId?: string | null; branchName?: string | null; location?: CitationLocation | null }>();
const emit = defineEmits<{
  readFull: [citation: Citation]; openKnowledge: [citation: Citation];
  openCode: [reference: CodeReference]; openGraph: [reference: CodeReference];
}>();
const expanded = shallowRef(false);
const activeRank = shallowRef(props.group.members[0].citation.rank);
const titleElement = useTemplateRef<HTMLElement>('titleElement');
const contentId = useId();
const active = computed(() => props.group.members.find(member => member.ranks.includes(activeRank.value)) ?? props.group.members[0]);
const citation = computed(() => active.value.citation);
const sourceReference = computed<CodeReference | null>(() => citation.value.sourceType === 'CODE' && citation.value.chunkId ? {
  repositoryId: citation.value.repositoryId, chunkId: citation.value.chunkId, contentVersion: citation.value.contentVersion,
  branchId: props.branchId, branchName: props.branchName, filePath: citation.value.filePath, symbolName: citation.value.symbolName,
  startLine: citation.value.startLine, endLine: citation.value.endLine, contentHash: '', stale: false,
} : null);
watch(() => props.location, async location => {
  if (!location) return;
  const member = props.group.members.find(item => item.ranks.includes(location.rank));
  if (!member) return;
  activeRank.value = location.rank;
  expanded.value = true;
  await nextTick();
  titleElement.value?.scrollIntoView({ block: 'nearest', behavior: 'auto' });
  titleElement.value?.focus({ preventScroll: true });
}, { immediate: true });
async function collapse() {
  expanded.value = false;
  await nextTick();
  titleElement.value?.scrollIntoView({ block: 'nearest', behavior: 'auto' });
  titleElement.value?.focus({ preventScroll: true });
}
function variantLabel(item: Citation) {
  if (item.sourceType === 'KNOWLEDGE') return `知识内容 · ${item.title}${item.knowledgeRevision ? ` · 修订 ${item.knowledgeRevision}` : ''}${item.sourceScope ? ` · ${item.sourceScope}` : ''}`;
  return `${isMarkdown(item.filePath) ? '来源 Markdown' : '代码'} · L${item.startLine ?? '?'}–${item.endLine ?? '?'}`;
}
</script>

<template>
  <article class="evidence-card" :data-citation-ranks="group.members.flatMap(member => member.ranks).join(',')">
    <div class="card-heading">
      <h4 ref="titleElement" tabindex="-1">{{ group.members[0].citation.title }}</h4>
      <button class="text-action top-toggle" type="button" :aria-expanded="expanded" :aria-controls="contentId" @click="expanded ? collapse() : expanded = true">
        {{ expanded ? '收起' : '展开相关片段' }}
      </button>
    </div>
    <p class="source-line">{{ group.sourcePath ? `来源：${group.sourcePath}` : citation.sourceScope || (citation.sourceType === 'KNOWLEDGE' ? '团队知识' : citation.filePath) }}<span v-if="branchName"> · {{ branchName }}</span></p>
    <p v-if="group.otherVersions" class="difference">同一来源存在其他分支或版本，已分开保留</p>
    <p v-if="group.differentContent" class="difference">同源内容有差异或包含不同片段，可展开切换核对</p>
    <div :id="contentId" class="card-content">
      <div v-if="expanded && group.members.length > 1" class="source-switch" role="group" aria-label="切换证据内容">
        <button v-for="member in group.members" :key="member.citation.rank" type="button"
          :aria-pressed="member === active" @click="activeRank = member.citation.rank">
          {{ variantLabel(member.citation) }}<span v-if="!member.cited"> · 本轮未引用</span>
        </button>
      </div>
      <small v-if="!active.cited" class="uncited-label">相关内容 · 本轮回答未引用</small>
      <CitationExcerpt :citation="citation" :expanded="expanded" />
      <template v-if="expanded">
        <p v-if="group.missingMarkdown" class="snapshot-missing">本轮未保存来源 Markdown 的正文快照，无法查看其历史原文。</p>
        <details v-if="citation.sources?.length" class="source-records">
          <summary>查看来源与版本记录</summary>
          <p v-for="(source, index) in citation.sources" :key="index">{{ source.filePath }}
            <small v-if="source.branchId">来源分支：{{ source.branchId }}</small>
            <small v-if="source.contentVersion">内容版本：{{ source.contentVersion }}</small>
          </p>
        </details>
        <details v-if="citation.codeReferences?.length" class="linked-code">
          <summary>关联代码 · {{ citation.codeReferences.length }} 处</summary>
          <button v-for="reference in citation.codeReferences" :key="`${reference.chunkId}:${reference.filePath}:${reference.startLine}`" type="button"
            @click="emit('openCode', { ...reference, branchId: reference.branchId ?? branchId, branchName: reference.branchName ?? branchName })">
            {{ reference.filePath }} · L{{ reference.startLine ?? '?' }}–{{ reference.endLine ?? '?' }}<span v-if="reference.stale"> · 待复核</span>
          </button>
        </details>
      </template>
    </div>
    <div class="card-actions">
      <button class="text-action read-full" type="button" @click="emit('readFull', citation)">{{ citation.sourceType === 'KNOWLEDGE' ? '阅读全文' : '阅读已存原文' }}</button>
      <template v-if="expanded">
        <button v-if="citation.sourceType === 'KNOWLEDGE' && citation.knowledgeCardId" class="text-action secondary" type="button" @click="emit('openKnowledge', citation)">查看当前知识</button>
        <template v-if="sourceReference">
          <button class="text-action secondary" type="button" @click="emit('openCode', sourceReference)">查看源码</button>
          <button class="text-action secondary" type="button" @click="emit('openGraph', sourceReference)">调用图谱</button>
        </template>
        <button class="text-action bottom-collapse" type="button" @click="collapse">收起片段</button>
      </template>
    </div>
  </article>
</template>

<style scoped>
.evidence-card { min-width:0; padding:14px 16px; border:1px solid var(--app-border, #dfe6ec); border-radius:6px; background:var(--app-surface, #fff); }
.card-heading { display:flex; gap:12px; align-items:flex-start; justify-content:space-between; }
.card-heading h4 { margin:0; min-width:0; color:var(--app-text-primary, #28313a); font-size:14px; line-height:1.6; overflow-wrap:anywhere; }
.card-heading h4:focus { outline:2px solid var(--app-color-action, #005eb8); outline-offset:4px; border-radius:2px; }
.text-action { border:0; padding:2px 0; background:transparent; color:var(--app-color-action, #005eb8); cursor:pointer; font:inherit; font-size:13px; }
.text-action:focus-visible, .source-switch button:focus-visible { outline:2px solid var(--app-color-action, #005eb8); outline-offset:3px; }
.top-toggle { flex-shrink:0; }.secondary { color:var(--app-text-muted, #66717c); }
.source-line, .difference, .snapshot-missing, .source-records { margin:6px 0 10px; font-size:12px; line-height:1.6; color:var(--app-text-muted, #66717c); overflow-wrap:anywhere; }
.difference { color:#8a5b00; }.card-content { min-width:0; }
.source-switch { display:flex; flex-wrap:wrap; gap:6px; margin:12px 0; }
.source-switch button { border:1px solid var(--app-border, #dfe6ec); border-radius:4px; padding:6px 9px; background:#f7f9fb; color:#4f5e6c; font-size:12px; text-align:left; overflow-wrap:anywhere; cursor:pointer; }
.source-switch button[aria-pressed=true] { border-color:var(--app-color-action, #005eb8); color:var(--app-color-action, #005eb8); background:var(--app-selection-bg, #edf5fc); }
.source-records summary, .linked-code summary { cursor:pointer; }.source-records small { display:block; }
.linked-code { font-size:12px; color:var(--app-text-muted, #66717c); margin-top:12px; }
.linked-code button { display:block; width:100%; padding:8px; border:0; background:#f7f9fb; text-align:left; overflow-wrap:anywhere; cursor:pointer; color:var(--app-color-action, #005eb8); }
.card-actions { display:flex; gap:14px; flex-wrap:wrap; align-items:center; margin-top:12px; }
.bottom-collapse { margin-left:auto; }.uncited-label { display:block; margin-bottom:8px; color:#8a5b00; }
@media(max-width:600px) { .evidence-card { padding:12px; }.card-heading { flex-wrap:wrap; gap:5px; }.top-toggle { margin-left:auto; } }
</style>
