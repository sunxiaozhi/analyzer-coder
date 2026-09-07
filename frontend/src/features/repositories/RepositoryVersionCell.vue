<script setup lang="ts">
import { computed } from 'vue';
import type { Repository } from '@/types/api';

const props = defineProps<{ repository: Repository }>();

const shortCommit = computed(() => props.repository.commit?.slice(0, 8) ?? '未识别');
const shortDigest = computed(() => props.repository.worktreeDigest?.slice(0, 10) ?? '未计算');
const shortSnapshot = computed(() => props.repository.snapshotId?.slice(0, 8) ?? '未发布');
const snapshotTime = computed(() =>
  props.repository.snapshotCreatedAt
    ? new Date(props.repository.snapshotCreatedAt).toLocaleString()
    : '无版本',
);
</script>

<template>
  <div class="version-cell">
    <div class="version-main">
      <span class="branch">{{ repository.branch ?? 'detached HEAD' }}</span>
      <span class="mono">{{ shortCommit }}</span>
    </div>
    <div class="version-meta">
      <el-tag :type="repository.dirty ? 'warning' : 'success'" effect="plain" size="small">
        {{ repository.dirty ? '工作区有修改' : '工作区干净' }}
      </el-tag>
      <span class="mono digest">{{ shortDigest }}</span>
    </div>
    <div class="snapshot-meta" :title="repository.snapshotId ?? '尚未发布代码版本'">
      <el-tag type="info" effect="plain" size="small">版本 {{ shortSnapshot }}</el-tag>
      <span class="snapshot-time">{{ snapshotTime }}</span>
    </div>
  </div>
</template>

<style scoped>
.version-cell { display: grid; min-width: 0; gap: 7px; }
.version-main, .version-meta, .snapshot-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 9px; }
.version-cell .el-tag { flex-shrink: 0; }
.branch { min-width: 0; white-space: normal; overflow-wrap: anywhere; color: var(--text-strong, #172033); font-weight: 650; }
.snapshot-time { white-space: normal; }
.digest, .snapshot-time { color: var(--text-muted, #748096); font-size: 14px; }
</style>
