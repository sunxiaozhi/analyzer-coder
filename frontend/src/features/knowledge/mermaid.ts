import DOMPurify from 'dompurify';

let sequence = 0;
let renderer: Promise<typeof import('mermaid')['default']> | undefined;

function loadRenderer() {
  renderer ??= import('mermaid').then(({ default: mermaid }) => {
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, htmlLabels: false });
    return mermaid;
  }).catch(error => {
    renderer = undefined;
    throw error;
  });
  return renderer;
}

/** Render only fenced Mermaid blocks in this preview; keep source on failure. */
export async function renderMermaidBlocks(root: HTMLElement, isCurrent: () => boolean) {
  const blocks = [...root.querySelectorAll<HTMLElement>('pre > code')]
    .filter(code => [...code.classList].some(name => name.toLowerCase() === 'language-mermaid'));
  if (!blocks.length) return;
  for (const code of blocks) {
    if (!isCurrent()) return;
    const pre = code.parentElement!;
    const staging = document.createElement('div');
    staging.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none';
    pre.after(staging);
    try {
      const mermaid = await loadRenderer();
      if (!isCurrent()) return;
      const { svg } = await mermaid.render(`knowledge-mermaid-${++sequence}`, code.textContent ?? '', staging);
      if (!isCurrent()) return;
      const figure = document.createElement('figure');
      figure.className = 'markdown-mermaid';
      figure.setAttribute('aria-label', 'Mermaid 图表');
      figure.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { html: true, svg: true, svgFilters: true } });
      const expand = document.createElement('button');
      expand.type = 'button';
      expand.className = 'markdown-mermaid-expand';
      expand.textContent = '放大查看';
      expand.setAttribute('aria-label', '放大查看 Mermaid 图表');
      figure.append(expand);
      pre.replaceWith(figure);
    } catch {
      if (!isCurrent()) return;
      const message = document.createElement('p');
      message.className = 'markdown-mermaid-error';
      message.setAttribute('role', 'status');
      message.textContent = '图表暂时无法显示，请检查 Mermaid 语法；原始内容已保留。';
      pre.before(message);
    } finally {
      staging.remove();
    }
  }
}
