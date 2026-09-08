<script setup lang="ts">
import type { AccessToken } from '@/api/accessTokens';
defineProps<{ tokens: AccessToken[]; busy: boolean }>();
const emit = defineEmits<{ revoke: [id: string] }>();
function status(token: AccessToken) { return token.revokedAt ? '已撤销' : Date.parse(token.expiresAt) <= Date.now() ? '已过期' : '有效'; }
function date(value: string | null) { return value ? new Date(value).toLocaleString() : '尚未使用'; }
</script>
<template>
  <el-table :data="tokens" empty-text="该账户尚未创建访问令牌">
    <el-table-column prop="name" label="名称" min-width="140" />
    <el-table-column label="令牌标识" min-width="135"><template #default="{ row }"><code>{{ row.prefix }}…</code></template></el-table-column>
    <el-table-column label="状态" width="90"><template #default="{ row }">{{ status(row) }}</template></el-table-column>
    <el-table-column label="到期时间" min-width="170"><template #default="{ row }">{{ date(row.expiresAt) }}</template></el-table-column>
    <el-table-column label="最近使用" min-width="170"><template #default="{ row }">{{ date(row.lastUsedAt) }}</template></el-table-column>
    <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="danger" :disabled="busy || Boolean(row.revokedAt)" @click="emit('revoke', row.id)">撤销</el-button></template></el-table-column>
  </el-table>
</template>
