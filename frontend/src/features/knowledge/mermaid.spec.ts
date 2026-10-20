import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderMermaidBlocks } from './mermaid';
import { flushPromises, mount } from '@vue/test-utils';
import MarkdownPreview from '@/components/MarkdownPreview.vue';
import MermaidViewerDialog from '@/components/MermaidViewerDialog.vue';

const { render, initialize } = vi.hoisted(() => ({ render: vi.fn(), initialize: vi.fn() }));
vi.mock('mermaid', () => ({ default: { render, initialize } }));

function documentWith(source: string) {
  const root = document.createElement('article');
  const pre = document.createElement('pre');
  const code = document.createElement('code');
  code.className = 'language-mermaid';
  code.textContent = source;
  pre.append(code); root.append(pre);
  return root;
}

describe('Mermaid Markdown diagrams', () => {
  beforeEach(() => { render.mockReset(); });

  it('renders decoded source and sanitizes the generated SVG while preserving labels', async () => {
    render.mockResolvedValue({ svg: '<svg xmlns="http://www.w3.org/2000/svg"><text>开始</text><script>alert(1)</script><foreignObject><div xmlns="http://www.w3.org/1999/xhtml">标签</div></foreignObject></svg>' });
    const root = documentWith('graph TD; A[开始] --> B[完成]');
    await renderMermaidBlocks(root, () => true);
    expect(render.mock.calls[0][1]).toBe('graph TD; A[开始] --> B[完成]');
    expect(root.querySelector('figure svg text')?.textContent).toBe('开始');
    expect(root.querySelector('script')).toBeNull();
    expect(root.querySelector('pre')).toBeNull();
    expect(root.children).toHaveLength(1);
  });

  it('keeps invalid source and continues rendering subsequent diagrams', async () => {
    render.mockRejectedValueOnce(new Error('parse error')).mockResolvedValueOnce({ svg: '<svg><text>OK</text></svg>' });
    const root = documentWith('invalid diagram');
    root.append(...documentWith('graph TD; A-->B').children);
    await renderMermaidBlocks(root, () => true);
    expect(root.querySelector('pre code')?.textContent).toBe('invalid diagram');
    expect(root.querySelector('[role="status"]')?.textContent).toContain('原始内容已保留');
    expect(root.querySelectorAll('figure svg')).toHaveLength(1);
  });

  it('does not replace stale content when a render finishes after switching cards', async () => {
    let resolve!: (value: { svg: string }) => void;
    render.mockReturnValue(new Promise(done => { resolve = done; }));
    const root = documentWith('graph TD; A-->B');
    let current = true;
    const pending = renderMermaidBlocks(root, () => current);
    await vi.waitFor(() => expect(render).toHaveBeenCalled());
    current = false;
    resolve({ svg: '<svg></svg>' });
    await pending;
    expect(root.querySelector('figure')).toBeNull();
    expect(root.children).toHaveLength(1);
  });

  it('renders diagrams on mount and redraws when knowledge content changes', async () => {
    render.mockResolvedValue({ svg: '<svg><text>图表</text></svg>' });
    const wrapper = mount(MarkdownPreview, { props: { content: '```mermaid\ngraph TD; A-->B\n```' } });
    await flushPromises();
    expect(wrapper.find('.markdown-mermaid svg').exists()).toBe(true);
    await wrapper.get('.markdown-mermaid-expand').trigger('click');
    expect(wrapper.getComponent(MermaidViewerDialog).props('modelValue')).toBe(true);
    expect(wrapper.getComponent(MermaidViewerDialog).props('svg')).toContain('图表');
    await wrapper.setProps({ content: '```mermaid\nsequenceDiagram\nA->>B: hello\n```' });
    await flushPromises();
    expect(render).toHaveBeenCalledTimes(2);
    expect(render.mock.calls[1][1]).toContain('sequenceDiagram');
    expect(wrapper.findAll('.markdown-mermaid')).toHaveLength(1);
    expect(wrapper.findComponent(MermaidViewerDialog).exists()).toBe(false);
    await wrapper.setProps({ content: '普通正文' });
    await flushPromises();
    expect(wrapper.find('svg').exists()).toBe(false);
    wrapper.unmount();
  });
});
