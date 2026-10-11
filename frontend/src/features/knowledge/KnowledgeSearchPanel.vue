<script setup lang="ts">
import { onScopeDispose, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { intelligenceApi, type UnifiedSearchHit } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { knowledgeExcerpt } from './knowledgePresentation';
const repositories = useRepositoryStore();
const branches = useBranchContextStore();
const route = useRoute();
const router = useRouter();
const query = shallowRef('');
const hits = shallowRef<UnifiedSearchHit[]>([]);
const loading = shallowRef(false);
const searched = shallowRef(false);
const error = shallowRef('');
const degraded = shallowRef(false);
let requestVersion = 0;
onScopeDispose(() => { requestVersion++; });
async function search() {
  const version = ++requestVersion;
  hits.value = []; error.value = ''; degraded.value = false; loading.value = false; searched.value = false;
  const context = branches.context;
  if (!query.value.trim() || !context || context.repositoryId !== repositories.selectedRepositoryId) return;
  loading.value = true;
  try {
    const result = await intelligenceApi.unifiedSearch(context.repositoryId, query.value.trim(), 50, context.contextId);
    if (version !== requestVersion || context.contextId !== branches.context?.contextId) return;
    hits.value = result.evidence; degraded.value = result.retrieval.degraded; searched.value = true;
  } catch (cause) { if (version === requestVersion) error.value = cause instanceof Error ? cause.message : '知识检索失败'; }
  finally { if (version === requestVersion) loading.value = false; }
}
watch(() => [repositories.selectedRepositoryId, branches.context?.contextId, route.query.q], () => {
  requestVersion++; hits.value = []; searched.value = false; loading.value = false; error.value = '';
  query.value = typeof route.query.q === 'string' ? route.query.q : '';
  if (query.value) void search();
}, { immediate: true });
</script>
<template>
  <section class="surface knowledge-search">
    <header><h1>知识检索</h1><span>{{ repositories.selectedRepository?.name }}</span></header>
    <form class="search-command" @submit.prevent="search">
      <el-input v-model="query" placeholder="搜索已发布知识的标题、正文或标签" aria-label="知识检索关键词" clearable />
      <el-button type="primary" native-type="submit" :loading="loading" :disabled="!branches.context">检索</el-button>
      <el-button :loading="branches.loading" @click="branches.refresh()">刷新知识</el-button>
    </form>
    <el-alert v-if="error || branches.error" :title="error || branches.error || ''" type="error" :closable="false" />
    <el-alert v-if="degraded" title="部分知识检索通道不可用，已保留可用结果。" type="info" :closable="false" />
    <p v-if="searched" role="status">找到 {{ hits.length }} 条知识</p>
    <el-empty v-if="!loading && !hits.length" :description="searched ? '没有找到相关的已发布知识，请调整关键词' : '输入关键词检索已发布知识'" />
    <article v-for="hit in hits" :key="hit.knowledgeCardId || hit.title" class="knowledge-hit">
      <h2><el-button link type="primary" @click="router.push({ path: '/knowledge', query: { cardId: hit.knowledgeCardId || undefined } })">{{ hit.title }}</el-button></h2>
      <p>{{ knowledgeExcerpt(hit.content) }}</p>
    </article>
  </section>
</template>
<style scoped>
.knowledge-search { padding: 24px; height: 100%; overflow: auto; }
.knowledge-search header { display: flex; align-items: baseline; gap: 16px; flex-wrap: wrap; }
.knowledge-search h1 { font-size: 20px; }
.search-command { display: flex; gap: 10px; margin-bottom: 18px; }
.knowledge-hit { padding: 16px 0; border-top: 1px solid var(--app-border); }
.knowledge-hit h2 { margin: 0 0 8px; }
.knowledge-hit p { margin: 0; color: var(--app-text-regular); line-height: 1.7; overflow-wrap: anywhere; }
@media(max-width: 640px) { .search-command { flex-wrap: wrap; } .search-command .el-input { flex-basis: 100%; } }
</style>
