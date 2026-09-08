import { defineComponent, toRef } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { accessTokensApi } from '@/api/accessTokens';
import { useAccessTokens } from './useAccessTokens';
vi.mock('@/api/accessTokens', () => ({ accessTokensApi: { list: vi.fn(), create: vi.fn(), revoke: vi.fn() } }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn() } }));
const Harness = defineComponent({
  props: { accountId: { type: String, required: true } },
  setup: props => useAccessTokens(toRef(props, 'accountId')),
  template: '<div>{{ rawToken }}</div>',
});
const token = { id: 'token-1', name: 'client', prefix: 'acp_abc', createdAt: '2026-09-08', expiresAt: '2026-12-08', lastUsedAt: null, revokedAt: null };
beforeEach(() => { vi.resetAllMocks(); vi.mocked(accessTokensApi.list).mockResolvedValue([]); });
describe('account access token lifecycle', () => {
  it('creates for the selected account and clears the secret on account change', async () => {
    vi.mocked(accessTokensApi.create).mockResolvedValue({ token, rawToken: 'only-show-once' });
    const wrapper = mount(Harness, { props: { accountId: 'alice' } });
    await flushPromises();
    await wrapper.vm.create('client', 30);
    expect(accessTokensApi.create).toHaveBeenCalledWith('alice', 'client', 30);
    expect(wrapper.text()).toContain('only-show-once');
    await wrapper.setProps({ accountId: 'bob' });
    await flushPromises();
    expect(wrapper.text()).not.toContain('only-show-once');
    expect(wrapper.vm.tokens).toEqual([]);
    wrapper.unmount();
  });
  it('does not leak a late issuance response into another account', async () => {
    let resolve!: (value: { token: typeof token; rawToken: string }) => void;
    vi.mocked(accessTokensApi.create).mockReturnValue(new Promise(done => { resolve = done; }));
    const wrapper = mount(Harness, { props: { accountId: 'alice' } });
    await flushPromises();
    const pending = wrapper.vm.create('client', 30);
    await wrapper.setProps({ accountId: 'bob' });
    resolve({ token, rawToken: 'alice-secret' });
    await pending;
    await flushPromises();
    expect(wrapper.text()).not.toContain('alice-secret');
    expect(wrapper.vm.tokens).toEqual([]);
    wrapper.unmount();
  });
  it('revokes under the selected account and removes the displayed secret', async () => {
    vi.mocked(accessTokensApi.create).mockResolvedValue({ token, rawToken: 'secret' });
    vi.mocked(accessTokensApi.revoke).mockResolvedValue({ revoked: true });
    const wrapper = mount(Harness, { props: { accountId: 'alice' } });
    await flushPromises();
    await wrapper.vm.create('client', 30);
    await wrapper.vm.revoke(token.id);
    expect(accessTokensApi.revoke).toHaveBeenCalledWith('alice', token.id);
    expect(wrapper.vm.rawToken).toBe('');
    expect(wrapper.vm.tokens[0].revokedAt).toBeTruthy();
    wrapper.unmount();
  });
});
