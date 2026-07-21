<script setup lang="ts">
import RepositoryVersionCell from '@/features/repositories/RepositoryVersionCell.vue';
import type { Repository } from '@/types/api';

defineProps<{
  rows: readonly Repository[];
  loading: boolean;
  rescanningId: string | null;
}>();

const emit = defineEmits<{
  index: [repositoryId: string];
  rescan: [repositoryId: string];
  remove: [repositoryId: string, name: string];
}>();
</script>

<template>
  <el-table v-loading="loading" :data="rows" empty-text="尚未登记本地 Git 仓库" row-key="id">
    <el-table-column label="仓库" min-width="190">
      <template #default="{ row }">
        <div class="primary-cell"><b>{{ row.name }}</b><span>本地 Git</span></div>
      </template>
    </el-table-column>
    <el-table-column label="当前版本" min-width="250">
      <template #default="{ row }"><RepositoryVersionCell :repository="row" /></template>
    </el-table-column>
    <el-table-column prop="path" label="服务端路径" min-width="300">
      <template #default="{ row }"><span class="mono">{{ row.path }}</span></template>
    </el-table-column>
    <el-table-column label="CodeGraph" width="130">
      <template #default="{ row }">
        <el-tag effect="plain" :type="row.codeGraphDetected ? 'success' : 'info'">
          {{ row.codeGraphDetected ? '已检测' : '未检测' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="最后扫描" width="180">
      <template #default="{ row }">
        {{ row.lastScannedAt ? new Date(row.lastScannedAt).toLocaleString() : '—' }}
      </template>
    </el-table-column>
    <el-table-column label="操作" width="280" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" :loading="rescanningId === row.id" @click="emit('rescan', row.id)">重新扫描</el-button>
        <el-button link type="primary" @click="emit('index', row.id)">建立内容索引</el-button>
        <el-button link type="danger" @click="emit('remove', row.id, row.name)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>
