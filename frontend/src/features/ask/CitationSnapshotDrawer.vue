<script setup lang="ts">
import { computed } from 'vue';
import type { Citation } from '@/api/intelligence';
import MarkdownPreview from '@/components/MarkdownPreview.vue';
import { citationMarkdown } from './evidenceGroups';
const props = defineProps<{ citation: Citation | null }>();
const visible = defineModel<boolean>({ required: true });
const markdown = computed(() => props.citation ? citationMarkdown(props.citation, props.citation.content) : '');
</script>

<template>
  <el-drawer v-model="visible" class="citation-snapshot-drawer" size="min(1280px, 85vw)" append-to-body destroy-on-close :close-on-click-modal="false">
    <template #header><div class="snapshot-heading"><b>{{ citation?.title }}</b><small>本次回答保存的原文快照</small></div></template>
    <article v-if="citation" class="snapshot-document">
      <p class="snapshot-version">
        <template v-if="citation.knowledgeRevision">知识修订 {{ citation.knowledgeRevision }} · </template>
        <template v-if="citation.contentVersion">内容版本 {{ citation.contentVersion }} · </template>
        {{ citation.sourceScope || citation.filePath }}
      </p>
      <p v-if="citation.sourceType === 'CODE'" class="snapshot-version">保存范围：L{{ citation.startLine ?? '?' }}–{{ citation.endLine ?? '?' }}，仅展示当时保存的代码或文件片段。</p>
      <MarkdownPreview :content="markdown" :repository-id="citation.repositoryId" />
    </article>
    <template #footer><el-button @click="visible = false">关闭阅读</el-button></template>
  </el-drawer>
</template>

<style scoped>
.snapshot-heading { display:grid; gap:6px; min-width:0; overflow-wrap:anywhere; }
.snapshot-heading b { font-size:18px; }
.snapshot-heading small, .snapshot-version { color:var(--app-text-muted, #66717c); font-size:13px; overflow-wrap:anywhere; }
.snapshot-document { width:min(100%, 960px); margin:0 auto; }
@media(max-width:900px) { :global(.citation-snapshot-drawer) { width:100vw !important; } }
</style>
