<script setup lang="ts">
import { Connection, Document, Reading, View } from '@element-plus/icons-vue';
import { nextTick, shallowRef, watch } from 'vue';
import type { Citation, CodeReference } from '@/api/intelligence';

const props = defineProps<{ citations: Citation[]; activeCitationId?: string | null }>();
const emit = defineEmits<{
  openKnowledge: [citation: Citation];
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
}>();
const panelElement = shallowRef<HTMLElement | null>(null);

watch(() => props.activeCitationId, async citationId => {
  if (!citationId) return;
  await nextTick();
  panelElement.value?.querySelector<HTMLElement>(`[data-citation-id="${citationId}"]`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
});

function citationReference(citation: Citation): CodeReference | null {
  if (citation.sourceType !== 'CODE' || !citation.chunkId) return null;
  return {
    repositoryId: citation.repositoryId,
    chunkId: citation.chunkId,
    snapshotId: citation.snapshotId,
    filePath: citation.filePath,
    symbolName: citation.symbolName,
    startLine: citation.startLine,
    endLine: citation.endLine,
    contentHash: '',
    stale: false,
  };
}
</script>

<template>
  <aside ref="panelElement" class="answer-evidence">
    <header>
      <div><b>证据轨道</b><span>代码与已发布知识</span></div>
      <em>{{ citations.length }}</em>
    </header>
    <el-empty v-if="!citations.length" :image-size="54" description="本次回答没有可引用证据" />
    <article
      v-for="citation in citations"
      :key="citation.id"
      :data-citation-id="citation.id"
      :class="['evidence-card', { active: citation.id === activeCitationId }]"
    >
      <div class="evidence-heading">
        <span :class="['source-badge', citation.sourceType.toLowerCase()]">
          <el-icon><Reading v-if="citation.sourceType === 'KNOWLEDGE'" /><Document v-else /></el-icon>
          {{ citation.sourceType === 'KNOWLEDGE' ? '知识卡片' : '代码证据' }}
        </span>
        <small>S{{ citation.rank }}</small>
      </div>
      <b class="evidence-title">{{ citation.title }}</b>
      <div v-if="citation.sourceType === 'CODE'" class="code-location">
        <span class="mono">{{ citation.filePath }}</span>
        <small>L{{ citation.startLine ?? '?' }}–{{ citation.endLine ?? '?' }}</small>
      </div>
      <p>{{ citation.content.slice(0, 360) }}</p>
      <div class="evidence-actions">
        <el-button
          v-if="citation.sourceType === 'KNOWLEDGE' && citation.knowledgeCardId"
          link type="primary" :icon="Reading" @click="emit('openKnowledge', citation)"
        >查看知识</el-button>
        <template v-else-if="citationReference(citation)">
          <el-button link type="primary" :icon="View" @click="emit('openCode', citationReference(citation)!)">查看源码</el-button>
          <el-button link :icon="Connection" @click="emit('openGraph', citationReference(citation)!)">调用图谱</el-button>
        </template>
      </div>
      <div v-if="citation.codeReferences.length" class="knowledge-code-links">
        <div><span>关联代码</span><small>{{ citation.codeReferences.length }} 处</small></div>
        <button
          v-for="reference in citation.codeReferences"
          :key="reference.chunkId ?? reference.filePath"
          @click="emit('openCode', reference)"
        >
          <span><b>{{ reference.symbolName || reference.filePath.split('/').pop() }}</b><small class="mono">{{ reference.filePath }}</small></span>
          <em v-if="reference.stale">已变化</em>
          <small>L{{ reference.startLine ?? '?' }}</small>
        </button>
      </div>
    </article>
  </aside>
</template>

<style scoped>
.answer-evidence { min-width:0; overflow:auto; border:1px solid #dedee3; border-left:0; border-radius:0 7px 7px 0; background:#fff; }
.answer-evidence>header { position:sticky; top:0; z-index:2; display:flex; min-height:54px; align-items:center; justify-content:space-between; padding:0 14px; border-bottom:1px solid #e6e7ea; background:#fff; }
.answer-evidence>header div { display:grid; gap:2px; }.answer-evidence>header b { font-size:13px; }.answer-evidence>header span { color:#80858c; font-size:9px; }.answer-evidence>header em { display:grid; place-items:center; width:25px; height:25px; color:#0066cc; border-radius:50%; background:#edf5fd; font-size:10px; font-style:normal; font-weight:700; }
.evidence-card { display:grid; gap:10px; padding:16px; border-bottom:1px solid #eceef0; box-shadow:inset 3px 0 transparent; transition:background-color .18s ease,box-shadow .18s ease; }.evidence-card.active { background:#f5f9fd; box-shadow:inset 3px 0 #0066cc; }
.evidence-heading,.evidence-actions { display:flex; align-items:center; }.evidence-heading { justify-content:space-between; }.evidence-heading>small { color:#858a91; font-size:9px; }
.source-badge { display:inline-flex; width:max-content; align-items:center; gap:4px; padding:3px 6px; border-radius:3px; font-size:9px; font-weight:700; }.source-badge.code { color:#005eb8; background:#eaf3fd; }.source-badge.knowledge { color:#795500; background:#fff3cf; }
.evidence-title { color:#2f3338; font-size:12px; line-height:1.45; overflow-wrap:anywhere; }.code-location { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:8px; padding:7px 9px; border-left:2px solid #8db8df; background:#f6f9fc; }.code-location span { overflow-wrap:anywhere; color:#556473; font-size:9px; }.code-location small { color:#0066cc; font-size:9px; white-space:nowrap; }
.evidence-card>p { max-height:88px; margin:0; padding-left:9px; overflow:hidden; color:#63686e; border-left:1px solid #e0e3e6; font-size:10px; line-height:1.6; white-space:pre-wrap; }.evidence-actions { gap:6px; }
.knowledge-code-links { display:grid; gap:6px; padding-top:10px; border-top:1px dashed #dce0e4; }.knowledge-code-links>div { display:flex; justify-content:space-between; color:#59616a; font-size:10px; }.knowledge-code-links>div small { color:#8a9096; }
.knowledge-code-links button { display:grid; grid-template-columns:minmax(0,1fr) auto auto; align-items:center; gap:7px; padding:8px 9px; text-align:left; border:1px solid #dbe6ef; border-radius:5px; background:#f9fbfd; }.knowledge-code-links button>span { display:grid; min-width:0; gap:2px; }.knowledge-code-links b,.knowledge-code-links button>span small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.knowledge-code-links b { font-size:10px; }.knowledge-code-links small { color:#78818a; font-size:8px; }.knowledge-code-links em { color:#9b5428; font-size:8px; font-style:normal; }
@media (max-width:900px) { .answer-evidence { border-left:1px solid #dedee3; border-radius:7px; } }
</style>
