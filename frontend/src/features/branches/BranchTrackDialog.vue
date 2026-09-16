<script setup lang="ts">
import { shallowRef } from 'vue';
import type { RemoteBranch } from '@/api/branches';
import RemoteBranchDiscoveryPanel from './RemoteBranchDiscoveryPanel.vue';
defineProps<{ remoteSource: boolean; branches: RemoteBranch[]; trackedNames: string[]; discovering: boolean; disabled: boolean; error: string }>();
const open = defineModel<boolean>({ required: true });
const name = shallowRef('');
const emit = defineEmits<{ discover: []; track: [name: string] }>();
</script>
<template>
  <el-dialog v-model="open" title="添加跟踪分支" width="min(720px, 94vw)" destroy-on-close>
    <p class="tracking-intro">选择需要管理的已有分支，添加后可在列表中同步代码或一键准备。</p>
    <RemoteBranchDiscoveryPanel v-if="remoteSource" :branches="branches" :tracked-names="trackedNames" :loading="discovering" :disabled="disabled" @refresh="emit('discover')" @track="branch => emit('track', branch)" />
    <form class="track-form" @submit.prevent="emit('track', name)"><label for="tracking-branch-name">{{ remoteSource ? '也可以输入已有分支名称' : '已有分支名称' }}</label><div><el-input id="tracking-branch-name" v-model="name" placeholder="例如 main 或 release/1.0" aria-label="新增分支名称" :maxlength="200" :disabled="disabled" /><el-button type="primary" native-type="submit" :disabled="disabled || !name.trim()">添加跟踪</el-button></div></form>
    <p class="tracking-note">此操作只添加跟踪记录，不创建或切换源仓库中的 Git 分支。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer><el-button @click="open = false">关闭</el-button></template>
  </el-dialog>
</template>
<style scoped>
.tracking-intro { margin: 0 0 22px; color: var(--app-text-regular); font-size: 13px; line-height: 1.8; }
.track-form { display: grid; gap: 10px; margin-top: 24px; }.track-form label { font-size: 13px; color: var(--app-text-regular); }.track-form > div { display: flex; align-items: center; gap: 12px; }.track-form .el-input { flex: 1; min-width: 0; }
.tracking-note { color: var(--app-text-muted); font-size: 12px; line-height: 1.8; margin: 14px 0 0; }
</style>
