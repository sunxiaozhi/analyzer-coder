import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import { branchesApi, type BranchContext, type BranchValidationCard } from '@/api/branches';
import KnowledgeBranchValidationPanel from './KnowledgeBranchValidationPanel.vue';

vi.mock('@/api/branches', () => ({ branchesApi: { validations: vi.fn(), validate: vi.fn() } }));
afterEach(() => vi.resetAllMocks());
const context: BranchContext = { repositoryId: 'repo', contextId: 'ctx', branchId: 'branch', branchName: 'release', contentVersion: 'contentVersion', commitSha: 'abcdef123456789', expiresAt: '' };
const card: BranchValidationCard = { cardId: 'card', revision: 3, title: '退款规则', content: '检查退款事务', state: 'UNVERIFIED', note: '' };

it('requires a note and saves the selected revision against the pinned context', async () => {
  vi.mocked(branchesApi.validations).mockResolvedValue([card]);
  vi.mocked(branchesApi.validate).mockResolvedValue(undefined);
  const wrapper = mount(KnowledgeBranchValidationPanel, { props: { context, canManage: true }, global: { plugins: [ElementPlus] } });
  await flushPromises();
  await wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'card');
  await flushPromises();
  expect(wrapper.text()).toContain(card.content);
  const submit = wrapper.get('button[type="submit"]');
  expect(submit.attributes('disabled')).toBeDefined();
  await wrapper.get('textarea').setValue('已检查当前内容版本的事务边界');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(branchesApi.validate).toHaveBeenCalledWith(context, card, 'UNVERIFIED', '已检查当前内容版本的事务边界');
  expect(wrapper.text()).toContain('验证结果已保存');
  wrapper.unmount();
});

it('ignores a late response after the branch context changes', async () => {
  let resolveOld!: (rows: BranchValidationCard[]) => void;
  vi.mocked(branchesApi.validations).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce([]);
  const wrapper = mount(KnowledgeBranchValidationPanel, { props: { context, canManage: false }, global: { plugins: [ElementPlus] } });
  await wrapper.setProps({ context: { ...context, contextId: 'new-context', branchName: 'main' } });
  await flushPromises();
  resolveOld([card]);
  await flushPromises();
  expect(wrapper.text()).toContain('0 条适用知识');
  expect(wrapper.text()).not.toContain('保存验证结果');
  expect(branchesApi.validate).not.toHaveBeenCalled();
  wrapper.unmount();
});


it('auto-selects the detail revision and emits saved with the pinned branch identity', async () => {
  vi.mocked(branchesApi.validations).mockResolvedValue([card, { ...card, cardId: 'other', title: '其他知识' }]);
  vi.mocked(branchesApi.validate).mockResolvedValue(undefined);
  const wrapper = mount(KnowledgeBranchValidationPanel, { props: { context, canManage: true, cardId: card.cardId, cardRevision: card.revision }, global: { plugins: [ElementPlus] } });
  await flushPromises();
  expect(wrapper.text()).toContain(card.content);
  expect(wrapper.text()).not.toContain('其他知识');
  await wrapper.get('textarea').setValue('已对照当前分支内容版本');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.emitted('saved')).toHaveLength(1);
  expect(branchesApi.validate).toHaveBeenCalledWith(context, card, 'UNVERIFIED', '已对照当前分支内容版本');
  await wrapper.setProps({ cardRevision: card.revision + 1 });
  await flushPromises();
  expect(wrapper.find('form').exists()).toBe(false);
  wrapper.unmount();
});
