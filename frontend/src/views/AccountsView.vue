<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { accountsApi, type AccountInput } from '@/api/accounts';
import AppPagination from '@/components/AppPagination.vue';
import AccountDialog from '@/features/accounts/AccountDialog.vue';
import AccountEditDialog from '@/features/accounts/AccountEditDialog.vue';
import AccountTable from '@/features/accounts/AccountTable.vue';
import AuditLogPanel from '@/features/accounts/AuditLogPanel.vue';
import { useAuthStore } from '@/stores/authStore';
import type { AccountSummary, AuditEvent } from '@/types/security';

const auth = useAuthStore();
const accounts = shallowRef<AccountSummary[]>([]);
const audit = shallowRef<AuditEvent[]>([]);
const query = shallowRef('');
const pageNum = shallowRef(1);
const pageSize = shallowRef(15);
const total = shallowRef(0);
const loading = shallowRef(false);
const activeTab = shallowRef('accounts');
const dialog = shallowRef(false);
const busy = shallowRef(false);
const auditLoading = shallowRef(false);
const auditLoaded = shallowRef(false);
const auditFocus = shallowRef('');
const auditFocusVersion = shallowRef(0);
const editDialog = shallowRef(false);
const editing = shallowRef<AccountSummary | null>(null);
const editBusy = shallowRef(false);
let searchTimer: number | undefined;

const summary = computed(() => ({
  total: total.value,
  admins: accounts.value.filter(account => account.role === 'SUPER_ADMIN').length,
  enabled: accounts.value.filter(account => account.status === 'ENABLED').length,
  restricted: accounts.value.filter(account => account.status !== 'ENABLED').length,
}));

async function loadAccounts() {
  loading.value = true;
  try {
    const result = await accountsApi.page({ query: query.value, pageNum: pageNum.value, pageSize: pageSize.value });
    accounts.value = result.items;
    total.value = result.total;
    if (!result.items.length && pageNum.value > 1) {
      pageNum.value -= 1;
      await loadAccounts();
    }
  } finally { loading.value = false; }
}
async function changePage(value: number) { pageNum.value = value; await loadAccounts(); }
async function changePageSize(value: number) { pageSize.value = value; pageNum.value = 1; await loadAccounts(); }
async function loadAudit() { auditLoading.value = true; try { audit.value = await accountsApi.audit(); auditLoaded.value = true; } catch (error) { ElMessage.error(error instanceof Error ? error.message : '审计日志加载失败'); } finally { auditLoading.value = false; } }
async function create(input: AccountInput) { busy.value = true; try { const created = await accountsApi.create(input); dialog.value = false; pageNum.value = 1; await loadAccounts(); auditLoaded.value = false; await ElMessageBox.alert(`临时密码：${created.temporaryPassword}\n请安全转交，此密码只显示一次。`, '账号已创建'); } finally { busy.value = false; } }
function openEdit(account: AccountSummary) { editing.value = account; editDialog.value = true; }
async function saveEdit(input: { displayName: string; role: AccountSummary['role']; version: number }) { if (!editing.value) return; editBusy.value = true; try { await accountsApi.update(editing.value.id, input); editDialog.value = false; await loadAccounts(); auditLoaded.value = false; ElMessage.success('账号资料已更新'); } finally { editBusy.value = false; } }
async function toggle(account: AccountSummary) { await accountsApi.update(account.id, { enabled: account.status === 'DISABLED', version: account.version }); await loadAccounts(); auditLoaded.value = false; }
async function reset(account: AccountSummary) { await ElMessageBox.confirm(`确定重置账号“${account.username}”的密码吗？重置后该账号的现有会话将立即失效。`, '确认重置密码', { type: 'warning', confirmButtonText: '确定重置', cancelButtonText: '取消' }); const result = await accountsApi.resetPassword(account.id); await loadAccounts(); auditLoaded.value = false; await ElMessageBox.alert(`重置密码：${result.temporaryPassword}\n该账号下次登录必须先修改密码，修改完成前无法进入系统。`, '密码已重置', { confirmButtonText: '我知道了' }); }
async function unlock(account: AccountSummary) { await accountsApi.unlock(account.id); await loadAccounts(); auditLoaded.value = false; }
async function showAudit(account: AccountSummary) { auditFocus.value = account.username; auditFocusVersion.value++; activeTab.value = 'audit'; if (!auditLoaded.value) await loadAudit(); }

watch(query, () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => { pageNum.value = 1; void loadAccounts(); }, 300);
});
watch(activeTab, tab => { if (tab === 'audit' && !auditLoaded.value) void loadAudit(); });
onMounted(() => void loadAccounts());
onBeforeUnmount(() => window.clearTimeout(searchTimer));
</script>

<template>
  <section class="page account-design">
    <div class="summary-strip">
      <div><span>账号总数</span><b>{{ summary.total }}</b></div>
      <div><span>本页管理员</span><b>{{ summary.admins }}</b></div>
      <div><span>本页正常</span><b>{{ summary.enabled }}</b></div>
      <div><span>本页受限</span><b>{{ summary.restricted }}</b></div>
    </div>
    <div class="surface account-surface">
      <el-tabs v-model="activeTab" class="account-tabs">
        <el-tab-pane class="account-list-pane" label="账号管理" name="accounts">
          <div class="toolbar account-toolbar"><el-input v-model="query" class="app-search-input" :prefix-icon="Search" placeholder="搜索姓名、账号" clearable /><el-button type="primary" @click="dialog=true">新增账号</el-button></div>
          <div class="account-table-region">
            <AccountTable v-loading="loading" :rows="accounts" :current-account-id="auth.account?.id" @edit="openEdit" @toggle="toggle" @reset="reset" @unlock="unlock" @audit="showAudit" />
          </div>
          <AppPagination :page-num="pageNum" :page-size="pageSize" :total="total" :disabled="loading" @page-change="changePage" @size-change="changePageSize" />
        </el-tab-pane>
        <el-tab-pane class="account-audit-pane" label="审计日志" name="audit" lazy><AuditLogPanel :rows="audit" :loading="auditLoading" :focus-username="auditFocus" :focus-version="auditFocusVersion" @refresh="loadAudit" /></el-tab-pane>
      </el-tabs>
    </div>
    <AccountDialog v-model="dialog" :busy="busy" @submit="create" />
    <AccountEditDialog v-model="editDialog" :account="editing" :busy="editBusy" @submit="saveEdit" />
  </section>
</template>

<style scoped>
.account-design {
  grid-template-rows: 80px minmax(0, 1fr);
  overflow: hidden;
}

.account-surface,
.account-tabs {
  min-height: 0;
  height: 100%;
}

.account-tabs {
  display: flex;
  flex-direction: column;
}

.account-tabs :deep(.el-tabs__header) {
  flex: none;
}

.account-tabs :deep(.el-tabs__nav-scroll) {
  padding-inline: 12px;
}

.account-tabs :deep(.el-tabs__content) {
  min-height: 0;
  flex: 1;
}

.account-tabs :deep(.account-list-pane) {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-height: 0;
  height: 100%;
}

.account-tabs :deep(.account-audit-pane) {
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.account-toolbar {
  margin-bottom: 0;
}

.account-toolbar .el-input {
  max-width: 360px;
}

.account-table-region {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

@media (max-width: 760px) {
  .account-design {
    grid-template-rows: auto auto;
    height: auto;
    overflow: visible;
  }

  .account-surface,
  .account-tabs,
  .account-tabs :deep(.account-list-pane),
  .account-tabs :deep(.account-audit-pane) {
    height: auto;
  }

  .account-tabs :deep(.el-tabs__content),
  .account-tabs :deep(.account-audit-pane),
  .account-table-region {
    overflow: visible;
  }

  .account-tabs :deep(.account-list-pane) {
    display: block;
  }
}
</style>
