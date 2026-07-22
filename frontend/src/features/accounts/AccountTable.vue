<script setup lang="ts">
import type { AccountSummary } from '@/types/security';

defineProps<{
  rows: AccountSummary[];
  currentAccountId?: string;
}>();

const emit = defineEmits<{
  toggle: [account: AccountSummary];
  reset: [account: AccountSummary];
  unlock: [account: AccountSummary];
  audit: [account: AccountSummary];
}>();

const labels: Record<string, string> = {
  ENABLED: '正常',
  DISABLED: '已停用',
  LOCKED: '已锁定',
  PASSWORD_CHANGE_REQUIRED: '待修改密码',
};
</script>

<template>
  <el-table :data="rows">
    <el-table-column label="用户" min-width="170">
      <template #default="{ row }">
        <div class="primary-cell">
          <b>{{ row.displayName }}</b>
          <span class="mono">{{ row.username }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="角色" width="110">
      <template #default="{ row }">
        <el-tag effect="plain">{{ row.role === 'SUPER_ADMIN' ? '管理员' : '普通用户' }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="130">
      <template #default="{ row }">
        <el-tag effect="plain" :type="row.status === 'ENABLED' ? 'success' : 'warning'">
          {{ labels[row.status] }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="repositoryPermissionCount" label="被授权仓库" width="100" />
    <el-table-column label="最近登录" min-width="165">
      <template #default="{ row }">
        {{ row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString() : '—' }}
      </template>
    </el-table-column>
    <el-table-column label="操作" width="310" fixed="right">
      <template #default="{ row }">
        <div class="account-actions">
          <el-button class="action-button" link type="primary" @click="emit('audit', row)">查看审计</el-button>
          <el-button
            v-if="row.status === 'LOCKED'"
            class="action-button"
            link
            type="primary"
            @click="emit('unlock', row)"
          >
            解锁账号
          </el-button>
          <el-button class="action-button" link type="primary" @click="emit('reset', row)">重置密码</el-button>
          <el-button
            class="action-button"
            link
            :type="row.status === 'DISABLED' ? 'success' : 'danger'"
            :disabled="row.id === currentAccountId"
            @click="emit('toggle', row)"
          >
            {{ row.status === 'DISABLED' ? '启用账号' : '停用账号' }}
          </el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.account-actions {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: 14px;
  white-space: nowrap;
}

.account-actions > .action-button + .action-button {
  margin-left: 0;
}

.action-button {
  height: 32px;
  padding: 0;
  line-height: 32px;
}
</style>