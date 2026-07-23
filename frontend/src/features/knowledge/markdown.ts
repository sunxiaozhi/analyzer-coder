const escapeHtml = (value: string) => value
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;').replaceAll("'", '&#39;');

function safeUrl(value: string, repositoryId: string) {
  if (/^knowledge-attachment:\/\/[0-9a-f-]{36}$/i.test(value)) {
    return `/api/repositories/${repositoryId}/knowledge/attachments/${value.slice('knowledge-attachment://'.length)}`;
  }
  return /^https?:\/\//i.test(value) ? value : '#';
}

function inline(value: string, repositoryId: string) {
  return escapeHtml(value)
    .replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt, url) => {
      const href = safeUrl(url, repositoryId);
      return href === '#' ? `<span class="invalid-media">[不安全的图片地址]</span>` :
        `<img src="${href}" alt="${alt}" loading="lazy">`;
    })
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, url) =>
      `<a href="${safeUrl(url, repositoryId)}" target="_blank" rel="noopener noreferrer">${label}</a>`)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/~~([^~]+)~~/g, '<del>$1</del>')
    .replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>');
}

export function renderMarkdown(markdown: string, repositoryId: string) {
  const blocks: string[] = [];
  const tokenized = markdown.replace(/```([^\n]*)\n([\s\S]*?)```/g, (_, language, code) => {
    const token = `@@CODE_${blocks.length}@@`;
    blocks.push(`<pre><code data-language="${escapeHtml(language.trim())}">${escapeHtml(code)}</code></pre>`);
    return token;
  });
  const lines = tokenized.split(/\r?\n/);
  const output: string[] = [];
  let list: 'ul' | 'ol' | null = null;
  const closeList = () => { if (list) output.push(`</${list}>`); list = null; };
  for (const line of lines) {
    const code = line.match(/^@@CODE_(\d+)@@$/);
    if (code) { closeList(); output.push(blocks[Number(code[1])]); continue; }
    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) { closeList(); const level = heading[1].length; output.push(`<h${level}>${inline(heading[2], repositoryId)}</h${level}>`); continue; }
    const item = line.match(/^(\s*)([-*+]|\d+\.)\s+(.+)$/);
    if (item) {
      const next = /\d+\./.test(item[2]) ? 'ol' : 'ul';
      if (list !== next) { closeList(); list = next; output.push(`<${list}>`); }
      output.push(`<li>${inline(item[3], repositoryId)}</li>`); continue;
    }
    closeList();
    if (!line.trim()) { output.push(''); continue; }
    if (line.startsWith('> ')) output.push(`<blockquote>${inline(line.slice(2), repositoryId)}</blockquote>`);
    else output.push(`<p>${inline(line, repositoryId)}</p>`);
  }
  closeList();
  return output.join('\n');
}
