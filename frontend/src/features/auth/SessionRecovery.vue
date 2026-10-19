<script setup lang="ts">
defineProps<{ error: string; loading: boolean }>();
const emit = defineEmits<{ retry: [] }>();
</script>
<template>
  <main class="session-recovery">
    <section class="session-recovery-card" role="alert">
      <h1>暂时无法确认登录状态</h1>
      <p>{{ error || '正在重新检查登录状态…' }}</p>
      <p>请检查网络或稍后重试。恢复后会继续打开当前页面。</p>
      <el-button type="primary" :loading="loading" @click="emit('retry')">重新检查登录状态</el-button>
    </section>
  </main>
</template>
<style scoped>
.session-recovery { min-height:100dvh; display:grid; place-items:center; padding:24px; background:var(--app-canvas,#f4f7f9); }.session-recovery-card { width:min(100%,480px); padding:28px; border:1px solid var(--app-border,#d6e0e6); border-radius:8px; background:var(--app-surface,#fff); }.session-recovery-card h1 { font-size:20px; }.session-recovery-card p { color:var(--app-text-muted,#63717e); line-height:1.7; overflow-wrap:anywhere; }
</style>
