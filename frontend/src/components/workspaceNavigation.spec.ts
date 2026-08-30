import { describe, expect, it } from 'vitest';
import { workspaceNavigation } from './workspaceNavigation';

describe('workspaceNavigation', () => {
  it('shows ordinary developers only the four daily development tasks', () => {
    const groups = workspaceNavigation({
      isAdmin: false,
      canMaintainSelectedRepository: false,
      canManageProjects: false,
    });

    expect(groups).toHaveLength(1);
    expect(groups[0].items.map(item => item.label)).toEqual([
      '项目总览',
      '代码与证据',
      '问项目',
      '变更审查',
    ]);
  });

  it('adds governance without exposing system operations to maintainers', () => {
    const groups = workspaceNavigation({
      isAdmin: false,
      canMaintainSelectedRepository: true,
      canManageProjects: true,
    });

    expect(groups.map(group => group.label)).toEqual(['研发工作', '项目维护']);
    expect(groups[1].items.map(item => item.label)).toEqual(['知识治理', '项目管理']);
  });

  it('nests all four administrator operations in one system group', () => {
    const groups = workspaceNavigation({
      isAdmin: true,
      canMaintainSelectedRepository: true,
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
