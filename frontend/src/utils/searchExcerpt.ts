export function searchExcerpt(content: string, query: string, limit = 180) {
  const text = content.replace(/\s+/g, ' ').trim();
  const terms = [...new Set(query.trim().split(/\s+/).filter(Boolean))].sort((a, b) => b.length - a.length);
  const positions = terms.map(term => text.toLocaleLowerCase().indexOf(term.toLocaleLowerCase())).filter(index => index >= 0);
  const start = positions.length ? Math.max(0, Math.min(...positions) - 45) : 0;
  const excerpt = `${start ? '…' : ''}${text.slice(start, start + limit)}${text.length > start + limit ? '…' : ''}`;
  if (!terms.length) return [{ text: excerpt, match: false }];
  const pattern = new RegExp(`(${terms.map(term => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'gi');
  return excerpt.split(pattern).filter(Boolean).map(part => ({ text: part, match: terms.some(term => term.toLocaleLowerCase() === part.toLocaleLowerCase()) }));
}
