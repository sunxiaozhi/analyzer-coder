<script setup lang="ts">
import { computed } from 'vue';
import type { BranchCodeOperation, BranchContext, BranchIndexStatus, BranchPreparationJob, RepositoryBranch } from '@/api/branches';
import BranchIndexPanel from './BranchIndexPanel.vue';
import BranchTaskHistory from './BranchTaskHistory.vue';
const props = defineProps<{ repositoryId: string; branch: RepositoryBranch | undefined; context: BranchContext | null; status?: BranchIndexStatus; jobs: BranchPreparationJob[]; disabled: boolean; canMaintain: boolean; error: string }>();
const open = defineModel<boolean>({ required: true });
const tab = defineModel<'status' | 'tasks'>('tab', { required: true });
const emit = defineEmits<{ operate: [kind: BranchCodeOperation]; vectors: []; refresh: []; read: [target: 'search' | 'atlas']; copy: [] }>();
const revision = computed(() => props.jobs.map(job => job.id + ':' + job.status + ':' + job.stage).join('|'));
</script>
<template>
  <el-dialog v-model="open" :title="branch ? '分支详情 · ' + branch.name : '分支详情'" width="min(940px, 94vw)" destroy-on-close class="branch-details-dialog">
    <template v-if="branch">
      <div class="detail-tabs" role="tablist" aria-label="分支详情分类"><button role="tab" :aria-selected="tab === 'status'" @click="tab = 'status'">状态与操作</button><button role="tab" :aria-selected="tab === 'tasks'" @click="tab = 'tasks'">任务记录</button></div>
      <el-alert v-if="error || branch.error" :title="error || branch.error || ''" type="error" :closable="false" class="detail-error" />
      <BranchIndexPanel v-if="tab === 'status'" :branch="branch" :context="context" :status="status" :jobs="jobs" :disabled="disabled" :can-maintain="canMaintain" @operate="kind => emit('operate', kind)" @vectors="emit('vectors')" @refresh="emit('refresh')" @read="target => emit('read', target)" @copy="emit('copy')" />
      <BranchTaskHistory v-else :key="branch.id" :repository-id="repositoryId" :branch-id="branch.id" :revision="revision" />
    </template>
    <template #footer><el-button @click="open = false">关闭</el-button></template>
  </el-dialog>
</template>
<style scoped>
.detail-tabs { display: flex; gap: 24px; margin: 0 0 24px; border-bottom: 1px solid var(--app-border); }
.detail-tabs button { padding: 8px 2px 13px; color: var(--app-text-muted); border: 0; border-bottom: 2px solid transparent; background: transparent; font-size: 14px; }
.detail-tabs button[aria-selected='true'] { border-color: var(--app-color-action); color: var(--app-color-action); font-weight: 600; }
.detail-error { margin-bottom: 16px; }
</style>
