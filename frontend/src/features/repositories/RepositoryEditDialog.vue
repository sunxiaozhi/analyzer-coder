<script setup lang="ts">
import { reactive, watch } from 'vue';
import type { Repository } from '@/types/api';
import RepositoryCredentialBindingPanel from './RepositoryCredentialBindingPanel.vue';

const props = defineProps<{ modelValue: boolean; repository: Repository | null; busy: boolean }>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [value: { name: string; description: string; defaultBranch: string; version: number }];
}>();
const form = reactive({ name: '', description: '', defaultBranch: '', version: 1 });
watch(() => [props.modelValue, props.repository] as const, () => {
  if (!props.modelValue || !props.repository) return;
  Object.assign(form, {
    name: props.repository.name,
    description: props.repository.description,
    defaultBranch: props.repository.branch ?? '',
    version: props.repository.version,
  });
}, { immediate: true });
</script>

<template>
  <el-dialog :model-value="modelValue" title="编辑仓库" width="620"
             @update:model-value="emit('update:modelValue', $event)">
    <el-alert title="来源类型、服务端路径和所有者不可在此修改；远程仓库凭据可在下方单独维护。" type="info"
              :closable="false" show-icon />
    <el-form label-position="top" class="edit-form">
      <el-form-item label="仓库名称" required>
        <el-input v-model="form.name" maxlength="100" show-word-limit />
      </el-form-item>
      <el-form-item label="仓库描述">
        <el-input v-model="form.description" type="textarea" :rows="4" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="默认分支">
        <el-input v-model="form.defaultBranch" maxlength="255" placeholder="例如 main" />
      </el-form-item>
      <el-form-item label="不可修改的来源">
        <el-input :model-value="`${repository?.sourceType ?? ''} · ${repository?.path ?? ''}`" disabled />
      </el-form-item>
    </el-form>
    <RepositoryCredentialBindingPanel
      v-if="repository && ['REMOTE_GIT', 'GITLAB'].includes(repository.sourceType) && repository.capabilities.canManageCredential"
      :repository-id="repository.id"
      :source-type="repository.sourceType"
    />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!form.name.trim()"
                 @click="emit('submit', { ...form, name: form.name.trim(), description: form.description.trim(), defaultBranch: form.defaultBranch.trim() })">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>.edit-form{margin-top:18px}</style>
