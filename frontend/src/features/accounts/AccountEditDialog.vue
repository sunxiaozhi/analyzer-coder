<script setup lang="ts">
import { reactive, watch } from 'vue';
import type { AccountRole, AccountSummary } from '@/types/security';

const props = defineProps<{ modelValue: boolean; account: AccountSummary | null; busy: boolean }>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [value: { displayName: string; role: AccountRole; version: number }];
}>();
const form = reactive<{ displayName: string; role: AccountRole; version: number }>({
  displayName: '', role: 'NORMAL', version: 1,
});

watch(() => [props.modelValue, props.account] as const, () => {
  if (!props.modelValue || !props.account) return;
  form.displayName = props.account.displayName;
  form.role = props.account.role;
  form.version = props.account.version;
}, { immediate: true });
</script>

<template>
  <el-dialog :model-value="modelValue" title="编辑账号" width="480"
             @update:model-value="emit('update:modelValue', $event)">
    <el-alert title="账号名、状态和密码由独立操作维护，此处只修改资料与角色。" type="info"
              :closable="false" show-icon />
    <el-form label-position="top" class="edit-form">
      <el-form-item label="登录账号"><el-input :model-value="account?.username" disabled /></el-form-item>
      <el-form-item label="显示名称" required>
        <el-input v-model="form.displayName" maxlength="50" show-word-limit />
      </el-form-item>
      <el-form-item label="角色" required>
        <el-select v-model="form.role" style="width:100%">
          <el-option label="普通账号" value="NORMAL" />
          <el-option label="超级管理员" value="SUPER_ADMIN" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!form.displayName.trim()"
                 @click="emit('submit', { ...form, displayName: form.displayName.trim() })">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>.edit-form{margin-top:18px}</style>
