import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import EngineeringProjectsDialog from './EngineeringProjectsDialog.vue';
import { engineeringProjectsApi } from '@/api/engineeringProjects';
import type { Repository } from '@/types/api';

vi.mock('@/api/engineeringProjects', () => ({
  engineeringProjectsApi: {
    list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(),
  },
}));

const repository = (id: string, name: string) => ({
  id, name, snapshotId: `snapshot-${id}`,
  capabilities: { canConfigure: true },
} as Repository);

describe('EngineeringProjectsDialog', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows explicit service identities and two-sided contract evidence instead of inferred links', async () => {
    vi.mocked(engineeringProjectsApi.list).mockResolvedValue([{
      id: 'project-1', name: 'Commerce', description: '跨仓边界', version: 1,
      repositories: [
        { repositoryId: 'repo-1', repositoryName: 'Order', serviceName: 'order-service' },
        { repositoryId: 'repo-2', repositoryName: 'Web', serviceName: 'web-service' },
      ],
      contracts: [{
        id: 'contract-1', contractKey: 'order-api-v1', name: '订单 API',
        providerRepositoryId: 'repo-1', consumerRepositoryId: 'repo-2',
        providerEvidencePath: 'openapi/order.yaml', consumerEvidencePath: 'src/order-client.ts',
        providerEvidenceCurrent: true, consumerEvidenceCurrent: true, current: true,
      }],
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }]);
    const wrapper = mount(EngineeringProjectsDialog, {
      props: {
        modelValue: true,
        repositories: [repository('repo-1', 'Order'), repository('repo-2', 'Web')],
      },
      global: {
        directives: { loading: () => undefined },
        stubs: {
          'el-dialog': { template: '<div><slot/><slot name="footer"/></div>' },
          'el-button': { template: '<button><slot/></button>' },
          'el-input': { props: ['modelValue'], template: '<span>{{ modelValue }}</span>' },
          'el-select': { props: ['modelValue'], template: '<span>{{ modelValue }}<slot/></span>' },
          'el-option': { template: '<option />' },
          'el-tag': { template: '<span><slot/></span>' },
          'el-alert': { template: '<div />' },
          'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
          Connection: true, Delete: true, Plus: true,
        },
      },
    });
    await flushPromises();
    await wrapper.get('.project-index button').trigger('click');

    expect(engineeringProjectsApi.list).toHaveBeenCalledOnce();
    expect(wrapper.text()).toContain('只登记可复核关系');
    expect(wrapper.text()).toContain('order-service');
    expect(wrapper.text()).toContain('contract-1');
    expect(wrapper.text()).toContain('两端证据当前');
    expect(wrapper.text()).toContain('路径必须已被双方当前内容索引收录');
  });
});
