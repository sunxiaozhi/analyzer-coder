<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { RefreshCw, Search } from 'lucide-vue-next';
import AppPagination from '@/components/AppPagination.vue';
import VectorIndexSummary from '@/features/indexing/VectorIndexSummary.vue';
import VectorIndexTable from '@/features/indexing/VectorIndexTable.vue';
import { useCurrentVectorIndex } from '@/features/indexing/useCurrentVectorIndex';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { VectorIndexChunk } from '@/api/vectorIndex';

const router = useRouter();
const repositoryStore = useRepositoryStore();
const repositoryId = computed(() => repositoryStore.selectedRepositoryId);
const {
  summary,
  source,
  query,
  status,
  chunkType,
  pageNum,
  pageSize,
  total,
  loading,
  error,
  items,
  refresh,
  search,
  changePage,
  changePageSize,
} = useCurrentVectorIndex(repositoryId);

function openCode(item: VectorIndexChunk) {
  void router.push({
    name: 'search',
    query: {
      path: item.filePath,
      startLine: String(item.startLine ?? 1),
      endLine: String(item.endLine ?? item.startLine ?? 1),
    },
  });
}
</script>

<template>
  <section class="vector-index-panel">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <VectorIndexSummary :summary="summary" />

    <div class="surface vector-index-list">
      <div class="source-tabs">
        <button :class="{ active: source === 'code' }" @click="source = 'code'">
          代码分块 <span>{{ summary?.vectorizedChunks ?? 0 }}/{{ summary?.totalChunks ?? 0 }}</span>
        </button>
        <button :class="{ active: source === 'knowledge' }" @click="source = 'knowledge'">
          知识卡片 <span>{{ summary?.vectorizedKnowledgeCards ?? 0 }}/{{ summary?.knowledgeCards ?? 0 }}</span>
        </button>
      </div>

      <div class="vector-filters">
        <el-input
          v-model="query"
          :prefix-icon="Search"
          clearable
          :placeholder="source === 'code' ? '搜索文件、符号或代码内容' : '搜索知识标题或内容'"
          @clear="search"
          @keyup.enter="search"
        />
        <el-select v-model="status" placeholder="全部状态" clearable>
          <el-option label="已向量化" value="EMBEDDED" />
          <el-option label="缺失" value="MISSING" />
        </el-select>
        <el-select v-if="source === 'code'" v-model="chunkType" placeholder="全部分块类型" clearable>
          <el-option label="文件" value="FILE" />
          <el-option label="符号" value="SYMBOL" />
          <el-option label="文档" value="DOC_SECTION" />
          <el-option label="测试" value="TEST_CASE" />
          <el-option label="配置" value="CONFIG" />
        </el-select>
        <el-button type="primary" @click="search">筛选</el-button>
        <span class="spacer" />
        <el-button :icon="RefreshCw" :loading="loading" @click="refresh">刷新</el-button>
      </div>

      <div class="vector-table-region">
        <el-empty
          v-if="!repositoryStore.selectedRepositoryId"
          description="请选择仓库查看当前向量索引"
        />
        <VectorIndexTable
          v-else
          :source="source"
          :items="items"
          :loading="loading"
          @open-code="openCode"
        />
      </div>

      <AppPagination
        :page-num="pageNum"
        :page-size="pageSize"
        :total="total"
        :disabled="loading"
        @page-change="changePage"
        @size-change="changePageSize"
      />
    </div>
  </section>
</template>

<style scoped>
.vector-index-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
}

.spacer {
  flex: 1;
}

.vector-index-list {
  display: grid;
  flex: 1;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  min-height: 0;
}

.source-tabs {
  display: flex;
  gap: 4px;
  min-height: 48px;
  padding: 0 12px;
  border-bottom: 1px solid #ececef;
}

.source-tabs button {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  color: #65656c;
  font-size: 12px;
}

.source-tabs button.active {
  border-color: #0066cc;
  color: #005eb8;
  font-weight: 600;
}

.source-tabs span {
  padding: 2px 5px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #71717a;
  font-size: 11px;
}

.vector-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #ececef;
}

.vector-filters > .el-input {
  width: min(360px, 40vw);
}

.vector-filters > .el-select {
  width: 145px;
}

.vector-table-region {
  min-height: 0;
  overflow: auto;
}

@media (max-width: 760px) {
  .vector-index-panel,
  .vector-index-list {
    display: block;
  }

  .vector-index-panel > * + * {
    margin-top: 12px;
  }

  .vector-filters {
    align-items: stretch;
    flex-direction: column;
  }

  .vector-filters > .el-input,
  .vector-filters > .el-select {
    width: 100%;
  }

  .vector-table-region {
    overflow: visible;
  }
}
</style>
