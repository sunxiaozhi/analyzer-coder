<script setup lang="ts">
defineProps<{ name: string; description?: string; branchName: string; total: number; published: number; drafts: number; loading: boolean }>();
const emit = defineEmits<{ ask: []; refresh: [] }>();
</script>

<template>
  <header class="library-header">
    <div class="library-copy">
      <h1>{{ name }}</h1>
      <p v-if="description">{{ description }}</p>
      <span>{{ branchName }} 虚拟分支</span>
      <span v-if="loading" role="status">正在读取知识…</span>
      <span v-else>可见知识 {{ total }} 条 · 已发布 {{ published }} 条 · 草稿 {{ drafts }} 条</span>
    </div>
    <div class="library-actions">
      <el-button @click="emit('ask')">知识问答</el-button>
      <el-button :loading="loading" @click="emit('refresh')">刷新知识</el-button>
    </div>
  </header>
</template>

<style scoped>
.library-header { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:16px; padding:20px; border-bottom:1px solid var(--app-border); }
.library-copy { min-width:0; }.library-copy h1 { margin:0 0 8px; color:var(--app-text-primary); font-size:20px; overflow-wrap:anywhere; }.library-copy p { margin:0 0 8px; color:var(--app-text-regular); font-size:13px; overflow-wrap:anywhere; }.library-copy span { display:inline-block; margin-right:16px; color:var(--app-text-muted); font-size:12px; }
.library-actions { display:flex; gap:8px; }.library-actions :deep(.el-button + .el-button) { margin-left:0; }
</style>
