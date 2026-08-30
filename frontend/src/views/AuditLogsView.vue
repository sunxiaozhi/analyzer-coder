<script setup lang="ts">
import { onMounted, shallowRef } from 'vue';
import { RefreshCw, ScrollText } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { useRoute } from 'vue-router';
import { accountsApi } from '@/api/accounts';
import AuditLogPanel from '@/features/accounts/AuditLogPanel.vue';
import type { AuditEvent } from '@/types/security';

const route = useRoute();
const rows = shallowRef<AuditEvent[]>([]);
const loading = shallowRef(false);
const focusUsername = shallowRef(
  typeof route.query.username === 'string' ? route.query.username : '',
);
const focusVersion = shallowRef(1);

async function load() {
  loading.value = true;
  try {
    rows.value = await accountsApi.audit();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审计日志加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => void load());
</script>

<template>
  <section class="page audit-page">
    <header class="audit-heading surface">
      <span class="audit-mark"><ScrollText :size="18" /></span>
      <div>
        <h1>审计日志</h1>
        <p>系统级账号、权限和仓库治理事件；日志仅记录已持久化的实际操作结果。</p>
      </div>
      <el-button :loading="loading" @click="load"><RefreshCw :size="14" />刷新</el-button>
    </header>
    <div class="surface audit-surface">
      <AuditLogPanel
        :rows="rows"
        :loading="loading"
        :focus-username="focusUsername"
        :focus-version="focusVersion"
      />
    </div>
  </section>
</template>

<style scoped>
.audit-page { grid-template-rows: auto minmax(0, 1fr); overflow: hidden; }
.audit-heading { display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 13px 16px; }
.audit-mark { display: grid; width: 34px; height: 34px; place-items: center; color: #175d86; border-radius: 6px; background: #eaf3f8; }
.audit-heading h1 { margin: 0; font-size: 16px; }
.audit-heading p { margin: 4px 0 0; color: var(--app-text-muted); font-size: 11px; }
.audit-surface { min-height: 0; padding-top: 2px; }
@media (max-width: 760px) {
  .audit-page { display: block; overflow: visible; }
  .audit-heading { margin-bottom: 10px; }
  .audit-heading p { display: none; }
}
</style>
