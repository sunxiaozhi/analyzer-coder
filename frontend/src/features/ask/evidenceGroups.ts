import type { Citation } from '@/api/intelligence';

export interface EvidenceMember { citation: Citation; ranks: number[]; cited: boolean }
export interface EvidenceGroup {
  key: string;
  members: EvidenceMember[];
  cited: boolean;
  sourcePath: string | null;
  differentContent: boolean;
  otherVersions: boolean;
  missingMarkdown: boolean;
}
export interface CitationLocation { rank: number; sequence: number }
export const isMarkdown = (path: string) => /\.(md|markdown)$/i.test(path);

/** Only persisted provenance establishes conversion; a title or code binding is insufficient. */
function origin(citation: Citation, branchId?: string | null) {
  if (citation.sourceType === 'CODE') {
    return isMarkdown(citation.filePath) && branchId && citation.contentVersion
      ? { path: citation.filePath, branch: branchId, version: citation.contentVersion } : null;
  }
  const sources = (citation.sources ?? []).filter(source => source.sourceType === 'MARKDOWN'
    && isMarkdown(source.filePath) && source.branchId && source.contentVersion);
  const unique = [...new Map(sources.map(source => [JSON.stringify([source.filePath, source.branchId, source.contentVersion]), source])).values()];
  // Multiple origins must not bridge separate branches or versions into a single group.
  if (unique.length !== 1) return null;
  return { path: unique[0].filePath, branch: unique[0].branchId!, version: unique[0].contentVersion! };
}

export function groupEvidence(citations: Citation[], related: Citation[] = [], branchId?: string | null): EvidenceGroup[] {
  const groups = new Map<string, EvidenceGroup>();
  for (const [items, cited] of [[citations, true], [related, false]] as const) {
    for (const citation of items) {
      const source = origin(citation, branchId);
      const identity = citation.sourceType === 'KNOWLEDGE'
        ? [citation.knowledgeCardId || citation.id, citation.knowledgeRevision ?? citation.content, citation.sourceScope]
        : [citation.chunkId || citation.id, citation.contentVersion];
      const key = JSON.stringify(source ? [citation.repositoryId, 'markdown', source.path, source.branch, source.version]
        : [citation.repositoryId, citation.sourceType, ...identity]);
      let group = groups.get(key);
      if (!group) {
        group = { key, members: [], cited: false, sourcePath: source?.path ?? null,
          differentContent: false, otherVersions: false, missingMarkdown: false };
        groups.set(key, group);
      }
      const same = group.members.find(member => member.citation.sourceType === citation.sourceType
        && (citation.sourceType === 'KNOWLEDGE' ? member.citation.knowledgeCardId === citation.knowledgeCardId : member.citation.chunkId === citation.chunkId)
        && member.citation.content === citation.content && member.citation.excerpt === citation.excerpt
        && member.citation.knowledgeRevision === citation.knowledgeRevision && member.citation.sourceScope === citation.sourceScope);
      if (same) {
        if (!same.ranks.includes(citation.rank)) same.ranks.push(citation.rank);
        same.cited ||= cited;
      } else group.members.push({ citation, ranks: [citation.rank], cited });
      group.cited ||= cited;
    }
  }
  const result = [...groups.values()];
  for (const group of result) {
    group.members.sort((a, b) => Number(b.citation.sourceType === 'KNOWLEDGE') - Number(a.citation.sourceType === 'KNOWLEDGE')
      || Number(b.cited) - Number(a.cited));
    group.differentContent = new Set(group.members.map(member => member.citation.content)).size > 1;
    group.otherVersions = Boolean(group.sourcePath && result.some(other => other !== group
      && other.sourcePath === group.sourcePath && other.members[0].citation.repositoryId === group.members[0].citation.repositoryId));
    group.missingMarkdown = group.members.some(member => (member.citation.sources ?? []).some(source => source.sourceType === 'MARKDOWN'))
      && !group.members.some(member => member.citation.sourceType === 'CODE' && isMarkdown(member.citation.filePath));
  }
  return result;
}

export function citationMarkdown(citation: Citation, text: string) {
  if (citation.sourceType === 'KNOWLEDGE' || isMarkdown(citation.filePath)) return text;
  const extension = citation.filePath.split('.').pop()?.toLowerCase() ?? '';
  const language = ({ ts: 'typescript', js: 'javascript', py: 'python', sh: 'bash', yml: 'yaml' } as Record<string, string>)[extension] ?? extension;
  const fence = '`'.repeat([...text.matchAll(/`+/g)].reduce((size, match) => Math.max(size, match[0].length + 1), 3));
  return `${fence}${/^[a-z0-9]+$/.test(language) ? language : ''}\n${text}\n${fence}`;
}
