<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { repositoryGovernanceApi, type GovernanceCandidate, type RepositoryMember, type RepositoryPermission } from '@/api/repositoryGovernance';
import type { Repository } from '@/types/api';
import { relationshipLabel } from '@/utils/displayLabels';

const props = defineProps<{ modelValue: boolean; repository: Repository | null }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; changed: [] }>();
const members = shallowRef<RepositoryMember[]>([]);
const candidates = shallowRef<GovernanceCandidate[]>([]);
const busy = shallowRef(false);
const ownershipVersion = shallowRef(0);
const grant = reactive<{ accountId: string; permission: RepositoryPermission }>({ accountId: '', permission: 'READ' });
const transfer = reactive<{ accountId: string; newName: string; previousOwnerPermission: RepositoryPermission | null }>({ accountId: '', newName: '', previousOwnerPermission: null });
const available = computed(() => candidates.value.filter((candidate) => !members.value.some((member) => member.accountId === candidate.id)));

async function load() {
  if (!props.repository) return;
  busy.value = true;
  try {
    [members.value, candidates.value] = await Promise.all([
      repositoryGovernanceApi.members(props.repository.id),
      repositoryGovernanceApi.candidates(props.repository.id),
    ]);
    transfer.newName = props.repository.name;
  } finally { busy.value = false; }
}

async function addMember() {
  if (!props.repository || !grant.accountId) return ElMessage.warning('请选择账号');
  const result = await repositoryGovernanceApi.grant(props.repository.id, grant.accountId, grant.permission, ownershipVersion.value);
  ownershipVersion.value = result.ownershipVersion;
  grant.accountId = '';
  emit('changed');
  await load();
}

async function changePermission(member: RepositoryMember, permission: RepositoryPermission) {
  if (!props.repository) return;
  const result = await repositoryGovernanceApi.grant(props.repository.id, member.accountId, permission, ownershipVersion.value);
  ownershipVersion.value = result.ownershipVersion;
  emit('changed');
  await load();
}

async function revoke(member: RepositoryMember) {
  if (!props.repository) return;
  await ElMessageBox.confirm(`撤销 ${member.displayName} 的仓库访问权限？`, '撤销授权', { type: 'warning' });
  const result = await repositoryGovernanceApi.revoke(props.repository.id, member.accountId, ownershipVersion.value);
  ownershipVersion.value = result.ownershipVersion;
  emit('changed');
  await load();
}

async function transferOwnership() {
  if (!props.repository || !transfer.accountId) return ElMessage.warning('请选择新所有者');
  await ElMessageBox.confirm(`将仓库所有权转移给所选账号？转移后你将立即失去治理权限。`, '确认转移所有权', { type: 'warning', confirmButtonText: '确认转移' });
  await repositoryGovernanceApi.transfer(props.repository.id, {
    newOwnerAccountId: transfer.accountId,
    newName: transfer.newName.trim() || props.repository.name,
    previousOwnerPermission: transfer.previousOwnerPermission,
    expectedOwnershipVersion: ownershipVersion.value,
  });
  emit('changed');
  emit('update:modelValue', false);
}

watch(() => [props.modelValue, props.repository?.id], () => { if (props.modelValue) { ownershipVersion.value = props.repository?.ownershipVersion ?? 0; void load(); } });
</script>

<template>
  <el-dialog :model-value="modelValue" :title="`仓库治理 · ${repository?.name ?? ''}`" width="760" @update:model-value="emit('update:modelValue', $event)">
    <el-tabs v-loading="busy">
      <el-tab-pane label="成员授权">
        <div class="toolbar">
          <el-select v-model="grant.accountId" filterable placeholder="选择启用账号" style="width: 240px">
            <el-option v-for="candidate in available" :key="candidate.id" :label="`${candidate.displayName} (${candidate.username})`" :value="candidate.id" />
          </el-select>
          <el-select v-model="grant.permission" style="width: 150px"><el-option label="只读" value="READ" /><el-option label="维护" value="MAINTAIN" /><el-option label="管理" value="MANAGE" /></el-select>
          <el-button type="primary" @click="addMember">添加成员</el-button>
        </div>
        <el-table :data="members" style="margin-top: 16px">
          <el-table-column label="账号" min-width="220"><template #default="{ row }"><div class="primary-cell"><b>{{ row.displayName }}</b><span class="mono">{{ row.username }}</span></div></template></el-table-column>
          <el-table-column label="关系" width="120"><template #default="{ row }"><el-tag effect="plain">{{ relationshipLabel(row.relationship) }}</el-tag></template></el-table-column>
          <el-table-column label="权限" width="210"><template #default="{ row }"><span v-if="row.relationship === 'OWNER'">完整治理权限</span><el-select v-else :model-value="row.permissionLevel" @change="changePermission(row, $event)"><el-option label="只读" value="READ" /><el-option label="维护" value="MAINTAIN" /><el-option label="管理" value="MANAGE" /></el-select></template></el-table-column>
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.relationship !== 'OWNER'" link type="danger" @click="revoke(row)">撤销</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="转移所有权">
        <el-alert title="转移仅适用于就绪或认证异常的仓库；目标账号下存在同名仓库时请先填写新名称。" type="warning" :closable="false" />
        <el-form label-width="130px" style="margin-top: 20px">
          <el-form-item label="新所有者"><el-select v-model="transfer.accountId" filterable style="width: 360px"><el-option v-for="candidate in candidates.filter(item => item.id !== repository?.ownerAccountId)" :key="candidate.id" :label="`${candidate.displayName} (${candidate.username})`" :value="candidate.id" /></el-select></el-form-item>
          <el-form-item label="转移后仓库名"><el-input v-model="transfer.newName" style="width: 360px" /></el-form-item>
          <el-form-item label="原所有者权限"><el-select v-model="transfer.previousOwnerPermission" clearable placeholder="不保留权限" style="width: 360px"><el-option label="只读" value="READ" /><el-option label="维护" value="MAINTAIN" /><el-option label="管理" value="MANAGE" /></el-select></el-form-item>
          <el-form-item><el-button type="danger" @click="transferOwnership">转移所有权</el-button></el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>
