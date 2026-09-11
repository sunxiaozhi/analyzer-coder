import { describe, expect, it } from 'vitest';
import { workspaceNavigation } from './workspaceNavigation';

describe('workspaceNavigation', () => {
  it('shows ordinary developers the focused retrieval workflow', () => {
    const groups = workspaceNavigation({
      isAdmin: false,
      canReadSelectedRepository: false,
      canManageProjects: false,
    });

    expect(groups).toHaveLength(1);
    expect(groups[0].items.map(item => item.label)).toEqual([
      '项目总览',
      '代码与知识',
      '问项目',
    ]);
  });

  it('keeps the knowledge library and project management in the maintenance group', () => {
    const groups = workspaceNavigation({
      isAdmin: false,
      canReadSelectedRepository: true,
      canManageProjects: true,
    });

    expect(groups.map(group => group.label)).toEqual(['研发工作', '项目维护']);
    expect(groups[1].items.map(item => item.label)).toEqual(['知识库', '项目管理']);
  });

  it('nests all four administrator operations in one system group', () => {
    const groups = workspaceNavigation({
      isAdmin: true,
      canReadSelectedRepository: true,
      canManageProjects: true,
    });
    const system = groups.find(group => group.key === 'system');

    expect(system?.collapsible).toBe(true);
    expect(system?.items.map(item => item.label)).toEqual([
      '索引任务',
      '模型配置',
      '账号权限',
      '审计日志',
    ]);
  });
});
