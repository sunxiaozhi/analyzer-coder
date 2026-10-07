import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { describe, expect, it } from 'vitest';
import MarkdownPreview from './MarkdownPreview.vue';
import KnowledgeCardDetailDialog from '@/features/knowledge/KnowledgeCardDetailDialog.vue';
import KnowledgeMarkdownEditor from '@/features/knowledge/KnowledgeMarkdownEditor.vue';
import type { KnowledgeCard } from '@/api/intelligence';

const content = `# 开发规范

正文包含 **重点** 和 \`inline_code\`。

| 字段 | 类型 |
| --- | ---: |
| id | string |

1. 第一项
   - 子项
     - 深层子项
2. 第二项

> 引用第一段
>
> 引用第二段

~~~typescript
const message = "<table>原样代码</table>";
~~~

- [x] 已完成
- [ ] 待处理
`;

const card = {
  id: 'card-1', repositoryId: 'repo-1', title: '开发规范', content,
  // The detail view should render the preserved Markdown, just like the source preview.
  renderedContent: '<p>旧的渲染结果</p>', publicationStatus: 'DRAFT', revision: 1,
  knowledgeKind: 'REFERENCE', enforcement: 'REFERENCE', tags: [], attachments: [],
  codeReferences: [], scope: { pathPatterns: [], symbols: [] }, updatedAt: '2026-10-01T00:00:00Z',
  sourceVersionStatus: 'UNVERIFIED', ownerAccountId: null,
} as unknown as KnowledgeCard;

describe('consistent Markdown presentation', () => {
  it('renders file and generated card content with identical headings, tables, nested lists and code blocks', () => {
    const file = mount(MarkdownPreview, { props: { content, repositoryId: 'repo-1' } });
    const detail = mount(KnowledgeCardDetailDialog, {
      props: { modelValue: true, card, driftEvent: null, driftLoading: false, canMaintain: false, sourceReviewLoading: false },
      global: { plugins: [createPinia()], stubs: {
        ElDrawer: { template: '<aside><slot /><slot name="header" /></aside>' },
        ElButton: { template: '<button><slot /></button>' }, ElTag: true,
        KnowledgeAttachmentList: true, KnowledgeBranchValidationPanel: true,
      } },
    });
    expect(detail.get('.markdown-body').html()).toBe(file.get('.markdown-body').html());
    expect(detail.get('.markdown-body h1').text()).toBe('开发规范');
    expect(detail.findAll('.markdown-table-scroll tbody tr')).toHaveLength(1);
    expect(detail.findAll('.markdown-body ol ul ul li')).toHaveLength(1);
    expect(detail.get('.markdown-body pre code').text()).toContain('<table>原样代码</table>');
    expect(detail.findAll('.markdown-body input[type="checkbox"]')).toHaveLength(2);
    expect(detail.text()).not.toContain('旧的渲染结果');
    detail.unmount(); file.unmount();
  });

  it('uses the same document rendering in the editor preview', async () => {
    const editor = mount(KnowledgeMarkdownEditor, { props: { modelValue: content, repositoryId: 'repo-1' }, global: { stubs: { ElInput: true } } });
    await editor.get('button[aria-pressed="false"]').trigger('click');
    const file = mount(MarkdownPreview, { props: { content, repositoryId: 'repo-1' } });
    expect(editor.get('.markdown-body').html()).toBe(file.get('.markdown-body').html());
    editor.unmount(); file.unmount();
  });

  it('resolves attachment images without changing fenced code or admitting unsafe HTML', () => {
    const id = '12345678-1234-1234-1234-123456789abc';
    const body = `![附件](knowledge-attachment://${id})

\`\`\`text
knowledge-attachment://${id}
\`\`\`

<script>alert(1)</script><img src="https://example.com/image.png" onerror="alert(1)">
[危险链接](javascript:alert(1))`;
    const wrapper = mount(MarkdownPreview, { props: { content: body, repositoryId: 'repo-1', embedded: true } });
    expect(wrapper.get('img[alt="附件"]').attributes('src')).toBe(`/api/repositories/repo-1/knowledge/attachments/${id}`);
    expect(wrapper.get('pre code').text()).toContain(`knowledge-attachment://${id}`);
    expect(wrapper.find('script').exists()).toBe(false);
    expect(wrapper.find('[onerror]').exists()).toBe(false);
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
