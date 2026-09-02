<script setup lang="ts">
import { computed } from 'vue';
import zhCn from 'element-plus/es/locale/lang/zh-cn';

const props = defineProps<{
  pageNum: number;
  pageSize: number;
  total: number;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  pageChange: [pageNum: number];
  sizeChange: [pageSize: number];
}>();

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)));
const currentPage = computed(() => Math.min(Math.max(1, props.pageNum), pageCount.value));
const rangeStart = computed(() => props.total === 0 ? 0 : (currentPage.value - 1) * props.pageSize + 1);
const rangeEnd = computed(() => Math.min(currentPage.value * props.pageSize, props.total));
</script>

<template>
  <div class="app-pagination">
    <span>共 {{ total }} 条</span>
    <div class="pagination-summary">
      <span>共 <strong>{{ total }}</strong> 条</span>
      <span v-if="total > 0">当前显示 {{ rangeStart }}–{{ rangeEnd }} 条</span>
      <span>第 {{ currentPage }} / {{ pageCount }} 页</span>
    </div>
    <div class="pagination-controls" role="region" aria-label="分页操作">
      <el-config-provider :locale="zhCn">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :page-sizes="[15, 30, 50]"
          :total="total"
          :disabled="disabled || total === 0"
          :pager-count="5"
          layout="sizes, prev, pager, next, jumper"
          background
          @update:current-page="emit('pageChange', $event)"
          @update:page-size="emit('sizeChange', $event)"
        />
      </el-config-provider>
    </div>
  </div>
</template>

<style scoped>
.app-pagination {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  padding: 12px 4px 0;
  border-top: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 15px;
}

.app-pagination {
  min-height: 58px;
  justify-content: space-between;
  width: 100%;
  padding: 10px 16px;
  background: var(--el-bg-color);
}

.app-pagination > span {
  display: none;
}

.pagination-summary {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
}

.pagination-summary strong {
  color: var(--el-text-color-primary);
  font-weight: 650;
}

.app-pagination :deep(.el-pagination) {
  margin-left: auto;
}

@media (max-width: 900px) {
  .app-pagination { flex-wrap: wrap; }
  .pagination-summary { width: 100%; }
  .app-pagination :deep(.el-pagination) { margin-left: 0; }
}

.pagination-controls {
  min-width: 0;
  max-width: 100%;
  margin-left: auto;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-inline: contain;
  scrollbar-width: thin;
  -webkit-overflow-scrolling: touch;
}

.pagination-controls :deep(.el-pagination) {
  width: max-content;
  min-width: max-content;
  margin-left: auto;
  padding-bottom: 2px;
}

@media (max-width: 900px) {
  .app-pagination {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
  }

  .pagination-controls {
    width: 100%;
    margin-left: 0;
  }

  .pagination-controls :deep(.el-pagination) {
    margin-left: 0;
  }
}
</style>
