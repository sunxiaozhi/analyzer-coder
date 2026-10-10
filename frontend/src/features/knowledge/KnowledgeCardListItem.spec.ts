import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import KnowledgeCardListItem from './KnowledgeCardListItem.vue';
import type { KnowledgeCard } from '@/api/intelligence';

function mountCard(status: KnowledgeCard['publicationStatus'], canManage = true, deleting = false) {
  const card: KnowledgeCard = {
    id: 'card', repositoryId: 'repo', title: '规则', cardType: '规则', content: '正文', renderedContent: '<p>正文</p>',
    tags: [], knowledgeKind: 'BUSINESS_RULE', severity: 'INFO', enforcement: 'REFERENCE', ownerAccountId: null,
    scope: { pathPatterns: [], symbols: [], modules: [] },
    obligations: { requiredTests: [], requiredApproverAccountIds: [], instructions: [], prohibitedPathPatterns: [], knowledgeUpdateRequired: false },
    lastVerifiedContentVersion: null, verificationNote: null, publicationStatus: status, revision: 1,
    createdAt: '2026-10-10T00:00:00Z', updatedAt: '2026-10-10T00:00:00Z', verifiedCommit: null,
    attachments: [], codeReferences: [], sourceVersionStatus: 'UNVERIFIED', sourceVersionCheckedAt: null, reviewStatus: 'UNREVIEWED', reviewedBy: null, reviewedAt: null,
  };
  return mount(KnowledgeCardListItem, {
    props: { card, canManage, canMaintain: true, scopeLabel: '仅 main 可见', deleting },
    global: { stubs: {
      ElTag: { template: '<span><slot /></span>' },
      ElButton: { template: '<button><slot /></button>' },
      ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
      ElDropdownMenu: { template: '<div><slot /></div>' },
      ElDropdownItem: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
    } },
  });
}
describe('knowledge draft deletion', () => {
  it('offers draft deletion to managers and emits the exact card', async () => {
    const wrapper = mountCard('DRAFT');
    await wrapper.findAll('button').find(button => button.text() === '删除草稿')!.trigger('click');
    expect(wrapper.emitted('delete')?.[0]).toEqual([wrapper.props('card')]);
  });
  it.each(['PUBLISHED', 'ARCHIVED'] as const)('requires withdrawal before deleting %s knowledge', async status => {
    const wrapper = mountCard(status);
    expect(wrapper.text()).not.toContain('删除草稿');
    await wrapper.findAll('button').find(button => button.text() === '撤回为草稿')!.trigger('click');
    expect(wrapper.emitted('publish')?.[0]).toEqual([wrapper.props('card'), 'DRAFT']);
  });
  it('does not offer deletion without manage permission', () => {
    expect(mountCard('DRAFT', false).text()).not.toContain('删除草稿');
  });
  it('disables the delete action while its request is pending', () => {
    const wrapper = mountCard('DRAFT', true, true);
    const button = wrapper.findAll('button').find(button => button.text() === '正在删除…')!;
    expect(button.attributes('disabled')).toBeDefined();
  });
});
