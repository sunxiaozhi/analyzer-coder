import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { KnowledgeCard, KnowledgeDriftEvent } from '@/api/intelligence';
import KnowledgeDriftPanel from './KnowledgeDriftPanel.vue';

const card = {
  sourceVersionStatus: 'SUSPECT',
} as KnowledgeCard;
const event = {
  id: 'event-1',
  repositoryId: 'repo-1',
  cardId: 'card-1',
  cardRevision: 4,
  fromSnapshotId: 'snapshot-old',
  toSnapshotId: 'snapshot-new',
  fromCommit: '1111111111111111',
  toCommit: '2222222222222222',
  previousStatus: 'CURRENT',
  resultStatus: 'SUSPECT',
  triggerType: 'AUTOMATIC_DIFF',
  reasons: [{
    kind: 'PATH_SCOPE_MATCHED',
    rule: 'src/payment/**',
    filePath: 'src/payment/RefundService.java',
    startLine: 10,
    endLine: 12,
    changeType: 'MODIFIED',
    detail: '真实 Git 变更路径命中知识 Scope',
  }],
  note: '等待人工重新验证',
  actorId: null,
  createdAt: '2026-08-30T08:00:00Z',
} satisfies KnowledgeDriftEvent;

describe('KnowledgeDriftPanel', () => {
  it('shows structured drift evidence and opens the exact commit range', async () => {
    const wrapper = mount(KnowledgeDriftPanel, {
      props: { card, event, loading: false, canMaintain: true, reviewing: false },
    });

    expect(wrapper.text()).toContain('路径范围命中');
    expect(wrapper.text()).toContain('src/payment/RefundService.java:10');
    expect(wrapper.text()).toContain('11111111');
    expect(wrapper.text()).toContain('22222222');

    await wrapper.get('.diff-link').trigger('click');
    expect(wrapper.emitted('openDiff')).toEqual([[event]]);
  });

  it('offers maintainers explicit confirmation actions', async () => {
    const wrapper = mount(KnowledgeDriftPanel, {
      props: { card, event, loading: false, canMaintain: true, reviewing: false },
    });
    const buttons = wrapper.findAll('footer el-button');
    expect(buttons).toHaveLength(2);
    await buttons[0].trigger('click');
    await buttons[1].trigger('click');
    expect(wrapper.emitted('review')).toEqual([['CONFIRM_CURRENT'], ['MARK_STALE']]);
  });
});
