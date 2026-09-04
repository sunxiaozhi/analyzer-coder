import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import LoginView from './LoginView.vue';

const mocks = vi.hoisted(() => ({
  account: null as { mustChangePassword: boolean } | null,
  login: vi.fn(),
  changePassword: vi.fn(),
  replace: vi.fn(),
  closeAll: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace }),
}));

vi.mock('@/stores/authStore', () => ({
  useAuthStore: () => ({
    get account() { return mocks.account; },
    login: mocks.login,
    changePassword: mocks.changePassword,
  }),
}));

vi.mock('@/stores/workspaceTabs', () => ({
  useWorkspaceTabsStore: () => ({ closeAll: mocks.closeAll }),
}));

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn() },
}));

describe('LoginView', () => {
  beforeEach(() => {
    mocks.account = null;
    vi.clearAllMocks();
  });

  it('always opens the project overview after a normal login', async () => {
    mocks.login.mockResolvedValue({ mustChangePassword: false });
    const wrapper = mount(LoginView);
    const inputs = wrapper.findAll<HTMLInputElement>('input');
    await inputs[0].setValue('admin');
    await inputs[1].setValue('password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(mocks.login).toHaveBeenCalledWith('admin', 'password', undefined, '');
    expect(mocks.closeAll).toHaveBeenCalledOnce();
    expect(mocks.replace).toHaveBeenCalledWith('/overview');
  });

  it('opens the project overview after the required password change', async () => {
    mocks.account = { mustChangePassword: true };
    mocks.changePassword.mockResolvedValue({ mustChangePassword: false });
    const wrapper = mount(LoginView);
    const inputs = wrapper.findAll<HTMLInputElement>('input');
    await inputs[0].setValue('old-password');
    await inputs[1].setValue('new-password');
    await inputs[2].setValue('new-password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(mocks.changePassword).toHaveBeenCalledWith('old-password', 'new-password');
    expect(mocks.replace).toHaveBeenCalledWith('/overview');
  });
});
