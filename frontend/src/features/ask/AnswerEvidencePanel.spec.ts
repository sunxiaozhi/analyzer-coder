import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AnswerEvidencePanel from './AnswerEvidencePanel.vue';
import EvidenceGroupCard from './EvidenceGroupCard.vue';
import CitationExcerpt from './CitationExcerpt.vue';
import CitationSnapshotDrawer from './CitationSnapshotDrawer.vue';
import MarkdownPreview from '@/components/MarkdownPreview.vue';
import AskConversationPanel from './AskConversationPanel.vue';
import { citationFixture, markdownFixture, markdownSource, answerFixture } from './evidenceFixtures';

vi.mock('@/features/knowledge/mermaid', () => ({ renderMermaidBlocks: vi.fn() }));
const global = { stubs: { MermaidViewerDialog: true, 'el-input': true, 'el-empty': true, 'el-button': { template: '<button><slot /></button>' },
  'el-drawer': { props: ['modelValue'], template: '<section v-if="modelValue"><slot name="header" /><slot /><slot name="footer" /></section>' }, AskRequestProgress: true } };
const scroll = vi.fn();
let previous: typeof HTMLElement.prototype.scrollIntoView;
beforeEach(() => { previous = HTMLElement.prototype.scrollIntoView; HTMLElement.prototype.scrollIntoView = scroll; scroll.mockClear(); });
afterEach(() => { HTMLElement.prototype.scrollIntoView = previous; });
const items = Array.from({ length: 5 }, (_, i) => citationFixture({ id: `k${i}`, knowledgeCardId: `card${i}`, rank: i + 1 }));

describe('compact answer evidence reading', () => {
  it('defaults to three short previews with explicit expansion and no mounted Markdown', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: items }, global });
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(3);
    expect(wrapper.findAllComponents(MarkdownPreview)).toHaveLength(0);
    expect(wrapper.findAll('.top-toggle').every(button => button.text() === '展开相关片段')).toBe(true);
    expect(wrapper.text()).not.toContain('后续说明');
    await wrapper.get('.evidence-heading .list-toggle').trigger('click');
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(5);
    await wrapper.get('button.list-bottom').trigger('click');
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(3);
    expect(scroll).toHaveBeenCalled();
  });
  it('expands only the saved passage and allows collapse at both ends', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [citationFixture()] }, global });
    await wrapper.get('.top-toggle').trigger('click');
    expect(wrapper.findAllComponents(MarkdownPreview)).toHaveLength(1);
    expect(wrapper.text()).not.toContain('后续说明');
    expect(wrapper.get('.top-toggle').text()).toBe('收起');
    await wrapper.get('.bottom-collapse').trigger('click');
    expect(wrapper.findAllComponents(MarkdownPreview)).toHaveLength(0);
    expect(wrapper.get('.top-toggle').attributes('aria-expanded')).toBe('false');
  });
  it('opens the historical full snapshot in a separate reader, then closes it', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [citationFixture()] }, global });
    await wrapper.get('.read-full').trigger('click');
    expect(wrapper.getComponent(CitationSnapshotDrawer).props('modelValue')).toBe(true);
    expect(wrapper.find('.snapshot-document').text()).toContain('后续说明');
    expect(wrapper.find('.snapshot-document').text()).toContain('知识修订 4');
    const close = wrapper.findAll('button').find(button => button.text() === '关闭阅读')!;
    await close.trigger('click');
    expect(wrapper.find('.snapshot-document').exists()).toBe(false);
    expect(wrapper.emitted('openKnowledge')).toBeUndefined();
  });
  it('shows one source group and switches between saved knowledge and Markdown', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [citationFixture({ sources: [markdownSource] }), markdownFixture()], branchId: 'main' }, global });
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(1);
    await wrapper.get('.top-toggle').trigger('click');
    await wrapper.findAll('.source-switch button')[1].trigger('click');
    expect(wrapper.getComponent(CitationExcerpt).props('citation').sourceType).toBe('CODE');
    expect(wrapper.findAllComponents(MarkdownPreview)).toHaveLength(1);
    expect(wrapper.text()).toContain('仅来源文档');
    expect(wrapper.text()).not.toContain('七天内可以退款');
  });
  it('reveals hidden groups, selects the cited member, and can repeat navigation after collapse', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: items }, global });
    await wrapper.setProps({ location: { rank: 5, sequence: 1 } });
    await flushPromises();
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(5);
    const target = wrapper.get('[data-citation-ranks="5"]');
    expect(target.get('.top-toggle').attributes('aria-expanded')).toBe('true');
    await target.get('.bottom-collapse').trigger('click');
    await wrapper.setProps({ location: { rank: 5, sequence: 2 } });
    await flushPromises();
    expect(target.get('.top-toggle').attributes('aria-expanded')).toBe('true');
  });
  it('selects a raw source when the answer cites its rank in a grouped card', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [citationFixture({ sources: [markdownSource] }), markdownFixture()], branchId: 'main' }, global });
    await wrapper.setProps({ location: { rank: 5, sequence: 1 } });
    await flushPromises();
    expect(wrapper.getComponent(CitationExcerpt).props('citation').id).toBe('md');
  });
  it('keeps uncited groups collapsed and supports closing them after opening', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [], relatedEvidence: items }, global });
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(0);
    await wrapper.get('.related-evidence > button').trigger('click');
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(3);
    expect(wrapper.text()).toContain('本轮回答未引用');
    await wrapper.get('.related-evidence .list-bottom').trigger('click');
    expect(wrapper.findAllComponents(EvidenceGroupCard)).toHaveLength(0);
  });
  it('selects distinct passages even when they share a citation entity id', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [citationFixture(), citationFixture({ rank: 8, excerpt: '另一个相关片段' })] }, global });
    await wrapper.setProps({ location: { rank: 8, sequence: 1 } });
    await flushPromises();
    expect(wrapper.getComponent(CitationExcerpt).props('citation').excerpt).toBe('另一个相关片段');
    await wrapper.findAll('.source-switch button')[0].trigger('click');
    expect(wrapper.getComponent(CitationExcerpt).props('citation').rank).toBe(3);
  });
  it('shows XML source literally in the collapsed preview', () => {
    const wrapper = mount(CitationExcerpt, { props: { citation: markdownFixture({ filePath: 'mapper.xml', excerpt: '<select id="find">SELECT * FROM orders</select>' }), expanded: false }, global });
    expect(wrapper.get('.excerpt-preview').text()).toBe('<select id="find">SELECT * FROM orders</select>');
    expect(wrapper.find('select').exists()).toBe(false);
  });
  it('makes missing source snapshots explicit and keeps legacy previews bounded', async () => {
    const wrapper = mount(AnswerEvidencePanel, { props: { citations: [citationFixture({ sources: [markdownSource], excerpt: undefined, content: '历史内容'.repeat(2000) })] }, global });
    expect(wrapper.text()).toContain('未保存本轮片段');
    expect(wrapper.get('.excerpt-preview').text().length).toBeLessThanOrEqual(280);
    await wrapper.get('.top-toggle').trigger('click');
    expect(wrapper.text()).toContain('未保存来源 Markdown');
    expect(wrapper.getComponent(MarkdownPreview).props('content').length).toBe(1200);
    expect(wrapper.find('.source-switch').exists()).toBe(false);
  });
  it('preserves code and Mermaid markup only when an excerpt is opened', async () => {
    const excerpt = '```mermaid\ngraph TD; A-->B\n```\n\n```java\nclass Refund {}\n```';
    const wrapper = mount(CitationExcerpt, { props: { citation: citationFixture({ excerpt }), expanded: false }, global });
    expect(wrapper.find('svg, img, pre').exists()).toBe(false);
    expect(wrapper.text()).toContain('图表，展开查看');
    expect(wrapper.text()).not.toContain('graph TD');
    await wrapper.setProps({ expanded: true });
    expect(wrapper.get('code.language-mermaid').text()).toContain('A-->B');
    expect(wrapper.find('code.language-java.hljs').exists()).toBe(true);
  });
  it('locates the citation within the correct conversation turn', async () => {
    const code = markdownFixture({ filePath: 'Refund.java' });
    const wrapper = mount(AskConversationPanel, { props: {
      modelValue: '', turns: [answerFixture('first', [code]), answerFixture('second', [code])], activeAnswerId: null,
      pendingQuestion: '', requestState: 'idle', error: null,
    }, global });
    await wrapper.get('[data-answer-id="second"] button.answer-citation').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-answer-id="first"] .top-toggle').attributes('aria-expanded')).toBe('false');
    expect(wrapper.get('[data-answer-id="second"] .top-toggle').attributes('aria-expanded')).toBe('true');
    scroll.mockClear();
    await wrapper.setProps({ requestState: 'sending' });
    await wrapper.setProps({ requestState: 'succeeded', turns: [answerFixture('first', [code]), answerFixture('second', [code]), answerFixture('third', [code])] });
    await flushPromises();
    expect(scroll).toHaveBeenCalledWith({ block: 'start', behavior: 'auto' });
    wrapper.unmount();
  });
});
