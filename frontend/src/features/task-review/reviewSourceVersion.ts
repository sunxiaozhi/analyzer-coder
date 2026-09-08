import type { Repository } from '@/types/api';
import type { ReviewEvidenceSelection } from './types';

// The review's knowledge baseline does not identify the version of changed source.
export function reviewSourceMismatch(
  source: ReviewEvidenceSelection,
  repository: Pick<Repository, 'id' | 'snapshotId' | 'commit' | 'worktreeDigest'> | null,
): string | null {
  if (!repository) return '请先选择证据所属仓库。';
  if (source.repositoryId && source.repositoryId !== repository.id) {
    return '该证据属于其他仓库，请从来源知识卡查看对应仓库的代码。';
  }
  if (source.snapshotId && source.snapshotId !== repository.snapshotId) {
    return '该证据属于历史快照，当前源码不能代表当时的内容。';
  }
  if (source.worktreeDigest && source.worktreeDigest !== repository.worktreeDigest) {
    return '工作区证据与当前已发布快照不同，请核对工作区或更新快照后重新审查。';
  }
  if (source.commitSha && source.commitSha !== repository.commit) {
    return '该变更证据属于其他提交，当前源码不能代表该提交的内容。';
  }
  if (source.kind === 'CHANGE' && !source.snapshotId && !source.commitSha && !source.worktreeDigest) {
    return '该变更缺少可核验的源码版本，请先核对 Git 变更范围。';
  }
  return null;
}
