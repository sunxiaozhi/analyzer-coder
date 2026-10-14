<script setup lang="ts">
import { onScopeDispose, shallowRef } from 'vue';
defineProps<{ localEvidence: boolean }>();
const emit = defineEmits<{ stop: [] }>();
const startedAt = Date.now();
const seconds = shallowRef(0);
const timer = setInterval(() => { seconds.value = Math.floor((Date.now() - startedAt) / 1000); }, 1000);
onScopeDispose(() => clearInterval(timer));
</script>

<template>
  <div class="request-progress" role="status">
    <strong>{{ localEvidence ? '正在检索和核对证据' : '正在检索证据并生成回答' }}</strong>
    <span>已等待 {{ seconds }} 秒</span>
    <p v-if="seconds >= 20">等待时间较长，可以停止等待，稍后刷新历史记录查看已保存的回答。</p>
    <el-button link type="primary" @click="emit('stop')">停止等待</el-button>
    <small>停止等待后，服务端任务可能继续运行。</small>
  </div>
</template>

<style scoped>
.request-progress { display:flex; flex-wrap:wrap; align-items:center; gap:8px 14px; }.request-progress strong { font-size:14px; }.request-progress p { flex-basis:100%; margin:0; }.request-progress span,.request-progress small,.request-progress p { color:var(--app-text-muted); font-size:13px; }
</style>
