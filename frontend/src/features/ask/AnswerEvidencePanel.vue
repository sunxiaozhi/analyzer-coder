<script setup lang="ts">
import { Connection, Document, Reading, View } from '@element-plus/icons-vue';
import type { Citation, CodeReference } from '@/api/intelligence';
import CitationExcerpt from './CitationExcerpt.vue';

const props = defineProps<{ citations: Citation[]; branchId?: string | null; branchName?: string | null; knowledgeOnly?: boolean }>();
const emit = defineEmits<{
  openKnowledge: [citation: Citation];
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
}>();

function citationReference(citation: Citation): CodeReference | null {
  if (citation.sourceType !== 'CODE' || !citation.chunkId) return null;
  return {
    branchId: props.branchId, branchName: props.branchName,
    repositoryId: citation.repositoryId,
    chunkId: citation.chunkId,
    contentVersion: citation.contentVersion,
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
  <section class="answer-evidence" aria-label="证据原文">
    <details
      v-for="citation in citations"
      :key="citation.id"
      class="evidence-card"
      :data-citation-rank="citation.rank"
      :open="citation.sourceType === 'KNOWLEDGE'"
      tabindex="-1"
    >
      <summary class="evidence-summary">
      <div class="evidence-heading">
        <span :class="['source-badge', citation.sourceType.toLowerCase()]">
          <el-icon><Reading v-if="citation.sourceType === 'KNOWLEDGE'" /><Document v-else /></el-icon>
          {{ citation.sourceType === 'KNOWLEDGE' ? (citation.sourceScope || '团队知识') : '代码' }}
        </span>
        <small><template v-if="!knowledgeOnly">{{ branchName || branchId || '来源分支未提供' }}</template></small>
      </div>
      <div class="evidence-title">
        <b>{{ citation.title }}</b>
        <span>{{ citation.sourceType === 'KNOWLEDGE' ? '知识原文' : '展开代码原文' }}</span>
      </div>
      </summary>
      <div class="evidence-body">
      <div v-if="citation.sourceType === 'CODE'" class="code-location">
        <span class="mono">{{ citation.filePath }}</span>
        <small>L{{ citation.startLine ?? '?' }}–{{ citation.endLine ?? '?' }}</small>
      </div>
      <CitationExcerpt :citation="citation" />
      <details v-if="citation.sources?.length" class="source-origins">
        <summary>来源记录 · {{ citation.sources.length }} 处</summary>
        <p v-for="(source, index) in citation.sources" :key="index">
          {{ source.filePath }}<template v-if="source.startLine"> · L{{ source.startLine }}–{{ source.endLine }}</template>
          <small v-if="source.branchId">来源分支：{{ source.branchId }}</small>
          <small v-if="source.contentVersion">内容版本：{{ source.contentVersion }}</small>
        </p>
      </details>

      <div class="evidence-actions">
        <template v-if="citation.sourceType === 'KNOWLEDGE' && citation.knowledgeCardId">
          <el-button link type="primary" :icon="Reading" @click="emit('openKnowledge', citation)">查看知识</el-button>
        </template>
        <template v-else-if="citationReference(citation)">
          <el-button link type="primary" :icon="View" @click="emit('openCode', citationReference(citation)!)">查看源码</el-button>
          <el-button link :icon="Connection" @click="emit('openGraph', citationReference(citation)!)">调用图谱</el-button>
        </template>
      </div>

      <details v-if="citation.codeReferences.length" class="knowledge-code-links">
        <summary>关联代码 · {{ citation.codeReferences.length }} 处</summary>
        <div class="linked-code-heading">
          <span>关联代码</span>
          <small>{{ citation.codeReferences.length }} 处</small>
        </div>
        <button
          v-for="reference in citation.codeReferences"
          :key="reference.chunkId ?? reference.filePath"
          type="button"
          @click="emit('openCode', { ...reference, branchId: reference.branchId ?? branchId, branchName: reference.branchName ?? branchName })"
        >
          <span class="code-reference-main">
            <b>{{ reference.symbolName || reference.filePath.split('/').pop() }}</b>
            <small>{{ reference.branchName || reference.branchId || branchName || '来源分支未提供' }}</small>
            <small class="mono">{{ reference.filePath }}</small>
          </span>
          <span class="code-reference-meta">
            <em v-if="reference.stale">待复核</em>
            <small>L{{ reference.startLine ?? '?' }}–{{ reference.endLine ?? '?' }}</small>
            <el-icon><View /></el-icon>
          </span>
        </button>
      </details>
      </div>
    </details>
  </section>
</template>

<style scoped>
.answer-evidence {
  width: 100%;
  min-width: 0;
  border: 1px solid #dedee3;
  border-radius: 6px;
  background: #f8fbfe;
}
.answer-evidence > summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  color: #005eb8;
  cursor: pointer;
  list-style-position: inside;
}
.answer-evidence > summary b { font-size: 13px; }
.evidence-card {
  padding: 16px;
  border-bottom: 1px solid #ededf0;
  transition: background-color .18s ease;
}
.evidence-card:focus { outline:2px solid var(--app-color-action); outline-offset:-2px; background:var(--app-selection-bg); }
.evidence-summary { cursor:pointer; }
.evidence-summary::marker { color:var(--app-text-muted); }
.evidence-body { display:grid; gap:12px; min-width:0; margin-top:12px; }
.source-origins { font-size:12px; color:var(--app-text-muted); overflow-wrap:anywhere; }
.source-origins summary { cursor:pointer; }
.source-origins small { display:block; margin-top:4px; }
.evidence-heading,
.evidence-actions { display: flex; align-items: center; }
.evidence-heading { justify-content: space-between; }
.evidence-heading small { color: var(--app-text-muted); }
.source-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 7px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 650;
}
.source-badge.code { color: #005eb8; background: var(--app-color-action-soft); }
.source-badge.knowledge { color: #6d4a00; background: #fff3ce; }
.evidence-card > p { margin: 0; color: #666; font-size: 13px; line-height: 1.55; }
.evidence-title { display: grid; gap: 4px; }
.evidence-title b { overflow-wrap: anywhere; color: #242426; font-size: 15px; line-height: 1.45; }
.evidence-title span { color: #8a6a24; font-size: 13px; letter-spacing: .04em; }
.code-location {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
  padding: 8px 10px;
  border: 1px solid #dce7f1;
  border-radius: 4px;
  background: #f6f9fc;
}
.code-location span { overflow-wrap: anywhere; color: #4f5e6c; font-size: 13px; line-height: 1.55; }
.code-location small { color: #005eb8; font-size: 13px; font-weight: 650; white-space: nowrap; }
.evidence-excerpt { max-height:84px; padding:9px 10px; overflow:hidden; border:1px solid #e4e7ea; border-radius:4px; background:#fafbfc; white-space:pre-wrap; }
.evidence-actions { gap: 6px; }
.knowledge-code-links { display: grid; gap: 7px; padding-top: 10px; border-top: 1px dashed #dde3e9; }
.linked-code-heading { display: flex; align-items: center; justify-content: space-between; }
.linked-code-heading > span { color: #3f4852; font-size: 13px; font-weight: 650; }
.linked-code-heading > small { color: var(--app-text-muted); font-size: 13px; }
.knowledge-code-links button {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 9px 10px;
  color: #344554;
  text-align: left;
  border: 1px solid #dce7f1;
  border-radius: 6px;
  background: #f8fbfe;
  transition: border-color .16s ease, background-color .16s ease;
}
.knowledge-code-links button:hover,
.knowledge-code-links button:focus-visible { border-color: var(--app-color-action); background: var(--app-selection-bg); }
.code-reference-main { display: grid; min-width: 0; gap: 3px; }
.code-reference-main b { overflow-wrap: anywhere; color: #005eb8; font-size: 13px; }
.code-reference-main small { overflow-wrap: anywhere; color: var(--app-text-muted); font-size: 13px; line-height: 1.45; }
.code-reference-meta { display: grid; justify-items: end; gap: 3px; color: var(--app-text-muted); white-space: nowrap; }
.code-reference-meta small { font-size: 13px; }
.code-reference-meta em { padding: 2px 5px; color: #a44b20; border-radius: 3px; background: #fff0e8; font-size: 13px; font-style: normal; }
@media (max-width:760px) { .answer-evidence>summary span { display:none; } }
</style>
