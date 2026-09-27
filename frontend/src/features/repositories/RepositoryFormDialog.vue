<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { repositoryCredentialsApi } from '@/api/repositoryCredentials';
import type { RepositoryCredential } from '@/api/repositoryCredentials';
import RepositoryCredentialManagerDialog from './RepositoryCredentialManagerDialog.vue';
import type { ProjectDraft } from '@/api/projectDrafts';

const repositorySourceOptions = [
  { value: 'GITLAB', label: 'GitLab' },
  { value: 'REMOTE_GIT', label: 'Git' },
  { value: 'ZIP', label: 'ZIP' },
  { value: 'LOCAL_GIT', label: '本地 Git' },
] as const;
type RepositorySourceType = (typeof repositorySourceOptions)[number]['value'];
const defaultRepositorySource = repositorySourceOptions[0].value;

const open = defineModel<boolean>({ required: true });
const props = withDefaults(defineProps<{ busy?: boolean; initialDraft?: ProjectDraft | null }>(), { busy: false, initialDraft: null });
const emit = defineEmits<{
  submit: [payload: {
    sourceType: RepositorySourceType;
    name: string;
    description: string;
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
  description: '',
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
    const draft = props.initialDraft;
    const sourceType = repositorySourceOptions.some(item => item.value === draft?.sourceType)
      ? draft?.sourceType as RepositorySourceType
      : defaultRepositorySource;
    Object.assign(form, {
      sourceType,
      name: draft?.name ?? '',
      description: draft?.description ?? '',
      path: sourceType === 'LOCAL_GIT' ? draft?.sourceLocation ?? '' : '',
      url: sourceType === 'REMOTE_GIT' || sourceType === 'GITLAB' ? draft?.sourceLocation ?? '' : '',
      branch: '',
      credentialId: draft?.credentialId ?? '',
    });
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
function clearFile() { file.value = null; }

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
  if (submitLocked.value) return;
  if (!form.name.trim()) { ElMessage.warning('请先填写项目名称'); return; }
  if (form.sourceType === 'LOCAL_GIT' && !form.path.trim()) {
    ElMessage.warning('请填写服务端本地 Git 路径'); return;
  }
  if (form.sourceType === 'ZIP' && !file.value) {
    ElMessage.warning('请选择 ZIP 文件'); return;
  }
  if ((form.sourceType === 'GITLAB' || form.sourceType === 'REMOTE_GIT') && !form.url.trim()) {
    ElMessage.warning('请填写 HTTPS Git 地址'); return;
  }
  submitted.value = true;
  emit('submit', { ...form, name: form.name.trim(), file: file.value });
}
</script>

<template>
  <el-dialog
    v-model="open"
    title="接入项目"
    width="min(560px, 96vw)"
    :close-on-click-modal="!submitLocked"
    :close-on-press-escape="!submitLocked"
    :show-close="!submitLocked"
  >
    <el-form class="project-import-form" label-position="top" :disabled="submitLocked" @submit.prevent="submit">
      <section class="import-section" aria-labelledby="project-details-heading">
        <h3 id="project-details-heading">项目资料</h3>
        <el-form-item label="项目名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="项目说明"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="说明项目目标、边界或维护团队" /></el-form-item>
      </section>
      <section class="import-section" aria-labelledby="project-source-heading">
        <h3 id="project-source-heading">代码来源</h3>
        <el-form-item label="来源类型" required>
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
          <el-upload :auto-upload="false" :limit="1" accept=".zip,application/zip" :on-change="choose" :on-remove="clearFile">
            <el-button>选择 ZIP</el-button>
          </el-upload>
        </el-form-item>
      </section>
      <p class="draft-note">来源验证失败后会保留接入草稿，可在项目列表继续接入。</p>
    </el-form>
    <template #footer>
      <el-button :disabled="submitLocked" @click="open = false">取消</el-button>
      <el-button type="primary" :loading="submitLocked" :disabled="submitLocked" @click="submit">
        {{ submitLocked ? '正在接入…' : '开始接入' }}
      </el-button>
    </template>
    <RepositoryCredentialManagerDialog v-model="credentialManagerOpen" :repository-url="form.url"
      :preferred-type="form.sourceType === 'GITLAB' ? 'GITLAB_PAT' : 'GIT_HTTP_TOKEN'"
      @selected="credentialSelected" />
  </el-dialog>
</template>

<style scoped>
.credential-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:8px;width:100%}
.project-import-form{max-height:min(68vh,620px);overflow-y:auto;padding-right:4px}
.import-section + .import-section{margin-top:20px;padding-top:18px;border-top:1px solid var(--app-border)}
.import-section h3{margin:0 0 16px;color:var(--app-text-primary);font-size:14px;font-weight:600}
.draft-note{margin:4px 0 0;color:var(--app-text-muted);font-size:12px;line-height:1.5}
@media(max-width:640px){.credential-row{grid-template-columns:1fr 1fr}.credential-row .el-select{grid-column:1/-1}}
</style>
