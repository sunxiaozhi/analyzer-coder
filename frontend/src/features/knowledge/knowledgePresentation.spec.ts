import { describe, expect, it } from 'vitest';
import type { KnowledgeCard } from '@/api/intelligence';
import type { BranchContext } from '@/api/branches';
import { branchScopeLabel, knowledgeExcerpt, knowledgeStatus } from './knowledgePresentation';

const card = { publicationStatus: 'PUBLISHED', enforcement: 'ADVISORY', codeReferences: [] } as unknown as KnowledgeCard;
const context = { branchId: 'main', branchName: 'main' } as BranchContext;
describe('knowledge presentation facts', () => {
  it('removes attachment syntax and Markdown markers without rendering HTML', () => {
    expect(knowledgeExcerpt('# Title\n**Rule** [source](https://example.com)\n![image](knowledge-attachment://id)\n```ts\nconst amount = 5;\n```'))
      .toBe('Title Rule source const amount = 5;');
  });
  it('keeps the complete selected branch list and handles missing names honestly', () => {
    expect(branchScopeLabel({ mode: 'SELECTED_BRANCHES', branchIds: ['a', 'missing'] }, [{ id: 'a', name: 'main' }]))
      .toBe('main、missing 共 2 个分支共享');
    expect(branchScopeLabel({ mode: 'ALL_BRANCHES', branchIds: [] }, [])).toBe('项目全部分支共享');
  });
  it('does not imply that publication validates every branch', () => {
    expect(knowledgeStatus(card, context, 'CURRENT').label).toContain('当前分支已验证');
    expect(knowledgeStatus(card, context, 'INVALID').label).toContain('当前分支不适用');
    expect(knowledgeStatus(card, context, 'UNVERIFIED').label).toContain('当前分支待验证');
    expect(knowledgeStatus(card, null, 'CURRENT').label).toBe('已发布');
  });
  it('does not label generic reference knowledge as unusable without manual validation', () => {
    expect(knowledgeStatus({ ...card, enforcement: 'REFERENCE' }, context, 'UNVERIFIED').label)
      .toBe('已发布 · 尚无当前分支验证');
    expect(knowledgeStatus({ ...card, publicationStatus: 'DRAFT' }, context, 'CURRENT').label).toBe('草稿');
  });
});
