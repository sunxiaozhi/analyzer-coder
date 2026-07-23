<script setup lang="ts">
import { Search } from '@element-plus/icons-vue';
import { computed, onMounted, ref, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import CodePreview from '@/components/CodePreview.vue';
import { listChunks } from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { CodeChunk } from '@/types/api';

const repositoryStore = useRepositoryStore();
const query = shallowRef('');
const chunks = ref<CodeChunk[]>([]);
const total = shallowRef(0);
const selectedChunkId = shallowRef<string | null>(null);
const loading = shallowRef(false);

const selectedChunk = computed(() => chunks.value.find((chunk) => chunk.id === selectedChunkId.value) ?? chunks.value[0] ?? null);

async function search() {
  if (!repositoryStore.selectedRepositoryId) {
    ElMessage.warning('请先选择仓库');
    return;
  }
  loading.value = true;
  try {
    const result = await listChunks(repositoryStore.selectedRepositoryId, { q: query.value.trim(), limit: 50 });
    chunks.value = result.chunks;
    total.value = result.total;
    selectedChunkId.value = result.chunks[0]?.id ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '检索失败');
  } finally {
    loading.value = false;
  }
}

onMounted(async () => {
  await repositoryStore.loadRepositories();
  if (repositoryStore.selectedRepositoryId) await search();
});
</script>

<template>
  <section class="code-search-design">
    <aside class="search-results-pane">
      <div class="result-search">
        <el-input v-model="query" class="app-search-input" :prefix-icon="Search" clearable placeholder="关键词、文件路径或代码内容" @keyup.enter="search" />
        <el-button type="primary" :loading="loading" @click="search">检索</el-button>
        <small>共 {{ total }} 个 chunk；M0 仅提供关键词检索。</small>
      </div>
      <el-empty v-if="!loading && !chunks.length" description="没有匹配的内容；请先完成内容索引" />
      <button v-for="chunk in chunks" :key="chunk.id" :class="{ active: selectedChunk?.id === chunk.id }" @click="selectedChunkId = chunk.id">
        <header><b>{{ chunk.symbolName ?? chunk.filePath }}</b><strong>{{ chunk.language ?? 'text' }}</strong></header>
        <p>{{ chunk.content.slice(0, 140) }}</p>
        <footer><span class="mono">{{ chunk.filePath }}</span><span class="mono">L{{ chunk.startLine }}-{{ chunk.endLine }}</span></footer>
      </button>
    </aside>
    <main class="search-code-pane"><CodePreview :chunk="selectedChunk" /></main>
    <aside class="search-evidence-pane">
      <header><b>证据信息</b><p>结果直接来自已发布的内容 chunk，不包含模型生成摘要。</p></header>
      <article v-if="selectedChunk">
        <b>{{ selectedChunk.chunkType }} · {{ selectedChunk.language ?? 'text' }}</b>
        <span class="mono">{{ selectedChunk.filePath }} L{{ selectedChunk.startLine }}-{{ selectedChunk.endLine }}</span>
        <span class="mono">SHA-256 {{ selectedChunk.contentHash.slice(0, 16) }}</span>
      </article>
    </aside>
  </section>
</template>
