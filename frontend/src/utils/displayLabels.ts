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
  SUSPECT: '疑似失效',
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

const knowledgeKindLabels: Record<string, string> = {
  REFERENCE: '参考',
  BUSINESS_RULE: '业务规则',
  ARCH_DECISION: '架构决策',
  API_CONTRACT: '接口契约',
  DATA_CONSTRAINT: '数据约束',
  TEST_OBLIGATION: '测试义务',
  SECURITY_POLICY: '安全策略',
  RUNBOOK: '运行手册',
  INCIDENT_LESSON: '事故经验',
  OWNERSHIP: '责任归属',
  TECH_DEBT: '技术债',
};

const enforcementLabels: Record<string, string> = {
  REFERENCE: '仅参考',
  ADVISORY: '建议执行',
  REQUIRED: '必须执行',
};

const retrievalSourceLabels: Record<string, string> = {
  DETERMINISTIC: '规则匹配',
  KEYWORD: '关键词检索',
  SEMANTIC: '语义检索',
  HYBRID: '混合检索',
  VECTOR: '向量检索',
};

const changeSourceLabels: Record<string, string> = {
  WORKTREE: '工作区',
  COMMIT: '提交版本',
  COMMIT_RANGE: '版本范围',
  PULL_REQUEST: '拉取请求',
  MERGE_REQUEST: '合并请求',
  PATCH: '补丁',
};

const symbolKindLabels: Record<string, string> = {
  FILE: '文件',
  CLASS: '类',
  INTERFACE: '接口',
  ENUM: '枚举',
  METHOD: '方法',
  FUNCTION: '函数',
  CONSTRUCTOR: '构造函数',
  FIELD: '字段',
  PROPERTY: '属性',
  ROUTE: '路由',
  MODULE: '模块',
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

export function knowledgeKindLabel(kind: string | null | undefined) {
  return kind ? (knowledgeKindLabels[kind] ?? '工程知识') : '工程知识';
}

export function enforcementLabel(enforcement: string | null | undefined) {
  return enforcement ? (enforcementLabels[enforcement] ?? '执行级别未标注') : '执行级别未标注';
}

export function retrievalSourceLabel(source: string | null | undefined) {
  return source ? (retrievalSourceLabels[source] ?? '检索候选') : '检索候选';
}

export function changeSourceLabel(source: string | null | undefined) {
  return source ? (changeSourceLabels[source] ?? '变更审查') : '变更审查';
}

export function symbolKindLabel(kind: string | null | undefined) {
  return kind ? (symbolKindLabels[kind] ?? '代码对象') : '代码对象';
}
