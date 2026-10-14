<script setup lang="ts">
defineProps<{ page: number; pages: number; total: number; pageSize: number }>();
const emit = defineEmits<{ change: [page: number] }>();
</script>
<template>
  <nav v-if="pages > 1" class="knowledge-pagination" aria-label="知识列表分页">
    <span>共 {{ total }} 条 · 每页 {{ pageSize }} 条 · 第 {{ page }} / {{ pages }} 页</span>
    <el-button :disabled="page <= 1" @click="emit('change', page - 1)">上一页</el-button>
    <el-button :disabled="page >= pages" @click="emit('change', page + 1)">下一页</el-button>
  </nav>
</template>
<style scoped>
.knowledge-pagination { display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:10px; padding:16px 0; }.knowledge-pagination span { margin-right:auto; font-size:13px; color:var(--app-text-muted); }
</style>
