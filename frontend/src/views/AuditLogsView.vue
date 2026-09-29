<script setup lang="ts">
import { onMounted, shallowRef } from 'vue';
import { RefreshCw } from 'lucide-vue-next';
import { useRoute } from 'vue-router';
import { accountsApi } from '@/api/accounts';
import AuditLogPanel from '@/features/accounts/AuditLogPanel.vue';
import type { AuditEvent } from '@/types/security';

const route = useRoute();
const rows = shallowRef<AuditEvent[]>([]);
const loading = shallowRef(false);
const loadError = shallowRef<string | null>(null);
const focusUsername = shallowRef(
  typeof route.query.username === 'string' ? route.query.username : '',
);
const focusVersion = shallowRef(1);

async function load() {
  loading.value = true; loadError.value = null;
  try {
    rows.value = await accountsApi.audit();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '审计日志加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(() => void load());
</script>

<template>
  <section class="page audit-page">
    <div class="surface audit-surface">
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
      <AuditLogPanel
        :rows="rows"
        :loading="loading"
        :focus-username="focusUsername"
        :focus-version="focusVersion"
      >
        <template #actions><el-button :loading="loading" @click="load"><RefreshCw :size="14" />刷新</el-button></template>
      </AuditLogPanel>
    </div>
  </section>
</template>

<style scoped>
.audit-page { grid-template-rows: minmax(0, 1fr); overflow: hidden; }
.audit-surface { min-height: 0; padding-top: 2px; }
@media (max-width: 760px) {
  .audit-page { display: block; overflow: visible; }
}
</style>
