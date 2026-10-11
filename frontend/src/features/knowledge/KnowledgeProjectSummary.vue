<script setup lang="ts">
import { shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { intelligenceApi } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
const repositories = useRepositoryStore();
const branches = useBranchContextStore();
const router = useRouter();
const count = shallowRef<number | null>(null);
const published = shallowRef(0);
const error = shallowRef('');
watch(() => branches.context?.contextId, async (_, __, onCleanup) => {
  let current = true;
  onCleanup(() => { current = false; });
  count.value = null; published.value = 0; error.value = '';
  const context = branches.context;
  if (!context || context.repositoryId !== repositories.selectedRepositoryId) return;
  try {
    const cards = await intelligenceApi.cards(context.repositoryId, context.contextId);
    if (current) { count.value = cards.length; published.value = cards.filter(card => card.publicationStatus === 'PUBLISHED' && card.reviewStatus === 'APPROVED').length; }
  } catch (cause) { if (current) error.value = cause instanceof Error ? cause.message : '知识状态加载失败'; }
}, { immediate: true });
</script>
<template>
  <section class="surface knowledge-summary">
    <el-tag>纯知识库</el-tag>
    <h1>{{ repositories.selectedRepository?.name }}</h1>
    <p v-if="repositories.selectedRepository?.description">{{ repositories.selectedRepository.description }}</p>
    <el-alert v-if="error || branches.error" :title="error || branches.error || ''" type="error" :closable="false" />
    <p v-else>{{ count === null ? '正在读取知识内容…' : `可见知识卡 ${count} 条，已发布 ${published} 条` }}</p>
    <p>维护并发布知识卡，即可用于检索和问答。</p>
    <el-button type="primary" @click="router.push('/knowledge')">管理知识</el-button>
    <el-button @click="router.push('/search')">知识检索</el-button>
    <el-button @click="router.push('/ask')">项目问答</el-button>
    <el-button :loading="branches.loading" @click="branches.refresh()">刷新知识</el-button>
  </section>
</template>
<style scoped>
.knowledge-summary { margin: 20px; padding: 28px; color: var(--app-text-regular); }
.knowledge-summary h1 { color: var(--app-text-primary); font-size: 24px; overflow-wrap: anywhere; }
.knowledge-summary .el-button { margin-top: 8px; }
</style>
