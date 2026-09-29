<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import type { KnowledgeBranchScope } from '@/api/intelligence';
import type { RepositoryBranch } from '@/api/branches';
const props = defineProps<{
  modelValue?: KnowledgeBranchScope;
  currentBranchId?: string | null;
  branchName?: string | null;
  branches: RepositoryBranch[];
}>();
const emit = defineEmits<{ 'update:modelValue': [value: KnowledgeBranchScope] }>();
const choice = shallowRef<'CURRENT' | 'ALL_BRANCHES' | 'SELECTED_BRANCHES'>('CURRENT');
watch(() => props.modelValue, value => {
  if (value?.mode === 'ALL_BRANCHES') choice.value = 'ALL_BRANCHES';
  else if (choice.value !== 'SELECTED_BRANCHES') choice.value = value?.branchIds.length === 1
    && value.branchIds[0] === props.currentBranchId ? 'CURRENT' : 'SELECTED_BRANCHES';
}, { immediate: true });
const available = computed(() => props.branches.filter(branch => branch.trackingStatus === 'ACTIVE'
  || props.modelValue?.branchIds.includes(branch.id)));
function selectChoice(value: 'CURRENT' | 'ALL_BRANCHES' | 'SELECTED_BRANCHES') {
  choice.value = value;
  emit('update:modelValue', value === 'ALL_BRANCHES' ? { mode: value, branchIds: [] }
    : { mode: 'SELECTED_BRANCHES', branchIds: value === 'CURRENT' && props.currentBranchId
      ? [props.currentBranchId] : props.modelValue?.branchIds.length ? [...props.modelValue.branchIds] : [] });
}
</script>

<template>
  <section class="branch-scope-section">
    <b>知识共享范围</b>
    <el-radio-group :model-value="choice" aria-label="知识共享范围" @update:model-value="selectChoice($event as typeof choice)">
      <el-radio value="CURRENT">仅当前分支{{ branchName ? ` · ${branchName}` : '' }}</el-radio>
      <el-radio value="ALL_BRANCHES">项目共享（所有分支）</el-radio>
      <el-radio value="SELECTED_BRANCHES">指定分支共享</el-radio>
    </el-radio-group>
    <el-select v-if="choice === 'SELECTED_BRANCHES'" :model-value="modelValue?.branchIds ?? []" multiple filterable
      aria-label="共享分支" placeholder="选择一个或多个分支" :multiple-limit="30"
      @update:model-value="emit('update:modelValue', { mode: 'SELECTED_BRANCHES', branchIds: $event })">
      <el-option v-for="branch in available" :key="branch.id" :value="branch.id"
        :label="branch.name + (branch.trackingStatus === 'ARCHIVED' ? '（已归档）' : '')" />
    </el-select>
    <small v-if="choice === 'ALL_BRANCHES'">所有分支共用这一张卡片，以后新增的分支也会包含它。修改正文后，各分支看到同一份新修订。</small>
    <small v-else-if="choice === 'SELECTED_BRANCHES'">只在选中的分支展示和检索，后续可以调整。</small>
    <small v-else>只在当前分支展示和检索。</small>
    <small>通用说明发布后即可检索；关联代码或约束类知识，需要在各使用分支确认适用性。验证结论互不影响。</small>
  </section>
</template>

<style scoped>
.branch-scope-section { display: grid; gap: 8px; padding: 14px; border: 1px solid #cdddea; border-left: 3px solid #2f6f94; border-radius: 5px; background: #f7fafc; }
.branch-scope-section b { color: #31475a; font-size: 14px; }
.branch-scope-section small { color: #68798a; font-size: 12px; line-height: 1.6; }
</style>
