import { shallowMount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, expect, it } from 'vitest';
import McpGuide from './McpGuide.vue';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';

beforeEach(() => setActivePinia(createPinia()));
it('uses explicit selected branch coordinates and never a context from another project', async () => {
  useRepositoryStore().selectedRepositoryId = 'knowledge';
  const branches = useBranchContextStore();
  branches.repositoryId = 'knowledge'; branches.selectedBranchId = 'workspace-uuid';
  const wrapper = shallowMount(McpGuide, { global: { stubs: { RouterLink: true, ElButton: true, ElDialog: true } } });
  const search = () => JSON.parse(wrapper.get('[aria-label="联合检索参数"]').text());
  expect(search()).toMatchObject({ repositoryId: 'knowledge', branchId: 'workspace-uuid' });
  expect(search().contextId).toBeUndefined();
  expect(JSON.parse(wrapper.get('[aria-label="解析项目上下文参数"]').text())).toEqual({ repositoryId: 'knowledge', branchId: 'workspace-uuid' });
  useRepositoryStore().selectedRepositoryId = 'another-project';
  await wrapper.vm.$nextTick();
  expect(search().repositoryId).toBe('another-project');
  expect(search().branchId).not.toBe('workspace-uuid');
  wrapper.unmount();
});
