<script setup lang="ts">
import { shallowRef } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import SessionRecovery from '@/features/auth/SessionRecovery.vue';
const auth = useAuthStore();
const router = useRouter();
const retrying = shallowRef(false);
async function retrySession() {
  if (retrying.value) return;
  retrying.value = true;
  try {
    await auth.restore();
    await router.replace(window.location.pathname + window.location.search + window.location.hash);
  } catch { /* The store preserves the failure and allows another retry. */ }
  finally { retrying.value = false; }
}
</script>
<template>
  <SessionRecovery v-if="auth.restoreError || retrying" :error="auth.restoreError" :loading="retrying" @retry="retrySession" />
  <RouterView v-else />
</template>
