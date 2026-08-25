<script setup lang="ts">
import type {
  VectorIndexChunk,
  VectorIndexKnowledge,
  VectorSource,
} from '@/api/vectorIndex';

defineProps<{
  source: VectorSource;
  items: Array<VectorIndexChunk | VectorIndexKnowledge>;
  loading: boolean;
}>();

const emit = defineEmits<{
  openCode: [item: VectorIndexChunk];
}>();

function isChunk(item: VectorIndexChunk | VectorIndexKnowledge): item is VectorIndexChunk {
  return 'filePath' in item;
}

function range(item: VectorIndexChunk) {
  return `L${item.startLine ?? '?'}–${item.endLine ?? '?'}`;
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString() : '尚未生成';
}
</script>

<template>
  <el-table v-loading="loading" :data="items" row-key="id" empty-text="当前筛选条件下暂无索引内容">
    <template v-if="source === 'code'">
      <el-table-column label="文件与符号" min-width="250">
        <template #default="{ row }">
          <div v-if="isChunk(row)" class="item-identity">
            <b>{{ row.symbolName || row.filePath.split('/').pop() }}</b>
            <span class="mono">{{ row.filePath }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="位置" width="110">
        <template #default="{ row }">
          <span v-if="isChunk(row)" class="mono">{{ range(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag v-if="isChunk(row)" effect="plain" size="small">{{ row.chunkType }}</el-tag>
        </template>
      </el-table-column>
    </template>
    <template v-else>
      <el-table-column label="知识卡片" min-width="260">
        <template #default="{ row }">
          <div v-if="!isChunk(row)" class="item-identity">
            <b>{{ row.title }}</b>
            <span>{{ row.cardType }} · 修订 v{{ row.revision }}</span>
          </div>
        </template>
      </el-table-column>
    </template>

    <el-table-column label="送入向量的内容" min-width="300">
      <template #default="{ row }">
        <p class="content-excerpt">{{ row.contentExcerpt || '没有可预览内容' }}</p>
      </template>
    </el-table-column>
    <el-table-column label="检索能力" min-width="175">
      <template #default="{ row }">
        <div class="vector-model">
          <b>{{ row.capabilityLabel ?? '尚未生成' }}</b>
          <small>{{ row.vectorModel ?? '—' }}{{ row.dimension ? ` · ${row.dimension} 维` : '' }}</small>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="105">
      <template #default="{ row }">
        <el-tag :type="row.vectorStatus === 'EMBEDDED' ? 'success' : 'warning'" size="small">
          {{ row.vectorStatus === 'EMBEDDED' ? '已向量化' : '缺失' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="生成时间" width="180">
      <template #default="{ row }">{{ formatTime(row.vectorizedAt) }}</template>
    </el-table-column>
    <el-table-column v-if="source === 'code'" label="操作" width="90" fixed="right">
      <template #default="{ row }">
        <el-button v-if="isChunk(row)" link type="primary" @click="emit('openCode', row)">查看代码</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.item-identity,
.vector-model {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.item-identity b,
.vector-model b,
.item-identity span,
.vector-model code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-identity b {
  color: #1d1d1f;
  font-size: 12px;
}

.vector-model b {
  color: #1d1d1f;
  font-size: 11px;
}

.item-identity span,
.vector-model small {
  color: var(--app-text-muted);
  font-size: 11px;
}

.content-excerpt {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #5f5f66;
  font-size: 11px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.vector-model code {
  color: #4a4a4f;
  font-size: 11px;
}
</style>
