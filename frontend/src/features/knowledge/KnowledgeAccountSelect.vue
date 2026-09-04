<script setup lang="ts">
import type { RepositoryMember } from '@/api/repositoryGovernance';

withDefaults(defineProps<{
  members: RepositoryMember[];
  multiple?: boolean;
  loading?: boolean;
  placeholder?: string;
}>(), {
  multiple: false,
  loading: false,
  placeholder: '选择仓库成员',
});

const model = defineModel<string | string[] | null>({ required: true });

function relationshipLabel(relationship: RepositoryMember['relationship']) {
  return { OWNER: '所有者', READ: '只读', MAINTAIN: '维护', MANAGE: '管理' }[relationship];
}
</script>

<template>
  <el-select v-model="model" class="account-select" :multiple="multiple" :loading="loading"
    :placeholder="placeholder" filterable clearable collapse-tags collapse-tags-tooltip>
    <el-option v-for="member in members" :key="member.accountId" :value="member.accountId"
      :label="`${member.displayName}（${member.username}）`" :disabled="!member.enabled">
      <div class="account-option"><span><b>{{ member.displayName }}</b><small>@{{ member.username }}</small></span>
        <em>{{ relationshipLabel(member.relationship) }}</em></div>
    </el-option>
  </el-select>
</template>

<style scoped>
.account-select { width: 100%; }
.account-option { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.account-option > span { display: flex; min-width: 0; align-items: baseline; gap: 8px; }
.account-option b { overflow: hidden; text-overflow: ellipsis; }
.account-option small, .account-option em { color: var(--el-text-color-secondary); font-size: 12px; font-style: normal; }
</style>
