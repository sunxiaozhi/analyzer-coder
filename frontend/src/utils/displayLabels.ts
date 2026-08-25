const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  NEEDS_REVIEW: '需要复核',
  ARCHIVED: '已归档',
  UNREVIEWED: '未评审',
  APPROVED: '评审通过',
  CHANGES_REQUESTED: '要求修改',
  UNVERIFIED: '未确认',
  CURRENT: '当前有效',
  REVIEW_REQUIRED: '待复核',
  STALE: '来源已过期',
  QUEUED: '排队中',
  RUNNING: '运行中',
  CANCEL_REQUESTED: '取消中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELED: '已取消',
  READY: '已就绪',
  BUILDING: '构建中',
  PENDING: '等待中',
  AVAILABLE: '可用',
  UNAVAILABLE: '不可用',
};

const relationshipLabels: Record<string, string> = {
  SUPER_ADMIN: '平台管理员',
  OWNER: '所有者',
  READ: '只读成员',
  MAINTAIN: '维护成员',
  MANAGE: '管理成员',
};

const scanStatusLabels: Record<string, string> = {
  PENDING: '等待扫描',
  SCANNING: '扫描中',
  CLEAN: '扫描通过',
  SAFE: '扫描通过',
  INFECTED: '存在风险',
  REJECTED: '已拒绝',
  FAILED: '扫描失败',
};

export function statusLabel(status: string | null | undefined, fallback = '未知状态') {
  return status ? (statusLabels[status] ?? fallback) : fallback;
}

export function relationshipLabel(relationship: string | null | undefined) {
  return relationship ? (relationshipLabels[relationship] ?? '未知权限') : '未知权限';
}

export function scanStatusLabel(status: string | null | undefined) {
  return status ? (scanStatusLabels[status] ?? '扫描状态未知') : '扫描状态未知';
}
