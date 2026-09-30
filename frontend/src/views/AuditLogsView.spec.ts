import { flushPromises, shallowMount } from '@vue/test-utils';
import { reactive } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import AuditLogsView from './AuditLogsView.vue';
import AuditLogPanel from '@/features/accounts/AuditLogPanel.vue';
let route: { query: Record<string, string> };
vi.mock('vue-router', () => ({ useRoute: () => route }));
vi.mock('@/api/accounts', () => ({ accountsApi: { audit: vi.fn().mockResolvedValue([]) } }));
describe('audit account navigation', () => {
  it('updates the target when a kept-alive page is opened for a different account', async () => {
    route = reactive({ query: { username: 'alice' } });
    const wrapper = shallowMount(AuditLogsView, { global: { stubs: { ElButton: true, ElAlert: true } } });
    await flushPromises();
    const panel = wrapper.getComponent(AuditLogPanel);
    expect(panel.props('focusUsername')).toBe('alice');
    route.query = { username: 'bob' }; await flushPromises();
    expect(panel.props('focusUsername')).toBe('bob');
    expect(panel.props('focusVersion')).toBe(2);
    route.query = {}; await flushPromises();
    expect(panel.props('focusUsername')).toBe('');
    wrapper.unmount();
  });
});
