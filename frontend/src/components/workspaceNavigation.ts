export type WorkspaceNavIcon =
  | 'overview'
  | 'code'
  | 'ask'
  | 'review'
  | 'knowledge'
  | 'projects'
  | 'tasks'
  | 'models'
  | 'accounts'
  | 'audit';

export interface WorkspaceNavItem {
  to: string;
  label: string;
  icon: WorkspaceNavIcon;
}

export interface WorkspaceNavGroup {
  key: 'developer' | 'maintenance' | 'system';
  label: string;
  collapsible: boolean;
  items: WorkspaceNavItem[];
}

export interface WorkspaceNavigationContext {
  isAdmin: boolean;
  canMaintainSelectedRepository: boolean;
  canManageProjects: boolean;
}

const developerItems: WorkspaceNavItem[] = [
  { to: '/overview', label: '项目总览', icon: 'overview' },
  { to: '/search', label: '代码与证据', icon: 'code' },
  { to: '/ask', label: '问项目', icon: 'ask' },
  { to: '/change-impact', label: '变更审查', icon: 'review' },
];

export function workspaceNavigation(
  context: WorkspaceNavigationContext,
): WorkspaceNavGroup[] {
  const maintenanceItems: WorkspaceNavItem[] = [];
  if (context.canMaintainSelectedRepository) {
    maintenanceItems.push({ to: '/knowledge', label: '知识治理', icon: 'knowledge' });
  }
  if (context.canManageProjects) {
    maintenanceItems.push({ to: '/repositories', label: '项目管理', icon: 'projects' });
  }

  const groups: WorkspaceNavGroup[] = [
    { key: 'developer', label: '研发工作', collapsible: false, items: developerItems },
  ];
  if (maintenanceItems.length) {
    groups.push({
      key: 'maintenance',
      label: '项目维护',
      collapsible: false,
      items: maintenanceItems,
    });
  }
  if (context.isAdmin) {
    groups.push({
      key: 'system',
      label: '系统管理',
      collapsible: true,
      items: [
        { to: '/indexing', label: '索引任务', icon: 'tasks' },
        { to: '/settings', label: '模型配置', icon: 'models' },
        { to: '/accounts', label: '账号权限', icon: 'accounts' },
        { to: '/audit', label: '审计日志', icon: 'audit' },
      ],
    });
  }
  return groups;
}
