<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  repositoryCredentialsApi,
  type RepositoryCredential,
  type RepositoryCredentialBindingStatus,
  type RepositoryCredentialType,
} from '@/api/repositoryCredentials';
import type { RepositorySourceType } from '@/types/api';
import RepositoryCredentialManagerDialog from './RepositoryCredentialManagerDialog.vue';

const props = defineProps<{
  repositoryId: string;
  sourceType: RepositorySourceType;
}>();

const status = shallowRef<RepositoryCredentialBindingStatus | null>(null);
const loading = shallowRef(false);
const saving = shallowRef(false);
const managerOpen = shallowRef(false);
const preferredType = computed<RepositoryCredentialType>(() => (
  props.sourceType === 'GITLAB' ? 'GITLAB_PAT' : 'GIT_HTTP_TOKEN'
));

watch(() => props.repositoryId, () => void load(), { immediate: true });

async function load() {
  if (!props.repositoryId) return;
  loading.value = true;
  try {
    status.value = await repositoryCredentialsApi.repositoryBinding(props.repositoryId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '仓库凭据状态加载失败');
  } finally {
    loading.value = false;
  }
}

async function bind(credential: RepositoryCredential) {
  saving.value = true;
  try {
    status.value = await repositoryCredentialsApi.bindRepository(props.repositoryId, credential.id);
    ElMessage.success('凭据验证通过并已绑定');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '凭据绑定失败');
  } finally {
    saving.value = false;
  }
}

async function unbind() {
  await ElMessageBox.confirm('解绑后，远程同步和拉取请求 / 合并请求审查将不可用。', '解绑访问凭据', { type: 'warning' });
  saving.value = true;
  try {
    await repositoryCredentialsApi.unbindRepository(props.repositoryId);
    if (status.value) status.value = { ...status.value, credential: null };
    ElMessage.success('仓库凭据已解绑');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '凭据解绑失败');
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="binding-panel" v-loading="loading">
    <header>
      <div>
        <strong>远程访问凭据</strong>
        <p>用于远程同步以及拉取请求 / 合并请求的读取和评论；变更后立即生效。</p>
      </div>
      <el-tag v-if="status?.credential" type="success">已绑定</el-tag>
      <el-tag v-else type="warning">未绑定</el-tag>
    </header>

    <dl v-if="status">
      <div><dt>远程仓库</dt><dd>{{ status.remoteUrl }}</dd></div>
      <div>
        <dt>当前凭据</dt>
        <dd v-if="status.credential">
          {{ status.credential.displayName }} · {{ status.credential.maskedValue }}
        </dd>
        <dd v-else>尚未配置，相关远程功能暂不可用</dd>
      </div>
    </dl>

    <footer>
      <el-button :loading="saving" @click="managerOpen = true">
        {{ status?.credential ? '更换或管理凭据' : '选择或新建凭据' }}
      </el-button>
      <el-button v-if="status?.credential" :loading="saving" type="danger" plain @click="unbind">解绑</el-button>
    </footer>

    <RepositoryCredentialManagerDialog
      v-model="managerOpen"
      :repository-url="status?.remoteUrl ?? ''"
      :preferred-type="preferredType"
      @selected="bind"
    />
  </section>
</template>

<style scoped>
.binding-panel { display: grid; gap: 12px; margin-top: 18px; padding: 14px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-fill-color-lighter); }
.binding-panel header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.binding-panel header div { display: grid; gap: 3px; }
.binding-panel p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.5; }
.binding-panel dl { display: grid; gap: 7px; margin: 0; }
.binding-panel dl div { display: grid; grid-template-columns: 84px minmax(0, 1fr); gap: 8px; font-size: 13px; }
.binding-panel dt { color: var(--el-text-color-secondary); }
.binding-panel dd { overflow-wrap: anywhere; margin: 0; color: var(--el-text-color-primary); }
.binding-panel footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
