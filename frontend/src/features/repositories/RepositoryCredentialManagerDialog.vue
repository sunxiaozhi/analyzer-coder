<script setup lang="ts">
import { reactive, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { repositoryCredentialsApi } from '@/api/repositoryCredentials';
import type { RepositoryCredential, RepositoryCredentialType } from '@/api/repositoryCredentials';

const open = defineModel<boolean>({ required: true });
const props = defineProps<{ repositoryUrl: string; preferredType: RepositoryCredentialType }>();
const emit = defineEmits<{ selected: [credential: RepositoryCredential] }>();
const rows = shallowRef<RepositoryCredential[]>([]);
const busy = shallowRef(false);
const saving = shallowRef(false);
const validatingId = shallowRef<string | null>(null);
const editingId = shallowRef<string | null>(null);
const form = reactive({ type: 'GIT_HTTP_TOKEN' as RepositoryCredentialType, displayName: '', serverUrl: '', username: '', secret: '' });

watch(open, value => { if (value) { void load(); reset(); } });

function serverFromUrl(value: string) {
  try { const url = new URL(value); return `${url.protocol}//${url.host}`; } catch { return ''; }
}
function reset() {
  editingId.value = null;
  Object.assign(form, {
    type: props.preferredType,
    displayName: props.preferredType === 'GITLAB_PAT' ? 'GitLab PAT' : 'Git HTTPS Token',
    serverUrl: serverFromUrl(props.repositoryUrl),
    username: props.preferredType === 'GITLAB_PAT' ? 'oauth2' : '',
    secret: '',
  });
}
function edit(item: RepositoryCredential) {
  editingId.value = item.id;
  Object.assign(form, { type: item.type, displayName: item.displayName, serverUrl: item.serverUrl, username: item.username ?? '', secret: '' });
}
async function load() { busy.value = true; try { rows.value = await repositoryCredentialsApi.list(); } finally { busy.value = false; } }
async function save() {
  if (!form.displayName.trim() || !form.serverUrl.trim() || (!editingId.value && !form.secret.trim())) {
    ElMessage.warning('请填写凭据名称、服务地址和访问令牌'); return;
  }
  saving.value = true;
  try {
    const payload = { ...form };
    const saved = editingId.value
      ? await repositoryCredentialsApi.update(editingId.value, payload)
      : await repositoryCredentialsApi.create(payload);
    await load(); edit(saved); ElMessage.success('凭据已安全保存');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '凭据保存失败'); }
  finally { saving.value = false; }
}
async function validate(item: RepositoryCredential) {
  if (!props.repositoryUrl.trim()) { ElMessage.warning('请先在接入表单填写仓库地址'); return; }
  validatingId.value = item.id;
  try { const updated = await repositoryCredentialsApi.validate(item.id, props.repositoryUrl); rows.value = rows.value.map(row => row.id === updated.id ? updated : row); ElMessage.success('凭据验证成功'); }
  catch (error) { await load(); ElMessage.error(error instanceof Error ? error.message : '凭据验证失败'); }
  finally { validatingId.value = null; }
}
async function toggle(item: RepositoryCredential) {
  try {
    const updated=item.status==='DISABLED'?await repositoryCredentialsApi.enable(item.id):await repositoryCredentialsApi.disable(item.id);
    rows.value=rows.value.map(row=>row.id===updated.id?updated:row);edit(updated);
    ElMessage.success(updated.status==='ACTIVE'?'凭据已启用':'凭据已停用');
  } catch(error){ElMessage.error(error instanceof Error?error.message:'操作失败')}
}
async function remove(item: RepositoryCredential) {
  const bindings=await repositoryCredentialsApi.bindings(item.id);
  const detail=bindings.length?`当前仍绑定：${bindings.map(binding=>binding.repositoryName).join('、')}`:'删除后无法恢复。';
  await ElMessageBox.confirm(detail,'删除凭据',{type:'warning'});
  try{await repositoryCredentialsApi.remove(item.id);await load();reset();ElMessage.success('凭据已删除')}
  catch(error){ElMessage.error(error instanceof Error?error.message:'删除失败')}
}
function select(item: RepositoryCredential) { if (item.status !== 'ACTIVE') return; emit('selected', item); open.value = false; }
</script>

<template>
  <el-dialog v-model="open" title="Git 凭据管理" width="760" append-to-body>
    <el-alert title="令牌仅加密保存且不会再次显示；服务地址限定凭据可使用的 Git 主机。" type="info" :closable="false" />
    <div class="credential-layout">
      <section class="credential-list" v-loading="busy">
        <div class="section-title"><b>可用凭据</b><el-button link type="primary" @click="reset">新建</el-button></div>
        <el-empty v-if="!rows.length && !busy" description="暂无凭据" :image-size="60" />
        <button v-for="item in rows" :key="item.id" type="button" class="credential-item" :class="{ active: editingId === item.id }" @click="edit(item)">
          <span><b>{{ item.displayName }}</b><small>{{ item.serverUrl }} · {{ item.maskedValue }}</small></span>
          <el-tag size="small" :type="item.status === 'ACTIVE' ? 'success' : 'danger'">{{ item.status === 'ACTIVE' ? '可用' : '失效' }}</el-tag>
        </button>
      </section>
      <el-form class="credential-form" label-position="top">
        <el-form-item label="凭据类型"><el-select v-model="form.type"><el-option label="通用 Git HTTPS 访问令牌" value="GIT_HTTP_TOKEN"/><el-option label="GitLab 个人访问令牌" value="GITLAB_PAT"/></el-select></el-form-item>
        <el-form-item label="凭据名称" required><el-input v-model="form.displayName" maxlength="100" /></el-form-item>
        <el-form-item label="Git 服务地址" required><el-input v-model="form.serverUrl" placeholder="https://gitlab.example.com" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.username" :placeholder="form.type === 'GITLAB_PAT' ? 'oauth2' : 'Git 用户名'" /></el-form-item>
        <el-form-item :label="editingId ? '新令牌（留空保留原令牌）' : '访问令牌'" required><el-input v-model="form.secret" type="password" show-password autocomplete="new-password" /></el-form-item>
        <div class="form-actions">
          <el-button v-if="editingId" :loading="validatingId === editingId" @click="validate(rows.find(item => item.id === editingId)!)">检测当前仓库</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button v-if="editingId" :disabled="rows.find(item => item.id === editingId)?.status !== 'ACTIVE'" @click="select(rows.find(item => item.id === editingId)!)">选择此凭据</el-button>
          <el-button v-if="editingId" @click="toggle(rows.find(item => item.id === editingId)!)">{{ rows.find(item => item.id === editingId)?.status === 'DISABLED' ? '启用' : '停用' }}</el-button>
          <el-button v-if="editingId" type="danger" plain @click="remove(rows.find(item => item.id === editingId)!)">删除</el-button>
        </div>
      </el-form>
    </div>
  </el-dialog>
</template>

<style scoped>
.credential-layout{display:grid;grid-template-columns:280px 1fr;gap:20px;margin-top:18px}.credential-list{border-right:1px solid var(--el-border-color);padding-right:16px;min-height:360px}.section-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}.credential-item{width:100%;display:flex;align-items:center;justify-content:space-between;gap:8px;border:1px solid var(--el-border-color);background:#fff;border-radius:8px;padding:10px;margin-bottom:8px;text-align:left;cursor:pointer}.credential-item.active{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.credential-item span{display:grid;gap:4px;min-width:0}.credential-item small{color:var(--el-text-color-secondary);overflow:hidden;text-overflow:ellipsis}.form-actions{display:flex;justify-content:flex-end;gap:8px}@media(max-width:720px){.credential-layout{grid-template-columns:1fr}.credential-list{border-right:0;border-bottom:1px solid var(--el-border-color);padding-right:0;padding-bottom:12px;min-height:0}}
</style>
