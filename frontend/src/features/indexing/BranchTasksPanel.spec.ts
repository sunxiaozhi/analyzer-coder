import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { branchesApi, type BranchPreparationJob } from '@/api/branches';
import type { Repository } from '@/types/api';
import BranchTasksPanel from './BranchTasksPanel.vue';
vi.mock('@/api/branches', () => ({ branchesApi: { list: vi.fn(), preparationHistory: vi.fn() } }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
afterEach(() => vi.resetAllMocks());
const projects = [{ id:'p1', name:'项目一', sourceType:'REMOTE_GIT' }, { id:'p2', name:'项目二', sourceType:'LOCAL_GIT' }] as Repository[];
const job: BranchPreparationJob = { id:'j1',branchId:'b1',kind:'CONTENT',status:'FAILED',stage:'FAILED',error:'索引错误',contentVersion:'old' };
function panel() { return mount(BranchTasksPanel, { props:{repositories:projects,initialRepositoryId:'p1'},global:{stubs:{ElButton:{template:'<button><slot /></button>'},ElAlert:{props:['title'],template:'<p>{{ title }}</p>'},ElEmpty:true,AppPagination:true,ElDialog:{props:['modelValue'],template:'<div v-if="modelValue" role="dialog"><slot /><slot name="footer" /></div>'}}} }); }
it('shows task history and requests server-side branch filtering', async () => {
  vi.mocked(branchesApi.list).mockResolvedValue([{id:'b1',name:'release'}] as never);
  vi.mocked(branchesApi.preparationHistory).mockResolvedValue({items:[job],pageNum:1,pageSize:15,total:2,pages:1});
  const wrapper=panel(); await flushPromises();
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  expect(wrapper.text()).not.toContain('索引错误');
  await wrapper.get('tbody button').trigger('click');
  expect(wrapper.get('[role="dialog"]').text()).toContain('索引错误');
  expect(wrapper.get('[role="dialog"]').text()).toContain('历史任务不代表当前分支');
  await wrapper.get('[role="dialog"] button').trigger('click');
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  await wrapper.get('tbody button').trigger('click');
  await wrapper.get('select[aria-label="任务所属分支"]').setValue('b1'); await flushPromises();
  expect(branchesApi.preparationHistory).toHaveBeenLastCalledWith('p1',1,15,'b1');
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  wrapper.unmount();
});
it('ignores task responses from a previously selected project', async () => {
  let resolveOld!: (value: any) => void;
  vi.mocked(branchesApi.list).mockResolvedValue([]);
  vi.mocked(branchesApi.preparationHistory).mockReturnValueOnce(new Promise(resolve=>{resolveOld=resolve;})).mockResolvedValue({items:[],pageNum:1,pageSize:15,total:0,pages:0});
  const wrapper=panel();
  await wrapper.get('select[aria-label="任务所属项目"]').setValue('p2'); await flushPromises();
  resolveOld({items:[job],pageNum:1,pageSize:15,total:1,pages:1}); await flushPromises();
  expect(wrapper.text()).not.toContain('索引错误');
  expect(branchesApi.preparationHistory).toHaveBeenLastCalledWith('p2',1,15,undefined);
  wrapper.unmount();
});
