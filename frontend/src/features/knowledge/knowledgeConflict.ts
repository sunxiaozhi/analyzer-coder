import type { CardInput } from '@/api/intelligence';

export const editableKnowledgeFields = ['title', 'content', 'tags', 'cardType', 'knowledgeKind', 'severity', 'enforcement', 'ownerAccountId', 'scope', 'obligations', 'branchScope', 'attachmentIds', 'codeReferences'] as const;
export type KnowledgeField = typeof editableKnowledgeFields[number];
function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => JSON.stringify(key) + ':' + canonical(item)).join(',') + '}';
  return JSON.stringify(value) ?? 'undefined';
}
export function compareKnowledgeDraft(base: CardInput, local: CardInput, latest: CardInput) {
  return editableKnowledgeFields.map(key => ({ key,
    changed: canonical(local[key]) !== canonical(latest[key]),
    conflict: canonical(local[key]) !== canonical(base[key]) && canonical(latest[key]) !== canonical(base[key]) && canonical(local[key]) !== canonical(latest[key]),
    preferred: canonical(local[key]) === canonical(base[key]) ? 'latest' as const : 'local' as const,
  }));
}
export function mergeKnowledgeDraft(base: CardInput, local: CardInput, latest: CardInput, choices: Partial<Record<KnowledgeField, 'local' | 'latest'>>): CardInput | null {
  const fields = compareKnowledgeDraft(base, local, latest);
  if (fields.some(field => field.conflict && !choices[field.key])) return null;
  const merged = { ...latest };
  for (const field of fields) Object.assign(merged, { [field.key]: (choices[field.key] ?? field.preferred) === 'local' ? local[field.key] : latest[field.key] });
  return { ...merged, expectedRevision: latest.expectedRevision };
}
