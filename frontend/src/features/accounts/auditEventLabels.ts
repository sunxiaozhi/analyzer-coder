export const auditEventLabels: Readonly<Record<string, string>> = {
  INITIAL_ADMIN_CREATED: '创建初始管理员',
  LOGIN_FAILED: '登录失败',
  LOGIN_SUCCEEDED: '登录成功',
  LOGOUT: '退出登录',
  ACCOUNT_LOCKED: '账号锁定',
  ACCOUNT_CREATED: '创建账号',
  ACCOUNT_UPDATED: '更新账号',
  ACCOUNT_DISABLED: '停用账号',
  ACCOUNT_UNLOCKED: '账号解锁',
  PASSWORD_CHANGED: '修改密码',
  PASSWORD_RESET: '重置密码',
  REPOSITORY_PERMISSION_CHANGED: '修改项目权限',
  REPOSITORY_PERMISSION_REVOKED: '撤销项目权限',
  ACCOUNT_ENABLED: '启用账号',
  ACCOUNT_ROLE_CHANGED: '修改账号角色',
  ACCESS_TOKEN_CREATED: '创建访问令牌',
  ACCESS_TOKEN_REVOKED: '撤销访问令牌',
  REPOSITORY_UPDATED: '更新项目',
  REPOSITORY_CREDENTIAL_CREATED: '创建仓库凭据',
  REPOSITORY_CREDENTIAL_UPDATED: '更新仓库凭据',
  REPOSITORY_CREDENTIAL_VALIDATED: '验证仓库凭据',
  REPOSITORY_CREDENTIAL_ENABLED: '启用仓库凭据',
  REPOSITORY_CREDENTIAL_DISABLED: '停用仓库凭据',
  REPOSITORY_CREDENTIAL_DELETED: '删除仓库凭据',
  REPOSITORY_CREDENTIAL_BOUND: '绑定仓库凭据',
  REPOSITORY_CREDENTIAL_UNBOUND: '解绑仓库凭据',
  REPOSITORY_OWNERSHIP_TRANSFERRED: '转移项目所有权',
  REPOSITORY_DELETION_REQUESTED: '申请删除项目',
};

export function auditEventLabel(eventType: string): string {
  return auditEventLabels[eventType] ?? eventType;
}
