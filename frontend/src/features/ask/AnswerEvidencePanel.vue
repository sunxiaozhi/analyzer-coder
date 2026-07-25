<script setup lang="ts">
import { Connection, Document, Reading, View } from '@element-plus/icons-vue';
import type { Citation, CodeReference } from '@/api/intelligence';

defineProps<{ citations: Citation[] }>();
const emit = defineEmits<{
  openKnowledge: [cardId: string];
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
}>();

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
  <aside class="answer-evidence">
    <header>
      <b>回答证据</b>
      <span>{{ citations.length }} 条 · 知识与代码联合检索</span>
    </header>

    <el-empty v-if="!citations.length" description="本次回答没有可引用证据" />
    <article v-for="citation in citations" :key="citation.id" class="evidence-card">
      <div class="evidence-heading">
        <span :class="['source-badge', citation.sourceType.toLowerCase()]">
          <el-icon><Reading v-if="citation.sourceType === 'KNOWLEDGE'" /><Document v-else /></el-icon>
          {{ citation.sourceType === 'KNOWLEDGE' ? '团队知识' : '当前代码' }}
        </span>
        <small>S{{ citation.rank }}</small>
      </div>
      <b>{{ citation.title }}</b>
      <p v-if="citation.sourceType === 'CODE'" class="mono">
        {{ citation.filePath }} · L{{ citation.startLine ?? '?' }}–{{ citation.endLine ?? '?' }}
      </p>
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
        <span>关联代码</span>
        <button v-for="reference in citation.codeReferences"
          :key="reference.chunkId ?? reference.filePath"
          type="button"
          @click="emit('openCode', reference)">
          {{ reference.symbolName || reference.filePath.split('/').pop() }}
          <small>L{{ reference.startLine ?? '?' }}</small>
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
  gap: 7px;
  padding: 14px;
  border-bottom: 1px solid #ededf0;
}

.evidence-heading,
.evidence-actions,
.knowledge-code-links {
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
.evidence-card > b { overflow-wrap: anywhere; font-size: 12px; }
.evidence-card > p { margin: 0; color: #666; font-size: 10px; line-height: 1.55; }
.evidence-excerpt { max-height: 64px; overflow: hidden; white-space: pre-wrap; }
.evidence-actions { gap: 6px; }

.knowledge-code-links {
  flex-wrap: wrap;
  gap: 6px;
  padding-top: 7px;
  border-top: 1px dashed #dde3e9;
}

.knowledge-code-links > span { color: #777; font-size: 9px; }
.knowledge-code-links button {
  display: inline-flex;
  gap: 5px;
  padding: 4px 7px;
  color: #005eb8;
  border: 1px solid #cfe1f3;
  border-radius: 4px;
  background: #f5f9fd;
  font-size: 9px;
}
.knowledge-code-links small { color: #718090; }

@media (max-width: 760px) {
  .answer-evidence { border-left: 1px solid #dedee3; }
}
</style>
