import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import type { Answer, Citation } from '@/api/intelligence';
import AnswerEvidencePanel from './AnswerEvidencePanel.vue';
import CitationExcerpt from './CitationExcerpt.vue';
import AnswerContent from './AnswerContent.vue';
import AskConversationPanel from './AskConversationPanel.vue';

vi.mock('@/features/knowledge/mermaid', () => ({ renderMermaidBlocks: vi.fn() }));
const knowledge = {
  id: 'k1', rank: 3, repositoryId: 'repo', sourceType: 'KNOWLEDGE', title: '退款规则',
  knowledgeCardId: 'card', filePath: 'knowledge://card', content: '## 背景\n\n退款规则：七天内可以退款。',
  excerpt: '退款规则：七天内可以退款。', excerptStartLine: 3, excerptEndLine: 3,
  knowledgeRevision: 4, codeReferences: [],
} as unknown as Citation;
const code = { ...knowledge, id: 'code', rank: 5, sourceType: 'CODE', chunkId: 'chunk', filePath: 'Refund.java', content: 'class Refund {}', excerpt: 'class Refund {}' } as Citation;
const global = { stubs: { 'el-button': true, 'el-icon': true, MermaidViewerDialog: true } };

describe('answer evidence reading', () => {
  it('opens cited knowledge, keeps code collapsed and shows each source once', () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [knowledge, code] }, global });
    expect(wrapper.find('details[data-citation-rank="3"]').attributes('open')).toBeDefined();
    expect(wrapper.find('details[data-citation-rank="5"]').attributes('open')).toBeUndefined();
    expect(wrapper.findAllComponents(CitationExcerpt)).toHaveLength(2);
    expect(wrapper.text()).toContain('修订 4');
    expect(wrapper.text()).not.toContain('S3');
  });
  it('expands the saved full original without fetching the latest revision', async () => {
    const wrapper = mount(CitationExcerpt, { props: { citation: knowledge }, global });
    expect(wrapper.text()).not.toContain('背景');
    await wrapper.get('button.expand-original').trigger('click');
    expect(wrapper.text()).toContain('背景');
    await wrapper.get('button.expand-original').trigger('click');
    expect(wrapper.text()).not.toContain('背景');
  });
  it('renders legacy citations without pretending to have a captured excerpt', () => {
    const wrapper = mount(CitationExcerpt, { props: { citation: { ...knowledge, excerpt: undefined } }, global });
    expect(wrapper.text()).toContain('历史原文快照');
    expect(wrapper.text()).toContain('背景');
  });
  it('emits the actual citation rank when a readable title is clicked', async () => {
    const wrapper = mount(AnswerContent, { props: { answer: '规则 [S3]。', citations: [knowledge] } });
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('locateCitation')).toEqual([[3]]);
  });
  it('locates and opens the citation in the correct conversation turn', async () => {
    const scroll = vi.fn();
    const previous = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView = scroll;
    const turn = (id: string): Answer => ({ conversationId: id, question: '退款', answer: '实现 [S5]。', citations: [code],
      threadId: 'thread', repositoryId: 'repo', title: '退款', contentVersion: null, branchId: null, branchName: null,
      commitSha: null, fallbackReason: null, citationAssessment: null,
      retrieval: { recalledCount: 1, durationMs: 1, degraded: false, enabledChannels: [], unavailableChannels: [], channelMetrics: [],
        contentVersion: null, vectorModel: null, retrievalCapability: null, degradationReasons: [] },
      provider: 'model', evidenceStatus: 'CITATION_COMPLETE', createdAt: new Date().toISOString(), turnNo: 1 });
    const wrapper = mount(AskConversationPanel, { props: {
      modelValue: '', turns: [turn('first'), turn('second')], activeAnswerId: null,
      pendingQuestion: '', requestState: 'idle', error: null,
    }, global: { stubs: { ...global.stubs, 'el-input': true, 'el-empty': true, AskRequestProgress: true } } });
    try {
      await wrapper.get('[data-answer-id="second"] button.answer-citation').trigger('click');
      expect(wrapper.get('[data-answer-id="first"] details[data-citation-rank="5"]').attributes('open')).toBeUndefined();
      expect(wrapper.get('[data-answer-id="second"] details[data-citation-rank="5"]').attributes('open')).toBeDefined();
      expect(scroll).toHaveBeenCalledOnce();
      scroll.mockClear();
      await wrapper.setProps({ requestState: 'sending' });
      await wrapper.setProps({ requestState: 'succeeded', turns: [turn('first'), turn('second'), turn('third')] });
      await flushPromises();
      expect(scroll).toHaveBeenCalledWith({ block: 'start', behavior: 'auto' });
    } finally {
      wrapper.unmount();
      HTMLElement.prototype.scrollIntoView = previous;
    }
  });
});
