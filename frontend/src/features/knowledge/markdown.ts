import DOMPurify from 'dompurify';
import { marked } from 'marked';

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
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
}
