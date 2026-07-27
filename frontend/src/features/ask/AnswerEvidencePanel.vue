<script setup lang="ts">
import { Connection, Document, Reading, View } from '@element-plus/icons-vue';
import { nextTick, shallowRef, watch } from 'vue';
import type { Citation, CodeReference } from '@/api/intelligence';

const props = defineProps<{ citations: Citation[]; activeCitationId?: string | null }>();
const emit = defineEmits<{
  openKnowledge: [cardId: string];
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
}>();
const panelElement = shallowRef<HTMLElement | null>(null);

watch(() => props.activeCitationId, async citationId => {
  if (!citationId) return;
  await nextTick();
  const card = panelElement.value?.querySelector<HTMLElement>(`[data-citation-id="${citationId}"]`);
  card?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
});

function citationReference(citation: Citation): CodeReference | null {
  if (citation.sourceType !== 'CODE' || !citation.chunkId) return null;
  return {
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
      <b>回答证据</b>
      <span>{{ citations.length }} 条 · 知识与代码联合检索</span>
    </header>

    <el-empty v-if="!citations.length" description="本次回答没有可引用证据" />
    <article
      v-for="citation in citations"
      :key="citation.id"
      :data-citation-id="citation.id"
      :class="['evidence-card', { active: citation.id === activeCitationId }]"
    >
      <div class="evidence-heading">
        <span :class="['source-badge', citation.sourceType.toLowerCase()]">
          <el-icon><Reading v-if="citation.sourceType === 'KNOWLEDGE'" /><Document v-else /></el-icon>
          {{ citation.sourceType === 'KNOWLEDGE' ? '团队知识' : '当前代码' }}
        </span>
        <small>S{{ citation.rank }}</small>
      </div>
      <div class="evidence-title">
        <b>{{ citation.title }}</b>
        <span v-if="citation.sourceType === 'KNOWLEDGE'">知识库内容</span>
      </div>
      <div v-if="citation.sourceType === 'CODE'" class="code-location">
        <span class="mono">{{ citation.filePath }}</span>
        <small>L{{ citation.startLine ?? '?' }}–{{ citation.endLine ?? '?' }}</small>
      </div>
      <p class="evidence-excerpt">{{ citation.content.slice(0, 360) }}</p>

      <div class="evidence-actions">
        <template v-if="citation.sourceType === 'KNOWLEDGE' && citation.knowledgeCardId">
          <el-button link type="primary" :icon="Reading"
            @click="emit('openKnowledge', citation.knowledgeCardId)">查看知识</el-button>
        </template>
        <template v-else-if="citationReference(citation)">
          <el-button link type="primary" :icon="View"
            @click="emit('openCode', citationReference(citation)!)">查看源码</el-button>
          <el-button link :icon="Connection"
            @click="emit('openGraph', citationReference(citation)!)">调用图谱</el-button>
        </template>
      </div>

      <div v-if="citation.codeReferences.length" class="knowledge-code-links">
        <div class="linked-code-heading">
          <span>关联代码</span>
          <small>{{ citation.codeReferences.length }} 处</small>
        </div>
        <button v-for="reference in citation.codeReferences"
          :key="reference.chunkId ?? reference.filePath"
          type="button"
          @click="emit('openCode', reference)">
          <span class="code-reference-main">
            <b>{{ reference.symbolName || reference.filePath.split('/').pop() }}</b>
            <small class="mono">{{ reference.filePath }}</small>
          </span>
          <span class="code-reference-meta">
            <em v-if="reference.stale">待复核</em>
            <small>L{{ reference.startLine ?? '?' }}–{{ reference.endLine ?? '?' }}</small>
            <el-icon><View /></el-icon>
          </span>
        </button>
      </div>
    </article>
  </aside>
</template>

<style scoped>
.answer-evidence {
  min-width: 0;
  overflow: auto;
  border: 1px solid #dedee3;
  border-left: 0;
  background: #fff;
}

.answer-evidence > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 49px;
  padding: 0 13px;
  border-bottom: 1px solid #ececef;
}

.answer-evidence > header b { font-size: 13px; }
.answer-evidence > header span { color: #777; font-size: 9px; }

.evidence-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border-bottom: 1px solid #ededf0;
  box-shadow: inset 3px 0 transparent;
  transition: background-color .18s ease, box-shadow .18s ease;
}
.evidence-card.active {
  background: #f6faff;
  box-shadow: inset 3px 0 #0066cc;
}

.evidence-heading,
.evidence-actions {
  display: flex;
  align-items: center;
}

.evidence-heading { justify-content: space-between; }
.evidence-heading small { color: #8a8a91; }

.source-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 7px;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 650;
}

.source-badge.code { color: #005eb8; background: #eaf3fd; }
.source-badge.knowledge { color: #6d4a00; background: #fff3ce; }
.evidence-card > p { margin: 0; color: #666; font-size: 10px; line-height: 1.55; }
.evidence-title {
  display: grid;
  gap: 4px;
}
.evidence-title b {
  overflow-wrap: anywhere;
  color: #242426;
  font-size: 13px;
  line-height: 1.45;
}
.evidence-title span {
  color: #8a6a24;
  font-size: 9px;
  letter-spacing: .04em;
}
.code-location {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
  padding: 8px 10px;
  border-left: 2px solid #8cb9e4;
  background: #f6f9fc;
}
.code-location span {
  overflow-wrap: anywhere;
  color: #4f5e6c;
  font-size: 9px;
  line-height: 1.55;
}
.code-location small {
  color: #005eb8;
  font-size: 9px;
  font-weight: 650;
  white-space: nowrap;
}
.evidence-excerpt {
  max-height: 84px;
  padding-left: 10px;
  overflow: hidden;
  border-left: 1px solid #e4e5e8;
  white-space: pre-wrap;
}
.evidence-actions { gap: 6px; }

.knowledge-code-links {
  display: grid;
  gap: 7px;
  padding-top: 10px;
  border-top: 1px dashed #dde3e9;
}

.linked-code-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.linked-code-heading > span {
  color: #3f4852;
  font-size: 10px;
  font-weight: 650;
}
.linked-code-heading > small { color: #8a8a91; font-size: 9px; }
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
.knowledge-code-links button:focus-visible {
  border-color: #8cb9e4;
  background: #eef6fd;
  outline: none;
}
.code-reference-main {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.code-reference-main b {
  overflow-wrap: anywhere;
  color: #005eb8;
  font-size: 10px;
}
.code-reference-main small {
  overflow-wrap: anywhere;
  color: #718090;
  font-size: 8px;
  line-height: 1.45;
}
.code-reference-meta {
  display: grid;
  justify-items: end;
  gap: 3px;
  color: #718090;
  white-space: nowrap;
}
.code-reference-meta small { font-size: 9px; }
.code-reference-meta em {
  padding: 2px 5px;
  color: #a44b20;
  border-radius: 3px;
  background: #fff0e8;
  font-size: 8px;
  font-style: normal;
}

@media (max-width: 760px) {
  .answer-evidence { border-left: 1px solid #dedee3; }
}
</style>
