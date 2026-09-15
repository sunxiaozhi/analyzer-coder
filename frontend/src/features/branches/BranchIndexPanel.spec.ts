import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import BranchIndexPanel from './BranchIndexPanel.vue';
import type { BranchContext, RepositoryBranch } from '@/api/branches';
const branch: RepositoryBranch = { id:'b', name:'release', snapshotId:'s', commitSha:'a'.repeat(40), status:'READY', error:null, generation:1, trackingStatus:'ACTIVE', archivedAt:null };
const context: BranchContext = { contextId:'ctx', repositoryId:'p', branchId:'b', branchName:'release', snapshotId:'s', commitSha:'a'.repeat(40), expiresAt:'' };
const button = { props:['disabled'], template:'<button :disabled="disabled"><slot /></button>' };
function panel(extra = {}) {
  return mount(BranchIndexPanel, { props:{ branch, context, jobs:[], disabled:false, canMaintain:true, ...extra }, global:{ stubs:{ ElButton:button } } });
}
describe('branch index actions', () => {
  it('keeps primary actions visible and advanced indexes accessible', async () => {
    const wrapper=panel();
    const primary=wrapper.findAll('.index-actions')[0].findAll('button');
    const advanced=wrapper.findAll('.index-actions')[1].findAll('button');
    expect(primary.map(button => button.text())).toEqual(['同步代码','一键准备','打开代码']);
    expect(advanced[2].attributes('disabled')).toBeDefined();
    await primary[0].trigger('click'); await primary[1].trigger('click');
    await advanced[0].trigger('click'); await advanced[1].trigger('click');
    expect(wrapper.emitted('operate')).toEqual([['SYNC'],['PREPARE'],['CONTENT'],['GRAPH']]);
    expect(wrapper.find('details').attributes('open')).toBeUndefined();
    wrapper.unmount();
  });
  it('does not treat another snapshot as ready', () => {
    const wrapper=panel({ status:{branchId:'b',snapshotId:'old',syncedAt:null,contentReady:true,graphReady:true,vectorsReady:true} });
    expect(wrapper.findAll('[data-ready="true"]')).toHaveLength(0);
    expect(wrapper.text()).not.toContain('打开此分支代码图谱');
    wrapper.unmount();
  });
  it('hides maintenance actions for read-only project members', () => {
    const wrapper=panel({canMaintain:false});
    expect(wrapper.findAll('.index-actions button').map(button => button.text())).toEqual(['打开代码']);
    wrapper.unmount();
  });
  it('blocks submissions while a task is running and labels historical tasks', () => {
    const wrapper=panel({ jobs:[{id:'j',branchId:'b',kind:'GRAPH',status:'RUNNING',stage:'GRAPH',snapshotId:'old',error:null}] });
    expect(wrapper.findAll('.index-actions button').filter(button => button.text() !== '打开代码').every(b=>b.attributes('disabled')!==undefined)).toBe(true);
    expect(wrapper.text()).toContain('历史版本任务');
    wrapper.unmount();
  });
});
