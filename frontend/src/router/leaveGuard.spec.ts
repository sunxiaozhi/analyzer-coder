import { createPinia, setActivePinia } from 'pinia';
import { expect, it, vi } from 'vitest';
import { useWorkspaceLeaveGuardStore } from '@/stores/workspaceLeaveGuard';
import { router } from './index';

vi.mock('@/stores/authStore', () => ({ useAuthStore: () => ({
  restore: vi.fn(), authenticated: true, account: { mustChangePassword: false }, isAdmin: true,
}) }));

it('keeps the current route until the editor allows leaving, including branch query changes', async () => {
  setActivePinia(createPinia());
  await router.push('/knowledge');
  const guard = vi.fn().mockResolvedValue(false);
  const unregister = useWorkspaceLeaveGuardStore().register(guard);
  await router.push('/ask');
  expect(router.currentRoute.value.fullPath).toBe('/knowledge');
  await router.push('/knowledge?branchId=other');
  expect(router.currentRoute.value.fullPath).toBe('/knowledge');
  guard.mockResolvedValue(true);
  await router.push('/ask');
  expect(router.currentRoute.value.fullPath).toBe('/ask');
  unregister();
});
