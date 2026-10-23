import { describe, expect, it } from 'vitest';
import { groupEvidence } from './evidenceGroups';
import { citationFixture, markdownFixture, markdownSource } from './evidenceFixtures';

describe('presentation source grouping', () => {
  it('groups a converted card and its exact Markdown source, retaining both snapshots', () => {
    const knowledge = citationFixture({ sources: [markdownSource] });
    const groups = groupEvidence([knowledge, markdownFixture()], [], 'main');
    expect(groups).toHaveLength(1);
    expect(groups[0].members).toHaveLength(2);
    expect(groups[0].members[0].citation).toBe(knowledge);
    expect(groups[0].differentContent).toBe(true);
    expect(groups[0].missingMarkdown).toBe(false);
  });
  it('keeps other versions, branches and projects separate', () => {
    for (const source of [{ ...markdownSource, contentVersion: 'v2' }, { ...markdownSource, branchId: 'dev' }]) {
      const groups = groupEvidence([citationFixture({ sources: [source] }), markdownFixture()], [], 'main');
      expect(groups).toHaveLength(2);
      expect(groups.every(group => group.otherVersions)).toBe(true);
    }
    expect(groupEvidence([citationFixture({ sources: [markdownSource], repositoryId: 'another' }), markdownFixture()], [], 'main')).toHaveLength(2);
  });
  it('does not infer conversion from a title, ordinary source code, or a code reference', () => {
    expect(groupEvidence([citationFixture({ title: 'docs/refund.md' }), markdownFixture()], [], 'main')).toHaveLength(2);
    expect(groupEvidence([citationFixture({ sources: [markdownSource] }), markdownFixture({ filePath: 'Refund.java' })], [], 'main')).toHaveLength(2);
    expect(groupEvidence([citationFixture({ sources: [markdownSource] }), markdownFixture()])).toHaveLength(2);
  });
  it('shares one member for repeated identical citations and preserves all ranks', () => {
    const groups = groupEvidence([citationFixture(), citationFixture({ id: 'repeat', rank: 8 })]);
    expect(groups).toHaveLength(1);
    expect(groups[0].members).toHaveLength(1);
    expect(groups[0].members[0].ranks).toEqual([3, 8]);
  });
  it('preserves different passages and scope of the same converted knowledge', () => {
    const groups = groupEvidence([citationFixture({ sources: [markdownSource] }), citationFixture({ id: 'other', rank: 8,
      sources: [markdownSource], sourceScope: '分支知识', excerpt: '另一段命中内容' })]);
    expect(groups[0].members).toHaveLength(2);
  });
  it('retains uncited source content within the cited group without claiming it was cited', () => {
    const groups = groupEvidence([citationFixture({ sources: [markdownSource] })], [markdownFixture(), citationFixture({ id: 'extra', knowledgeCardId: 'extra', rank: 9 })], 'main');
    expect(groups).toHaveLength(2);
    expect(groups[0].cited).toBe(true);
    expect(groups[0].members[1].cited).toBe(false);
    expect(groups[1].cited).toBe(false);
  });
  it('labels unavailable historical Markdown instead of fabricating a snapshot', () => {
    expect(groupEvidence([citationFixture({ sources: [markdownSource] })])[0].missingMarkdown).toBe(true);
  });
});
