<script setup lang="ts">
import{computed,onMounted,shallowRef,watch}from'vue';
import{ElMessage,ElMessageBox}from'element-plus';
import{accountsApi,type AccountInput}from'@/api/accounts';
import AccountDialog from'@/features/accounts/AccountDialog.vue';
import AccountTable from'@/features/accounts/AccountTable.vue';
import AccountPermissionDialog from'@/features/accounts/AccountPermissionDialog.vue';
import AuditLogPanel from'@/features/accounts/AuditLogPanel.vue';
import{useAuthStore}from'@/stores/authStore';
import type{AccountSummary,AuditEvent}from'@/types/security';

const auth=useAuthStore(),accounts=shallowRef<AccountSummary[]>([]),audit=shallowRef<AuditEvent[]>([]),query=shallowRef(''),activeTab=shallowRef('accounts'),dialog=shallowRef(false),permissionDialog=shallowRef(false),selected=shallowRef<AccountSummary|null>(null),busy=shallowRef(false),auditLoading=shallowRef(false),auditLoaded=shallowRef(false),auditFocus=shallowRef(''),auditFocusVersion=shallowRef(0);
const rows=computed(()=>{const value=query.value.trim().toLowerCase();return value?accounts.value.filter(account=>`${account.username} ${account.displayName}`.toLowerCase().includes(value)):accounts.value;});
const summary=computed(()=>({total:accounts.value.length,admins:accounts.value.filter(account=>account.role==='SUPER_ADMIN').length,enabled:accounts.value.filter(account=>account.status==='ENABLED').length,restricted:accounts.value.filter(account=>account.status!=='ENABLED').length}));
async function loadAccounts(){accounts.value=await accountsApi.list();}
async function loadAudit(){auditLoading.value=true;try{audit.value=await accountsApi.audit();auditLoaded.value=true;}catch(error){ElMessage.error(error instanceof Error?error.message:'审计日志加载失败');}finally{auditLoading.value=false;}}
async function create(input:AccountInput){busy.value=true;try{const created=await accountsApi.create(input);dialog.value=false;await loadAccounts();auditLoaded.value=false;await ElMessageBox.alert(`临时密码：${created.temporaryPassword}\n请安全转交，此密码只显示一次。`,'账号已创建');}finally{busy.value=false;}}
async function toggle(account:AccountSummary){await accountsApi.update(account.id,{enabled:account.status==='DISABLED'});await loadAccounts();auditLoaded.value=false;}
async function reset(account:AccountSummary){const result=await accountsApi.resetPassword(account.id);await loadAccounts();auditLoaded.value=false;await ElMessageBox.alert(`新临时密码：${result.temporaryPassword}`,'密码已重置');}
async function unlock(account:AccountSummary){await accountsApi.unlock(account.id);await loadAccounts();auditLoaded.value=false;}
function permissions(account:AccountSummary){selected.value=account;permissionDialog.value=true;}
async function showAudit(account:AccountSummary){auditFocus.value=account.username;auditFocusVersion.value++;activeTab.value='audit';if(!auditLoaded.value)await loadAudit();}
watch(activeTab,tab=>{if(tab==='audit'&&!auditLoaded.value)void loadAudit();});
onMounted(()=>void loadAccounts());
</script>
<template><section class="page account-design"><div class="summary-strip"><div><span>账号总数</span><b>{{summary.total}}</b></div><div><span>管理员</span><b>{{summary.admins}}</b></div><div><span>正常</span><b>{{summary.enabled}}</b></div><div><span>受限</span><b>{{summary.restricted}}</b></div></div><div class="surface"><el-tabs v-model="activeTab"><el-tab-pane label="账号管理" name="accounts"><div class="toolbar account-toolbar"><el-input v-model="query" placeholder="搜索姓名、账号" clearable/><el-button type="primary" @click="dialog=true">新增账号</el-button></div><AccountTable :rows="rows" :current-account-id="auth.account?.id" @toggle="toggle" @reset="reset" @unlock="unlock" @permissions="permissions" @audit="showAudit"/></el-tab-pane><el-tab-pane label="审计日志" name="audit" lazy><AuditLogPanel :rows="audit" :loading="auditLoading" :focus-username="auditFocus" :focus-version="auditFocusVersion" @refresh="loadAudit"/></el-tab-pane></el-tabs></div><AccountDialog v-model="dialog" :busy="busy" @submit="create"/><AccountPermissionDialog v-model="permissionDialog" :account="selected"/></section></template>
<style scoped>.account-toolbar{margin-bottom:16px}.account-toolbar .el-input{max-width:360px}</style>
