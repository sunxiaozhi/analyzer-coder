<script setup lang="ts">
import { ref, watch } from 'vue';
import { branchesApi, type BranchScope, type RepositoryBranch } from '@/api/branches';
import type { KnowledgeCard } from '@/api/intelligence';

const props = defineProps<{ modelValue: boolean; repositoryId: string; card: KnowledgeCard | null }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>();
const branches = ref<RepositoryBranch[]>([]);
const scope = ref<BranchScope | null>(null);
const busy = ref(false);
const error = ref('');
let sequence = 0;
watch(() => [props.modelValue, props.repositoryId, props.card?.id], async () => {
  const version = ++sequence;
  scope.value = null; error.value = '';
  if (!props.modelValue || !props.repositoryId || !props.card) return;
  const cardId = props.card.id;
  busy.value = true;
  try {
    const [rows, scopes] = await Promise.all([branchesApi.list(props.repositoryId), branchesApi.scopes(props.repositoryId)]);
    if (version !== sequence) return;
    branches.value = rows;
    scope.value = scopes.find(item => item.cardId === cardId) ?? null;
    if (!scope.value) error.value = '该知识尚未初始化分支范围，请刷新后重试';
  } catch (cause) { if (version === sequence) error.value = cause instanceof Error ? cause.message : '加载失败'; }
  finally { if (version === sequence) busy.value = false; }
}, { immediate: true });
async function save() {
  if (!scope.value || busy.value) return;
  const version = sequence;
  busy.value = true; error.value = '';
  try {
    await branchesApi.scope(props.repositoryId, { ...scope.value, branchIds: scope.value.mode === 'ALL_BRANCHES' ? [] : scope.value.branchIds });
    if (version !== sequence) return;
    emit('saved'); emit('update:modelValue', false);
  } catch (cause) { if (version === sequence) error.value = cause instanceof Error ? cause.message : '保存失败'; }
  finally { if (version === sequence) busy.value = false; }
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="知识适用分支" width="min(520px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy" @update:model-value="emit('update:modelValue', $event)">
    <p>{{ card?.title }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="scope" label-position="top" :disabled="busy">
      <el-form-item label="共享范围">
        <el-radio-group v-model="scope.mode">
          <el-radio value="SELECTED_BRANCHES">指定分支</el-radio>
          <el-radio value="ALL_BRANCHES">所有分支共享（含未来分支）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="scope.mode === 'SELECTED_BRANCHES'" label="适用分支（仅当前分支时，选择一个即可）">
        <el-select v-model="scope.branchIds" multiple filterable placeholder="选择适用分支" :multiple-limit="30" style="width: 100%">
          <el-option v-for="branch in branches" :key="branch.id" :label="branch.name" :value="branch.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <p class="scope-note">修改范围会生成新修订，保留代码引用与附件；各分支的验证状态不会继承。</p>
    <template #footer>
      <el-button :disabled="busy" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!scope || (scope.mode === 'SELECTED_BRANCHES' && !scope.branchIds.length)" @click="save">保存范围</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.scope-note { color: #68778a; font-size: 12px; line-height: 1.7; }
</style>
