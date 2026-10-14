<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { usePublishedKnowledgeSearch } from './usePublishedKnowledgeSearch';
import { knowledgeExcerpt } from './knowledgePresentation';
const props = defineProps<{ query: string; knowledgeKind?: string }>();
const emit = defineEmits<{ clearType: []; allKnowledge: []; clearQuery: [] }>();
const router = useRouter();
const branches = useBranchContextStore();
const repositories = useRepositoryStore();
const search = usePublishedKnowledgeSearch({ query: () => props.query, context: () =>
  branches.context?.repositoryId === repositories.selectedRepositoryId ? branches.context : null, knowledgeKind: () => props.knowledgeKind });
const visibleHits = computed(() => search.hits.value);
function open(id: string) {
  void router.push({ name: 'knowledge', query: { branchId: branches.context?.branchId, contextId: branches.context?.contextId, cardId: id, q: props.query, searchScope: 'PUBLISHED' } });
}
</script>

<template>
  <section class="published-results" v-loading="search.loading.value" aria-label="已发布知识检索结果">
    <el-alert v-if="search.error.value" :title="search.error.value" type="error" :closable="false"><el-button @click="search.retry">重试检索</el-button></el-alert>
    <el-alert v-if="search.degraded.value" title="部分检索通道不可用，已保留可用结果。" type="info" :closable="false" />
    <p v-if="!search.loading.value && !search.error.value" role="status">找到 {{ visibleHits.length }} 条已发布知识（最多返回 50 条）</p>
    <el-empty v-if="!search.loading.value && !search.error.value && !visibleHits.length" description="没有找到相关的已发布知识，请调整关键词或筛选范围">
      <el-button v-if="knowledgeKind" @click="emit('clearType')">清除类型筛选</el-button>
      <el-button @click="emit('allKnowledge')">在全部知识中筛选</el-button>
      <el-button @click="emit('clearQuery')">清除关键词</el-button>
    </el-empty>
    <div class="published-grid">
      <article v-for="(hit, index) in visibleHits" :key="`${hit.knowledgeCardId}:${index}`" class="published-hit">
        <small>已发布知识 · {{ branches.context?.branchName }} 虚拟分支</small>
        <h2><button type="button" @click="open(hit.knowledgeCardId!)">{{ hit.title }}</button></h2>
        <p>{{ knowledgeExcerpt(hit.content) }}</p>
        <el-button link type="primary" @click="open(hit.knowledgeCardId!)">查看知识</el-button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.published-results { min-height:160px; }.published-results>p { color:var(--app-text-muted); font-size:13px; }
.published-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(min(100%,320px),1fr)); gap:16px; }.published-hit { display:flex; flex-direction:column; gap:12px; padding:16px; border:1px solid var(--app-border); border-radius:8px; }.published-hit small { color:var(--app-text-muted); font-size:12px; }.published-hit h2,.published-hit p { margin:0; overflow-wrap:anywhere; }.published-hit h2 button { padding:0; border:0; background:transparent; color:var(--app-text-primary); font:inherit; font-size:15px; text-align:left; cursor:pointer; }.published-hit h2 button:focus-visible { outline:2px solid var(--app-color-action); outline-offset:3px; }.published-hit p { flex:1; color:var(--app-text-regular); font-size:13px; line-height:1.7; }.published-hit :deep(.el-button) { align-self:flex-start; }
</style>
