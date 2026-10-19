import { flushPromises, shallowMount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import KnowledgeCardEditorDialog from './KnowledgeCardEditorDialog.vue';
import KnowledgeCardContentSection from './KnowledgeCardContentSection.vue';
import KnowledgeConflictReview from './KnowledgeConflictReview.vue';
import { knowledgeCardInput } from './useKnowledgeCardEditor';
import type { KnowledgeCard } from '@/api/intelligence';
import KnowledgeCodeAssociationSection from './KnowledgeCodeAssociationSection.vue';
import { useWorkspaceLeaveGuardStore } from '@/stores/workspaceLeaveGuard';

vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal<typeof import('element-plus')>(),
  ElMessageBox: { confirm: vi.fn() }, ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
}));
beforeEach(() => { vi.resetAllMocks(); setActivePinia(createPinia()); });
const confirmed = () => Object.assign('confirm', { value: '', action: 'confirm' as const });
function mountEditor() {
  return shallowMount(KnowledgeCardEditorDialog, {
    props: { modelValue: true, repositoryId: 'repo', card: null, busy: false, branchId: 'main' },
    global: { stubs: {
      ElDialog: { name: 'ElDialog', props: ['modelValue', 'beforeClose'], template: '<div><slot/><slot name="footer"/></div>' },
      ElForm: { template: '<div><slot/></div>' }, ElAlert: true,
      ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
    } },
  });
}
async function edit(wrapper: ReturnType<typeof mountEditor>) {
  wrapper.findAllComponents(KnowledgeCardContentSection)[0].vm.$emit('update:title', '未保存的业务规则');
  await flushPromises();
}
it('closes an unchanged form without a prompt', async () => {
  const wrapper = mountEditor();
  await wrapper.findAll('button')[0].trigger('click');
  expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
  expect(ElMessageBox.confirm).not.toHaveBeenCalled();
  wrapper.unmount();
});
it('retains edits on cancel/escape and only closes after explicitly discarding', async () => {
  const wrapper = mountEditor(); await edit(wrapper);
  vi.mocked(ElMessageBox.confirm).mockResolvedValue(confirmed());
  await wrapper.findAll('button')[0].trigger('click'); await flushPromises();
  expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  expect(wrapper.findAllComponents(KnowledgeCardContentSection)[0].props('title')).toBe('未保存的业务规则');
  const done = vi.fn();
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('close');
  await wrapper.getComponent({ name: 'ElDialog' }).props('beforeClose')(done);
  expect(done).not.toHaveBeenCalled();
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
  await wrapper.getComponent({ name: 'ElDialog' }).props('beforeClose')(done);
  expect(done).toHaveBeenCalledOnce();
  expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
  wrapper.unmount();
});
it('guards navigation, browser refresh and code association edits, and unregisters on unmount', async () => {
  const wrapper = mountEditor();
  wrapper.getComponent(KnowledgeCodeAssociationSection).vm.$emit('update:references', [{ chunkId: 'new-reference' }]);
  await flushPromises();
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true);
  vi.mocked(ElMessageBox.confirm).mockResolvedValue(confirmed());
  expect(await useWorkspaceLeaveGuardStore().requestLeave()).toBe(false);
  expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  wrapper.unmount();
  expect(await useWorkspaceLeaveGuardStore().requestLeave()).toBe(true);
  const clean = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(clean); expect(clean.defaultPrevented).toBe(false);
});
it('blocks leaving while saving and keeps protection after a failed save', async () => {
  const wrapper = mountEditor(); await edit(wrapper);
  await wrapper.setProps({ busy: true });
  expect(await useWorkspaceLeaveGuardStore().requestLeave()).toBe(false);
  expect(ElMessage.warning).toHaveBeenCalled();
  expect(ElMessageBox.confirm).not.toHaveBeenCalled();
  await wrapper.setProps({ busy: false, saveError: '保存失败' });
  vi.mocked(ElMessageBox.confirm).mockResolvedValue(confirmed());
  expect(await useWorkspaceLeaveGuardStore().requestLeave()).toBe(false);
  expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  await wrapper.setProps({ modelValue: false });
  expect(await useWorkspaceLeaveGuardStore().requestLeave()).toBe(true);
  wrapper.unmount();
});
it('opens only one confirmation when competing leave actions are clicked', async () => {
  const wrapper = mountEditor(); await edit(wrapper);
  let finish!: () => void;
  vi.mocked(ElMessageBox.confirm).mockImplementationOnce(() => new Promise(resolve => { finish = () => resolve(confirmed()); }));
  const pending = useWorkspaceLeaveGuardStore().requestLeave();
  expect(await useWorkspaceLeaveGuardStore().requestLeave()).toBe(false);
  expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
  finish(); expect(await pending).toBe(false);
  wrapper.unmount();
});

it('applies reviewed changes without resetting the editor and uses the new revision on save and the next conflict', async () => {
  const base = { id: 'card', revision: 1, title: '原题', cardType: '知识卡片', content: '原文', tags: [], knowledgeKind: 'REFERENCE', severity: 'INFO', enforcement: 'REFERENCE', ownerAccountId: null, scope: { pathPatterns: [], symbols: [], modules: [] }, obligations: { requiredTests: [], requiredApproverAccountIds: [], instructions: [], prohibitedPathPatterns: [], knowledgeUpdateRequired: false }, attachments: [], codeReferences: [] } as unknown as KnowledgeCard;
  const wrapper = mountEditor(); await wrapper.setProps({ card: base, saveConflict: true }); await edit(wrapper);
  const latest = { ...base, revision: 2, content: '他人更新的正文' };
  const merged = { ...knowledgeCardInput(latest), title: '未保存的业务规则' };
  wrapper.getComponent(KnowledgeConflictReview).vm.$emit('resolve', merged, latest); await flushPromises();
  expect(wrapper.getComponent(KnowledgeConflictReview).props('base').revision).toBe(2);
  const content = wrapper.findAllComponents(KnowledgeCardContentSection)[0];
  expect(content.props('title')).toBe('未保存的业务规则'); expect(content.props('content')).toBe('他人更新的正文');
  await wrapper.setProps({ saveConflict: false });
  await wrapper.findAll('button').at(-1)!.trigger('click');
  expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({ expectedRevision: 2, title: '未保存的业务规则', content: '他人更新的正文' });
  expect(wrapper.emitted('conflictResolved')).toHaveLength(1);
  wrapper.unmount();
});

it('blocks automatic context refresh while dirty or uploading without prompting or closing', async () => {
  const wrapper = mountEditor();
  const guard = useWorkspaceLeaveGuardStore();
  expect(guard.canRefreshAutomatically()).toBe(true);
  await edit(wrapper);
  expect(guard.canRefreshAutomatically()).toBe(false);
  expect(ElMessageBox.confirm).not.toHaveBeenCalled(); expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  await wrapper.setProps({ modelValue: false });
  expect(guard.canRefreshAutomatically()).toBe(true);
  wrapper.unmount();
});
