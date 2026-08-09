<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue';
import RepositoryVersionCell from '@/features/repositories/RepositoryVersionCell.vue';
import type { Repository } from '@/types/api';
import { relationshipLabel } from '@/utils/displayLabels';

defineProps<{
  rows: readonly Repository[];
  loading: boolean;
  rescanningId: string | null;
  buildingId: string | null;
  preparingId: string | null;
}>();

const emit = defineEmits<{
  edit: [repository: Repository];
  index: [repositoryId: string];
  rescan: [repositoryId: string];
  remove: [repositoryId: string, name: string];
  govern: [repository: Repository];
  codegraph: [repository: Repository];
  prepare: [repository: Repository];
}>();

function sourceLabel(source: Repository['sourceType']) {
  return { LOCAL_GIT: '本地 Git', REMOTE_GIT: '远程 Git', GITLAB: 'GitLab', ZIP: 'ZIP' }[source];
}

function repositoryStatusLabel(status: string) {
  return {
    PREPARING: '仓库准备中',
    READY: '仓库就绪',
    AUTH_ERROR: '仓库认证异常',
    DELETING: '仓库删除中',
    DELETED: '仓库已删除',
    FAILED: '仓库异常',
  }[status] ?? `仓库状态：${status}`;
}

function canEditRepository(row: Repository) {
  return row.capabilities.canEditRepository
    ?? row.capabilities.canConfigure
    ?? ['OWNER', 'SUPER_ADMIN', 'MANAGE'].includes(row.relationship);
}

function command(action: string, row: Repository) {
  if (action === 'govern') emit('govern', row);
  else if (action === 'codegraph') emit('codegraph', row);
  else if (action === 'remove') emit('remove', row.id, row.name);
}
</script>

<template>
  <el-table v-loading="loading" :data="rows" empty-text="尚未接入仓库" row-key="id">
    <el-table-column label="仓库" min-width="220">
      <template #default="{ row }">
        <div class="primary-cell">
          <b>{{ row.name }}</b>
          <span>{{ sourceLabel(row.sourceType) }} · {{ row.ownerDisplayName }} · {{ relationshipLabel(row.relationship) }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="当前版本" min-width="250">
      <template #default="{ row }"><RepositoryVersionCell :repository="row" /></template>
    </el-table-column>
    <el-table-column prop="path" label="服务端路径" min-width="260">
      <template #default="{ row }"><span class="mono">{{ row.path }}</span></template>
    </el-table-column>
    <el-table-column label="产物状态" width="150">
      <template #default="{ row }">
        <div class="artifact-rail">
          <el-tag effect="plain" :type="row.codeGraphDetected ? 'success' : 'info'">
            CodeGraph {{ row.codeGraphDetected ? '已发布' : '未构建' }}
          </el-tag>
          <small>{{ repositoryStatusLabel(row.repositoryStatus) }}</small>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="410" fixed="right">
      <template #default="{ row }">
        <div class="repository-actions">
          <el-button
            class="action-button"
            link
            type="primary"
            :loading="preparingId === row.id"
            @click="emit('prepare', row)"
          >
            准备 / 画像
          </el-button>
          <el-button v-if="canEditRepository(row)" class="action-button" link type="primary" @click="emit('edit', row)">编辑</el-button>
          <el-button
            v-if="row.capabilities.canUpdate"
            class="action-button"
            link
            type="primary"
            :loading="rescanningId === row.id"
            @click="emit('rescan', row.id)"
          >
            同步
          </el-button>
          <el-button
            v-if="row.capabilities.canIndex"
            class="action-button"
            link
            type="primary"
            @click="emit('index', row.id)"
          >
            内容索引
          </el-button>
          <el-dropdown class="action-dropdown" trigger="click" @command="command($event, row)">
            <el-button class="action-button more-trigger" link type="primary" aria-label="打开更多仓库操作">
              <span>更多</span>
              <el-icon class="dropdown-indicator"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-if="row.capabilities.canBuildCodeGraph"
                  command="codegraph"
                  :disabled="buildingId === row.id"
                >
                  {{ row.codeGraphDetected ? '重新构建 CodeGraph' : '构建 CodeGraph' }}
                </el-dropdown-item>
                <el-dropdown-item v-if="row.capabilities.canGrant" command="govern">成员与所有权</el-dropdown-item>
                <el-dropdown-item v-if="row.capabilities.canDelete" command="remove" divided>删除仓库</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.repository-actions {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: 14px;
  white-space: nowrap;
}

.repository-actions > .action-button + .action-button {
  margin-left: 0;
}

.action-button {
  height: 32px;
  padding: 0;
  line-height: 32px;
}

.action-dropdown {
  display: inline-flex;
  height: 32px;
  align-items: center;
  vertical-align: middle;
}

.more-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.dropdown-indicator {
  width: 12px;
  height: 12px;
  font-size: 12px;
}
</style>
