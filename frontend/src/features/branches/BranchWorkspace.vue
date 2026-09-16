<script setup lang="ts">
import { shallowRef } from 'vue';
import { Plus, RefreshCw } from 'lucide-vue-next';
import type { BranchCodeOperation } from '@/api/branches';
import BranchListTable from './BranchListTable.vue';
import BranchDetailsDialog from './BranchDetailsDialog.vue';
import BranchTrackDialog from './BranchTrackDialog.vue';
import { useBranchManagement } from './useBranchManagement';
const props = withDefaults(defineProps<{ repositoryId: string; canMaintain: boolean; canManage?: boolean; remoteSource?: boolean; showBranchList?: boolean; readingBranchId?: string | null; initialBranchId?: string; defaultBranch?: string | null; readingBusy?: boolean }>(), { remoteSource: false, canManage: false, showBranchList: true, readingBusy: false });
const emit = defineEmits<{ changed: []; read: [branchId: string, target?: 'search' | 'atlas'] }>();
const detailsOpen = shallowRef(false), trackOpen = shallowRef(false);
const detailTab = shallowRef<'status' | 'tasks'>('status');
const management = useBranchManagement({ repositoryId: () => props.repositoryId, canMaintain: () => props.canMaintain, canManage: () => props.canManage, remoteSource: () => props.remoteSource, initialBranchId: () => props.initialBranchId, changed: () => emit('changed'), externalBusy: () => props.readingBusy });
const { branches, remoteBranches, selectedId, selected, context, loading, busy, discovering, error, indexes, preparation, selectedStatus, selectedJobs, trackedNames, reload, select, operate, prepareVectors, discoverRemote, trackBranch, archive, restoreBranch, copyCoordinates } = management;
async function openDetails(branchId: string, tab: 'status' | 'tasks' = 'status') { detailTab.value = tab; detailsOpen.value = true; await select(branchId); }
function openTrack() { trackOpen.value = true; if (props.remoteSource && !remoteBranches.value.length) void discoverRemote(); }
async function track(name: string) { if (await trackBranch(name)) trackOpen.value = false; }
function operateSelected(kind: BranchCodeOperation) { void operate(selectedId.value, kind); }
</script>
<template>
  <section class="branch-workspace" aria-label="分支管理">
    <header class="branch-toolbar"><div><h3>分支</h3><p>每个分支独立同步和构建，切换阅读不会修改默认分支。</p></div><div class="branch-toolbar-actions"><el-button :loading="loading" :disabled="busy" @click="reload"><RefreshCw :size="14" />刷新列表</el-button><el-button v-if="canMaintain" type="primary" :disabled="busy" @click="openTrack"><Plus :size="15" />添加跟踪分支</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="indexes.error.value || preparation.error.value" :title="indexes.error.value || preparation.error.value" type="warning" :closable="false" />
    <div v-loading="loading" class="branch-table-region"><BranchListTable :branches="branches" :selected-id="selectedId" :reading-branch-id="readingBranchId" :default-branch="defaultBranch" :disabled="busy" :can-manage="canManage" :can-maintain="canMaintain" :indexes="indexes.statuses.value" :jobs="preparation.jobs.value" @select="openDetails" @read="(branchId, target) => emit('read', branchId, target)" @operate="operate" @vectors="prepareVectors" @archive="archive" @restore="restoreBranch" /></div>
    <BranchDetailsDialog v-model="detailsOpen" v-model:tab="detailTab" :repository-id="repositoryId" :branch="selected" :context="context" :status="selectedStatus" :jobs="selectedJobs" :disabled="busy" :can-maintain="canMaintain" :error="error" @operate="operateSelected" @vectors="prepareVectors(selectedId)" @refresh="select(selectedId)" @read="target => emit('read', selectedId, target)" @copy="copyCoordinates" />
    <BranchTrackDialog v-if="canMaintain" v-model="trackOpen" :remote-source="remoteSource" :branches="remoteBranches" :tracked-names="trackedNames" :discovering="discovering" :disabled="busy" :error="error" @discover="discoverRemote" @track="track" />
  </section>
</template>
<style scoped>
.branch-workspace { display: grid; align-content: start; gap: 20px; padding: 26px 30px 30px; min-width: 0; }
.branch-toolbar { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 18px; }
.branch-toolbar h3 { margin: 0 0 7px; font-size: 17px; font-weight: 600; color: var(--app-text-primary); }
.branch-toolbar p { margin: 0; color: var(--app-text-muted); font-size: 12px; line-height: 1.8; }
.branch-toolbar-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }.branch-toolbar-actions :deep(.el-button + .el-button) { margin-left: 0; }.branch-toolbar-actions :deep(.el-button > span) { gap: 6px; }
.branch-table-region { min-width: 0; min-height: 130px; }
@media (max-width: 760px) { .branch-workspace { padding: 22px 18px; }.branch-toolbar-actions { width: 100%; } }
</style>
