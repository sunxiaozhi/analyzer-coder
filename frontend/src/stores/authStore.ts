import { defineStore } from 'pinia';
import { computed, shallowRef } from 'vue';
import { usePageMemoryStore } from './pageMemory';
import { authApi } from '@/api/auth';
import { ApiError, setCsrfToken } from '@/api/http';
import type { SessionAccount } from '@/types/security';

export const useAuthStore = defineStore('auth', () => {
  const account = shallowRef<SessionAccount | null>(null);
  const initialized = shallowRef(false);
  const restoreError = shallowRef('');
  const authenticated = computed(() => account.value !== null);
  const isAdmin = computed(() => account.value?.role === 'SUPER_ADMIN');
  let sessionVersion = 0;
  let restoreRequest: Promise<void> | null = null;

  function accept(session: SessionAccount | null) {
    sessionVersion++;
    restoreRequest = null;
    if (account.value?.id !== session?.id) usePageMemoryStore().clear();
    account.value = session;
    restoreError.value = '';
    setCsrfToken(session?.csrfToken ?? null);
  }

  function restore(): Promise<void> {
    if (initialized.value) return Promise.resolve();
    if (restoreRequest) return restoreRequest;
    const version = sessionVersion;
    restoreError.value = '';
    const attempt = (async () => {
      try {
        const session = await authApi.me();
        if (version !== sessionVersion) return;
        accept(session);
        initialized.value = true;
      } catch (cause) {
        // A newer login/logout must not be replaced by a late session check.
        if (version !== sessionVersion) return;
        if (!(cause instanceof ApiError) || cause.status !== 401) {
          restoreError.value = cause instanceof Error ? cause.message : '登录状态暂不可用';
          throw cause;
        }
        accept(null);
        initialized.value = true;
      }
    })();
    const tracked = attempt.finally(() => { if (restoreRequest === tracked) restoreRequest = null; });
    restoreRequest = tracked;
    return tracked;
  }

  async function login(username: string, password: string, captchaId?: string, captchaAnswer?: string) {
    const version = ++sessionVersion;
    const session = await authApi.login(username, password, captchaId, captchaAnswer);
    if (version === sessionVersion) { accept(session); initialized.value = true; }
    return session;
  }
  async function changePassword(current: string, next: string) {
    accept(await authApi.changePassword(current, next));
  }
  async function logout() {
    const version = ++sessionVersion;
    try { await authApi.logout(); }
    finally { if (version === sessionVersion) { accept(null); initialized.value = true; } }
  }
  window.addEventListener('auth-expired', () => { accept(null); initialized.value = true; });
  return { account, initialized, restoreError, authenticated, isAdmin, restore, login, changePassword, logout };
});
