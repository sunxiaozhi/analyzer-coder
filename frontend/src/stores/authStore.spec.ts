import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, expect, it, vi } from 'vitest';
import { authApi } from '@/api/auth';
import { ApiError } from '@/api/http';
import { useAuthStore } from './authStore';
import type { SessionAccount } from '@/types/security';
vi.mock('@/api/auth', () => ({ authApi: { me: vi.fn(), login: vi.fn(), logout: vi.fn() } }));
const session = (id: string): SessionAccount => ({ id, username: id, displayName: id, role: 'NORMAL', mustChangePassword: false, lastLoginAt: null, csrfToken: 'test' });
beforeEach(() => { vi.resetAllMocks(); setActivePinia(createPinia()); });
it('retries temporary session failures and clears the error after recovery', async () => {
  vi.mocked(authApi.me).mockRejectedValueOnce(new Error('临时断网')).mockResolvedValue(session('user'));
  const auth = useAuthStore();
  await expect(auth.restore()).rejects.toThrow('临时断网');
  expect(auth.initialized).toBe(false); expect(auth.restoreError).toBe('临时断网');
  await auth.restore();
  expect(authApi.me).toHaveBeenCalledTimes(2); expect(auth.authenticated).toBe(true); expect(auth.restoreError).toBe('');
});
it('shares concurrent checks and allows a single fresh retry after a shared failure', async () => {
  let fail!: (error: Error) => void;
  vi.mocked(authApi.me).mockReturnValueOnce(new Promise((_, reject) => { fail = reject; })).mockResolvedValue(session('user'));
  const auth = useAuthStore(); const first = auth.restore(), second = auth.restore();
  expect(authApi.me).toHaveBeenCalledTimes(1);
  const results = Promise.allSettled([first, second]); fail(new Error('network'));
  expect((await results).every(result => result.status === 'rejected')).toBe(true);
  await auth.restore(); expect(authApi.me).toHaveBeenCalledTimes(2);
});
it('treats an explicit 401 as a completed anonymous check', async () => {
  vi.mocked(authApi.me).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '登录已失效'));
  const auth = useAuthStore(); await auth.restore(); await auth.restore();
  expect(auth.initialized).toBe(true); expect(auth.authenticated).toBe(false); expect(auth.restoreError).toBe('');
  expect(authApi.me).toHaveBeenCalledTimes(1);
});
it('does not replace a successful newer login with an older session response', async () => {
  let finish!: (value: SessionAccount) => void;
  vi.mocked(authApi.me).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  vi.mocked(authApi.login).mockResolvedValue(session('new'));
  const auth = useAuthStore(); const checking = auth.restore();
  await auth.login('new', 'password'); finish(session('old')); await checking;
  expect(auth.account?.id).toBe('new'); expect(auth.restoreError).toBe('');
});
it('does not restore a session after logout', async () => {
  let finish!: (value: SessionAccount) => void;
  vi.mocked(authApi.me).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  vi.mocked(authApi.logout).mockResolvedValue(undefined);
  const auth = useAuthStore(); const checking = auth.restore();
  await auth.logout(); finish(session('old')); await checking;
  expect(auth.account).toBeNull(); expect(auth.initialized).toBe(true);
});
