import { describe, expect, it } from 'vitest';
import { reviewSourceMismatch } from './reviewSourceVersion';
import type { ReviewEvidenceSelection } from './types';

const repository = { id: 'repo', snapshotId: 'snapshot', commit: 'head', worktreeDigest: 'published' };
const selection = { kind: 'CHANGE', repositoryId: 'repo' } as ReviewEvidenceSelection;

describe('review source version', () => {
  it('rejects another commit even when the knowledge baseline matches', () => {
    expect(reviewSourceMismatch({ ...selection, snapshotId: 'snapshot', commitSha: 'base' }, repository))
      .toContain('其他提交');
  });
  it('rejects unpublished worktree source and cross-repository evidence', () => {
    expect(reviewSourceMismatch({ ...selection, worktreeDigest: 'new' }, repository)).toContain('工作区');
    expect(reviewSourceMismatch({ ...selection, repositoryId: 'other' }, repository)).toContain('其他仓库');
  });
  it('allows source verified against the published version', () => {
    expect(reviewSourceMismatch({ ...selection, commitSha: 'head', worktreeDigest: 'published' }, repository)).toBeNull();
  });
  it('rejects unversioned changes and historical snapshots', () => {
    expect(reviewSourceMismatch(selection, repository)).toContain('缺少');
    expect(reviewSourceMismatch({ ...selection, snapshotId: 'old' }, repository)).toContain('历史快照');
  });
});
