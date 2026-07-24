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
const fileResults = computed(() => {
  const firstChunkByFile = new Map<string, CodeChunk>();
  for (const chunk of chunks.value) {
    if (!firstChunkByFile.has(chunk.filePath)) firstChunkByFile.set(chunk.filePath, chunk);
  }
  return Array.from(firstChunkByFile.values()).map((chunk) => ({
    chunk,
    fileName: chunk.filePath.split(/[\\/]/).pop() ?? chunk.filePath,
  }));
});

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
        <div class="search-controls">
          <el-input v-model="query" class="app-search-input" :prefix-icon="Search" clearable placeholder="搜索代码" @keyup.enter="search" />
          <el-button type="primary" :loading="loading" @click="search">检索</el-button>
        </div>
        <small>当前 {{ fileResults.length }} 个文件，命中 {{ total }} 个代码片段</small>
      </div>
      <div class="file-result-list">
        <el-empty v-if="!loading && !fileResults.length" description="没有匹配的内容；请先完成内容索引" />
        <button
          v-for="file in fileResults"
          :key="file.chunk.id"
          :class="{ active: selectedChunk?.id === file.chunk.id }"
          :title="file.chunk.filePath"
          @click="selectedChunkId = file.chunk.id"
        >
          <b>{{ file.fileName }}</b>
        </button>
      </div>
    </aside>
    <main class="search-code-pane"><CodePreview :chunk="selectedChunk" /></main>
  </section>
</template>
