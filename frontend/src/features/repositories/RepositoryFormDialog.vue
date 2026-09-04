<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { repositoryCredentialsApi } from '@/api/repositoryCredentials';
import type { RepositoryCredential } from '@/api/repositoryCredentials';
import RepositoryCredentialManagerDialog from './RepositoryCredentialManagerDialog.vue';

const repositorySourceOptions = [
  { value: 'GITLAB', label: 'GitLab' },
  { value: 'REMOTE_GIT', label: 'Git' },
  { value: 'ZIP', label: 'ZIP' },
  { value: 'LOCAL_GIT', label: '本地 Git' },
] as const;
type RepositorySourceType = (typeof repositorySourceOptions)[number]['value'];
const defaultRepositorySource = repositorySourceOptions[0].value;

const open = defineModel<boolean>({ required: true });
const props = withDefaults(defineProps<{ busy?: boolean }>(), { busy: false });
const emit = defineEmits<{
  submit: [payload: {
    sourceType: RepositorySourceType;
    name: string;
    path: string;
    url: string;
    branch: string;
    credentialId: string;
    file: File | null;
  }];
}>();
const form = reactive({
  sourceType: defaultRepositorySource as RepositorySourceType,
  name: '',
  path: '',
  url: '',
  branch: '',
  credentialId: '',
});
const file = shallowRef<File | null>(null);
const submitted = shallowRef(false);
const credentials = shallowRef<RepositoryCredential[]>([]);
const credentialsLoading = shallowRef(false);
const validatingCredential = shallowRef(false);
const credentialManagerOpen = shallowRef(false);
const submitLocked = computed(() => props.busy || submitted.value);

watch(open, value => {
  if (value) {
    Object.assign(form, { sourceType: defaultRepositorySource, name: '', path: '', url: '', branch: '', credentialId: '' });
    file.value = null;
    submitted.value = false;
    void loadCredentials();
  }
});
watch(() => props.busy, busy => {
  if (!busy) submitted.value = false;
});

function choose(upload: { raw?: File }) {
  file.value = upload.raw ?? null;
}

async function loadCredentials() {
  credentialsLoading.value = true;
  try { credentials.value = await repositoryCredentialsApi.list(); }
  catch { credentials.value = []; }
  finally { credentialsLoading.value = false; }
}

async function validateCredential() {
  if (!form.credentialId || !form.url.trim()) { ElMessage.warning('请先填写仓库地址并选择凭据'); return; }
  validatingCredential.value = true;
  try {
    const updated = await repositoryCredentialsApi.validate(form.credentialId, form.url);
    credentials.value = credentials.value.map(item => item.id === updated.id ? updated : item);
    ElMessage.success('凭据验证成功');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '凭据验证失败'); }
  finally { validatingCredential.value = false; }
}

async function credentialSelected(credential: RepositoryCredential) {
  await loadCredentials();
  form.credentialId = credential.id;
}

function submit() {
  if (submitLocked.value || !form.name.trim()) return;
  submitted.value = true;
  emit('submit', { ...form, name: form.name.trim(), file: file.value });
}
</script>

<template>
  <el-dialog
    v-model="open"
    title="接入代码仓库"
    width="560"
    :close-on-click-modal="!submitLocked"
    :close-on-press-escape="!submitLocked"
    :show-close="!submitLocked"
  >
    <el-form label-position="top" :disabled="submitLocked">
      <el-form-item label="来源类型">
        <el-radio-group v-model="form.sourceType">
          <el-radio-button
            v-for="option in repositorySourceOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="仓库名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
      <el-form-item v-if="form.sourceType === 'LOCAL_GIT'" label="服务端本地 Git 路径" required>
        <el-input v-model="form.path" placeholder="C:\workspace\project" />
      </el-form-item>
      <template v-else-if="form.sourceType !== 'ZIP'">
        <el-form-item label="HTTPS Git 地址" required>
          <el-input v-model="form.url" placeholder="https://git.example.com/group/project.git" />
        </el-form-item>
        <el-form-item label="分支（留空使用默认分支）"><el-input v-model="form.branch" /></el-form-item>
        <el-form-item label="访问凭据（公开仓库可不选）">
          <div class="credential-row">
            <el-select v-model="form.credentialId" clearable :loading="credentialsLoading" placeholder="选择 Git/GitLab 凭据">
              <el-option v-for="credential in credentials" :key="credential.id" :value="credential.id"
                :label="`${credential.displayName} · ${credential.serverUrl} · ${credential.maskedValue}`"
                :disabled="credential.status !== 'ACTIVE'" />
            </el-select>
            <el-button @click="credentialManagerOpen = true">管理凭据</el-button>
            <el-button :disabled="!form.credentialId || !form.url.trim()" :loading="validatingCredential" @click="validateCredential">检测</el-button>
          </div>
        </el-form-item>
        <el-alert
          title="私有仓库请选择加密凭据；用户名或访问令牌不允许嵌入仓库地址。"
          type="info"
          :closable="false"
        />
      </template>
      <el-form-item v-else label="ZIP 文件" required>
        <el-upload :auto-upload="false" :limit="1" accept=".zip,application/zip" :on-change="choose">
          <el-button>选择 ZIP</el-button>
        </el-upload>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitLocked" @click="open = false">取消</el-button>
      <el-button type="primary" :loading="submitLocked" :disabled="submitLocked" @click="submit">
        {{ submitLocked ? '验证并导入中…' : '验证并导入' }}
      </el-button>
    </template>
    <RepositoryCredentialManagerDialog v-model="credentialManagerOpen" :repository-url="form.url"
      :preferred-type="form.sourceType === 'GITLAB' ? 'GITLAB_PAT' : 'GIT_HTTP_TOKEN'"
      @selected="credentialSelected" />
  </el-dialog>
</template>

<style scoped>
.credential-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:8px;width:100%}
@media(max-width:640px){.credential-row{grid-template-columns:1fr 1fr}.credential-row .el-select{grid-column:1/-1}}
</style>
