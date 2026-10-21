import type { Citation } from '@/api/intelligence';
import { renderMarkdown } from '@/features/knowledge/markdown';

/** Replace citations only in prose text after sanitizing; code examples remain literal. */
export function renderAnswerCitations(answer: string, citations: Citation[]) {
  const document = new DOMParser().parseFromString(renderMarkdown(answer), 'text/html');
  const byRank = new Map(citations.map(citation => [citation.rank, citation]));
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const nodes: Text[] = [];
  while (walker.nextNode()) nodes.push(walker.currentNode as Text);
  for (const node of nodes) {
    if (node.parentElement?.closest('code, pre, a, button')) continue;
    const text = node.textContent ?? '';
    const matches = [...text.matchAll(/\[S\s*(\d+)\s*\]/g)];
    if (!matches.length) continue;
    const fragment = document.createDocumentFragment();
    let offset = 0;
    for (const match of matches) {
      fragment.append(text.slice(offset, match.index));
      const citation = byRank.get(Number(match[1]));
      if (citation) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'answer-citation';
        button.dataset.citationRank = String(citation.rank);
        button.textContent = citation.title || citation.filePath;
        button.title = `查看引用原文：${citation.title} · ${citation.sourceScope || citation.filePath}`;
        fragment.append(button);
      } else fragment.append(match[0]);
      offset = match.index! + match[0].length;
    }
    fragment.append(text.slice(offset));
    node.replaceWith(fragment);
  }
  return document.body.innerHTML;
}
