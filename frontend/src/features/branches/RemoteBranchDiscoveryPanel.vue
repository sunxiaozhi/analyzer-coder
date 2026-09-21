<script setup lang="ts">
import { computed } from 'vue';
import { CloudDownload, RefreshCw } from 'lucide-vue-next';
import type { RemoteBranch } from '@/api/branches';

const props = defineProps<{
  branches: RemoteBranch[];
  trackedNames: string[];
  loading: boolean;
  disabled: boolean;
}>();

const emit = defineEmits<{
  refresh: [];
  track: [name: string];
}>();

const tracked = computed(() => new Set(props.trackedNames));
const shortCommit = (commitSha: string) => commitSha.slice(0, 12);
</script>

<template>
  <section class="remote-branches" aria-labelledby="remote-branches-title">
    <header class="remote-header">
      <div>
        <strong id="remote-branches-title"><CloudDownload :size="16" />远程分支</strong>
        <p>只添加需要阅读的分支；发现操作不会自动创建版本。</p>
      </div>
      <el-button
        plain
        :loading="loading"
        :disabled="disabled"
        aria-label="发现远程分支"
        @click="emit('refresh')"
      >
        <RefreshCw :size="14" />发现分支
      </el-button>
    </header>

    <p v-if="!loading && !branches.length" class="remote-empty">点击“发现分支”读取远程仓库当前分支列表。</p>
    <ul v-else class="remote-list">
      <li v-for="branch in branches" :key="branch.name">
        <div>
          <b>{{ branch.name }}</b>
          <code>{{ shortCommit(branch.commitSha) }}</code>
        </div>
        <el-button
          size="small"
          :disabled="disabled || loading || tracked.has(branch.name)"
          @click="emit('track', branch.name)"
        >
          {{ tracked.has(branch.name) ? '已添加' : '添加' }}
        </el-button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.remote-branches { display: grid; gap: 10px; padding: 12px; border: 1px solid #dbe3ec; border-radius: 6px; background: #f8fafc; }
.remote-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.remote-header strong { display: inline-flex; align-items: center; gap: 7px; color: #1f2937; font-size: 13px; }
.remote-header p, .remote-empty { margin: 4px 0 0; color: #68778a; font-size: 12px; line-height: 1.6; }
.remote-list { display: grid; gap: 7px; max-height: 248px; margin: 0; padding: 0; overflow-y: auto; list-style: none; }
.remote-list li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 10px; border: 1px solid #e5eaf0; border-radius: 5px; background: #fff; }
.remote-list li > div { display: grid; min-width: 0; }
.remote-list b { overflow: hidden; color: #263244; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.remote-list code { color: #718096; font-family: Consolas, monospace; font-size: 11px; }
@media (max-width: 560px) {
  .remote-header { align-items: stretch; flex-direction: column; }
}
</style>
