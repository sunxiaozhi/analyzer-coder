import DOMPurify from 'dompurify';
import { marked } from 'marked';
import hljs from 'highlight.js/lib/common';
import dos from 'highlight.js/lib/languages/dos';

hljs.registerLanguage('dos', dos);

const codeLanguageAliases: Record<string, string> = {
  jsx: 'javascript', tsx: 'typescript', vue: 'xml', htm: 'xml',
  shell: 'bash', properties: 'ini', bat: 'dos', cmd: 'dos', batch: 'dos',
};

/** Use the same GFM renderer for files, knowledge cards, editor previews and revisions. */
export function renderMarkdown(markdown: string, repositoryId = '') {
  const html = marked.parse(markdown, {
    async: false,
    gfm: true,
    walkTokens(token) {
      if (token.type !== 'link' && token.type !== 'image') return;
      const attachment = token.href.match(/^knowledge-attachment:\/\/([0-9a-f-]{36})$/i);
      if (attachment && repositoryId) {
        token.href = `/api/repositories/${encodeURIComponent(repositoryId)}/knowledge/attachments/${attachment[1]}`;
      }
    },
  });
  const sanitized = DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
  const parsed = new DOMParser().parseFromString(sanitized, 'text/html');
  parsed.querySelectorAll<HTMLElement>('pre > code').forEach(code => {
    const declared = [...code.classList].find(name => name.startsWith('language-'))?.slice(9).toLowerCase();
    // Preserve plain text and Mermaid source; diagrams have their own renderer.
    if (!declared || declared === 'mermaid') return;
    const language = codeLanguageAliases[declared] ?? declared;
    if (!hljs.getLanguage(language)) return;
    code.innerHTML = hljs.highlight(code.textContent ?? '', { language, ignoreIllegals: true }).value;
    code.classList.add('hljs');
  });
  return DOMPurify.sanitize(parsed.body.innerHTML, { USE_PROFILES: { html: true } });
}
