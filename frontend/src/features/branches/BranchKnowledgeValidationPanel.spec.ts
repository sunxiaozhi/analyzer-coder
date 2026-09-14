import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import { branchesApi, type BranchContext, type BranchValidationCard } from '@/api/branches';
import BranchKnowledgeValidationPanel from './BranchKnowledgeValidationPanel.vue';

vi.mock('@/api/branches', () => ({ branchesApi: { validations: vi.fn(), validate: vi.fn() } }));
afterEach(() => vi.resetAllMocks());
const context: BranchContext = { repositoryId: 'repo', contextId: 'ctx', branchId: 'branch', branchName: 'release', snapshotId: 'snapshot', commitSha: 'abcdef123456789', expiresAt: '' };
const card: BranchValidationCard = { cardId: 'card', revision: 3, title: '退款规则', content: '检查退款事务', state: 'UNVERIFIED', note: '' };

it('requires a note and saves the selected revision against the pinned context', async () => {
  vi.mocked(branchesApi.validations).mockResolvedValue([card]);
  vi.mocked(branchesApi.validate).mockResolvedValue(undefined);
  const wrapper = mount(BranchKnowledgeValidationPanel, { props: { context, canManage: true }, global: { plugins: [ElementPlus] } });
  await flushPromises();
  await wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'card');
  await flushPromises();
  expect(wrapper.text()).toContain(card.content);
  const submit = wrapper.get('button[type="submit"]');
  expect(submit.attributes('disabled')).toBeDefined();
  await wrapper.get('textarea').setValue('已检查当前快照的事务边界');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(branchesApi.validate).toHaveBeenCalledWith(context, card, 'UNVERIFIED', '已检查当前快照的事务边界');
  expect(wrapper.text()).toContain('验证结果已保存');
  wrapper.unmount();
});

it('ignores a late response after the branch context changes', async () => {
  let resolveOld!: (rows: BranchValidationCard[]) => void;
  vi.mocked(branchesApi.validations).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce([]);
  const wrapper = mount(BranchKnowledgeValidationPanel, { props: { context, canManage: false }, global: { plugins: [ElementPlus] } });
  await wrapper.setProps({ context: { ...context, contextId: 'new-context', branchName: 'main' } });
  await flushPromises();
  resolveOld([card]);
  await flushPromises();
  expect(wrapper.text()).toContain('0 条适用知识');
  expect(wrapper.text()).not.toContain('保存验证结果');
  expect(branchesApi.validate).not.toHaveBeenCalled();
  wrapper.unmount();
});
