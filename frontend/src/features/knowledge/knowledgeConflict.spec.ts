import { expect, it } from 'vitest';
import type { CardInput } from '@/api/intelligence';
import { compareKnowledgeDraft, mergeKnowledgeDraft } from './knowledgeConflict';

const base: CardInput = { expectedRevision: 1, title: '原题', content: '原文', cardType: '知识卡片', tags: [], knowledgeKind: 'REFERENCE', severity: 'INFO', enforcement: 'REFERENCE', ownerAccountId: null, scope: { pathPatterns: [], symbols: [], modules: [] }, obligations: { requiredTests: [], requiredApproverAccountIds: [], instructions: [], prohibitedPathPatterns: [], knowledgeUpdateRequired: false }, attachmentIds: [], codeReferences: [] };
it('merges independent changes and uses the newest expected revision', () => {
  const local = { ...base, content: '我的正文', attachmentIds: ['my-file'] };
  const latest = { ...base, expectedRevision: 2, title: '他人标题', tags: ['最新标签'] };
  expect(mergeKnowledgeDraft(base, local, latest, {})).toMatchObject({ expectedRevision: 2, title: '他人标题', content: '我的正文', tags: ['最新标签'], attachmentIds: ['my-file'] });
});
it('requires an explicit decision for every overlapping field, including attachments', () => {
  const local = { ...base, content: '我的正文', attachmentIds: ['mine'] };
  const latest = { ...base, expectedRevision: 3, content: '他人正文', attachmentIds: ['theirs'] };
  expect(mergeKnowledgeDraft(base, local, latest, {})).toBeNull();
  expect(mergeKnowledgeDraft(base, local, latest, { content: 'local' })).toBeNull();
  expect(mergeKnowledgeDraft(base, local, latest, { content: 'local', attachmentIds: 'latest' })).toMatchObject({ content: '我的正文', attachmentIds: ['theirs'], expectedRevision: 3 });
});
it('does not flag object property order or identical concurrent edits as a conflict', () => {
  const local = { ...base, content: '相同修改', scope: { modules: [], symbols: [], pathPatterns: [] } };
  const latest = { ...base, content: '相同修改', expectedRevision: 2 };
  expect(compareKnowledgeDraft(base, local, latest).some(field => field.conflict)).toBe(false);
  expect(mergeKnowledgeDraft(base, local, latest, {})?.expectedRevision).toBe(2);
});
