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
    : '无快照',
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
    <div class="snapshot-meta" :title="repository.snapshotId ?? '尚未发布快照'">
      <el-tag type="info" effect="plain" size="small">快照 {{ shortSnapshot }}</el-tag>
      <span class="snapshot-time">{{ snapshotTime }}</span>
    </div>
  </div>
</template>

<style scoped>
.version-cell { display: grid; gap: 7px; }
.version-main, .version-meta, .snapshot-meta { display: flex; align-items: center; gap: 9px; }
.branch { color: var(--text-strong, #172033); font-weight: 650; }
.digest, .snapshot-time { color: var(--text-muted, #748096); font-size: 12px; }
</style>
