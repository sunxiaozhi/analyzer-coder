-- 代码知识平台数据库单一初始化基线。
-- 本文件由原 V1 至 V14 按版本顺序合并，仅支持空库或明确重建后的数据库。
-- 仓库只保留一个已发布代码版本；snapshot_id 是派生数据一致性令牌，
-- 不指向可长期保留的历史源码副本。

CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================================
-- Identity and repositories
-- ============================================================================

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    username TEXT NOT NULL,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    account_role TEXT NOT NULL,
    role TEXT GENERATED ALWAYS AS (account_role) STORED,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    temporary_password_expires_at TIMESTAMP,
    last_login_at TIMESTAMP,
    last_login_ip TEXT,
    account_version BIGINT NOT NULL DEFAULT 1,
    last_repository_id UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_accounts_version_positive CHECK (account_version > 0)
);

COMMENT ON TABLE accounts IS '平台账号';
COMMENT ON COLUMN accounts.id IS '账号唯一标识';
COMMENT ON COLUMN accounts.username IS '登录用户名';
COMMENT ON COLUMN accounts.display_name IS '账号显示名称';
COMMENT ON COLUMN accounts.password_hash IS '不可逆密码摘要';
COMMENT ON COLUMN accounts.account_role IS '账号角色，例如 SUPER_ADMIN 或 USER';
COMMENT ON COLUMN accounts.role IS '由 account_role 自动生成的兼容角色字段';
COMMENT ON COLUMN accounts.enabled IS '账号是否启用';
COMMENT ON COLUMN accounts.must_change_password IS '下次登录是否必须修改密码';
COMMENT ON COLUMN accounts.failed_attempts IS '兼容字段：账号累计失败登录次数';
COMMENT ON COLUMN accounts.locked_until IS '账号锁定截止时间';
COMMENT ON COLUMN accounts.temporary_password_expires_at IS '临时密码失效时间';
COMMENT ON COLUMN accounts.last_login_at IS '最后成功登录时间';
COMMENT ON COLUMN accounts.last_login_ip IS '最后成功登录来源地址';
COMMENT ON COLUMN accounts.account_version IS '账号资料乐观锁版本';
COMMENT ON COLUMN accounts.last_repository_id IS '用户最后选择的仓库';
COMMENT ON COLUMN accounts.created_at IS '创建时间';
COMMENT ON COLUMN accounts.updated_at IS '最后更新时间';

CREATE UNIQUE INDEX uq_accounts_username_normalized ON accounts (LOWER(BTRIM(username)));
CREATE INDEX idx_accounts_last_repository ON accounts(last_repository_id);

CREATE TABLE repositories (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    path TEXT NOT NULL,
    source_type TEXT NOT NULL DEFAULT 'LOCAL_GIT',
    default_branch TEXT,
    remote_url TEXT,
    current_commit TEXT,
    worktree_digest TEXT,
    worktree_dirty BOOLEAN NOT NULL DEFAULT FALSE,
    current_snapshot_id UUID,
    current_snapshot_path TEXT,
    snapshot_created_at TIMESTAMP,
    codegraph_path TEXT,
    last_scanned_at TIMESTAMP,
    owner_account_id UUID NOT NULL REFERENCES accounts(id),
    ownership_version BIGINT NOT NULL DEFAULT 0,
    repository_status TEXT NOT NULL DEFAULT 'READY',
    repository_version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_repositories_version_positive CHECK (repository_version > 0)
);

COMMENT ON TABLE repositories IS '代码仓库；一条记录固定一个代码来源和分支，只维护当前代码版本';
COMMENT ON COLUMN repositories.id IS '仓库唯一标识';
COMMENT ON COLUMN repositories.name IS '仓库显示名称';
COMMENT ON COLUMN repositories.normalized_name IS '同一所有者下用于唯一性判断的规范化名称';
COMMENT ON COLUMN repositories.description IS '仓库说明';
COMMENT ON COLUMN repositories.path IS '源代码目录或平台受管 Git 工作目录';
COMMENT ON COLUMN repositories.source_type IS '来源类型：LOCAL_GIT、REMOTE_GIT、GITLAB 或 ZIP';
COMMENT ON COLUMN repositories.default_branch IS '该仓库固定跟踪的分支';
COMMENT ON COLUMN repositories.remote_url IS '远程 Git/GitLab HTTPS 克隆地址';
COMMENT ON COLUMN repositories.current_commit IS '当前已发布代码版本的 Git 提交号';
COMMENT ON COLUMN repositories.worktree_digest IS '当前代码文件清单及内容摘要';
COMMENT ON COLUMN repositories.worktree_dirty IS '最近同步时源工作区是否包含未提交变化';
COMMENT ON COLUMN repositories.current_snapshot_id IS '当前内容版本令牌，用于保证代码及派生数据版本一致';
COMMENT ON COLUMN repositories.current_snapshot_path IS '当前已发布代码的只读受管目录';
COMMENT ON COLUMN repositories.snapshot_created_at IS '当前代码版本发布时间';
COMMENT ON COLUMN repositories.codegraph_path IS '当前 CodeGraph 产物路径';
COMMENT ON COLUMN repositories.last_scanned_at IS '最近一次检查源代码变化的时间';
COMMENT ON COLUMN repositories.owner_account_id IS '仓库所有者账号';
COMMENT ON COLUMN repositories.ownership_version IS '所有权并发控制版本';
COMMENT ON COLUMN repositories.repository_status IS '仓库生命周期状态';
COMMENT ON COLUMN repositories.repository_version IS '仓库资料乐观锁版本';
COMMENT ON COLUMN repositories.deleted_at IS '逻辑删除时间';
COMMENT ON COLUMN repositories.created_at IS '创建时间';
COMMENT ON COLUMN repositories.updated_at IS '最后更新时间';

CREATE UNIQUE INDEX uq_repositories_normalized_path ON repositories(path);
CREATE UNIQUE INDEX uq_repositories_owner_normalized_name
    ON repositories(owner_account_id, normalized_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_repositories_owner_status
    ON repositories(owner_account_id, repository_status, created_at DESC);

ALTER TABLE accounts
    ADD CONSTRAINT fk_accounts_last_repository
    FOREIGN KEY (last_repository_id) REFERENCES repositories(id) ON DELETE SET NULL;

CREATE TABLE login_sessions (
    token_hash TEXT PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    csrf_token TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE login_sessions IS '登录会话';
COMMENT ON COLUMN login_sessions.token_hash IS '会话令牌摘要';
COMMENT ON COLUMN login_sessions.account_id IS '所属账号';
COMMENT ON COLUMN login_sessions.csrf_token IS '修改请求使用的 CSRF 令牌';
COMMENT ON COLUMN login_sessions.created_at IS '会话创建时间';
COMMENT ON COLUMN login_sessions.last_seen_at IS '最近活动时间';
COMMENT ON COLUMN login_sessions.expires_at IS '绝对失效时间';

CREATE INDEX idx_login_sessions_account ON login_sessions(account_id);
CREATE INDEX idx_login_sessions_expiry ON login_sessions(expires_at);

CREATE TABLE login_captcha_challenges (
    id UUID PRIMARY KEY,
    username_normalized TEXT NOT NULL,
    answer_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE login_captcha_challenges IS '登录验证码挑战';
COMMENT ON COLUMN login_captcha_challenges.id IS '验证码唯一标识';
COMMENT ON COLUMN login_captcha_challenges.username_normalized IS '规范化登录用户名';
COMMENT ON COLUMN login_captcha_challenges.answer_hash IS '验证码答案摘要';
COMMENT ON COLUMN login_captcha_challenges.expires_at IS '验证码失效时间';
COMMENT ON COLUMN login_captcha_challenges.used_at IS '验证码消费时间';
COMMENT ON COLUMN login_captcha_challenges.created_at IS '创建时间';

CREATE INDEX idx_login_captcha_user
    ON login_captcha_challenges(username_normalized, created_at DESC);

CREATE TABLE login_failure_counters (
    username_normalized TEXT PRIMARY KEY,
    failure_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE login_failure_counters IS '持久化登录失败计数';
COMMENT ON COLUMN login_failure_counters.username_normalized IS '规范化登录用户名';
COMMENT ON COLUMN login_failure_counters.failure_count IS '连续失败次数';
COMMENT ON COLUMN login_failure_counters.updated_at IS '最后失败或重置时间';

CREATE TABLE repository_permissions (
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    permission_level TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (account_id, repo_id),
    CONSTRAINT repository_permissions_permission_level_check
        CHECK (permission_level IN ('READ', 'MAINTAIN', 'MANAGE'))
);

COMMENT ON TABLE repository_permissions IS '仓库成员权限，不包含由 owner_account_id 表达的 OWNER';
COMMENT ON COLUMN repository_permissions.account_id IS '成员账号';
COMMENT ON COLUMN repository_permissions.repo_id IS '目标仓库';
COMMENT ON COLUMN repository_permissions.permission_level IS '权限等级：READ、MAINTAIN 或 MANAGE';
COMMENT ON COLUMN repository_permissions.created_at IS '授权时间';
COMMENT ON COLUMN repository_permissions.updated_at IS '最后调整时间';

CREATE INDEX idx_repository_permissions_repo ON repository_permissions(repo_id);

CREATE TABLE repository_governance_locks (
    repo_id UUID PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
    lock_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE repository_governance_locks IS '仓库治理操作的乐观并发锁';
COMMENT ON COLUMN repository_governance_locks.repo_id IS '目标仓库';
COMMENT ON COLUMN repository_governance_locks.lock_version IS '并发控制版本';
COMMENT ON COLUMN repository_governance_locks.updated_at IS '最后更新时间';

CREATE TABLE git_credentials (
    id UUID PRIMARY KEY,
    legacy_repo_id UUID REFERENCES repositories(id) ON DELETE CASCADE,
    credential_type TEXT NOT NULL,
    display_name TEXT NOT NULL,
    encrypted_secret TEXT NOT NULL,
    masked_value TEXT NOT NULL,
    credential_version BIGINT NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    last_validated_at TIMESTAMP,
    server_url TEXT NOT NULL,
    username TEXT,
    secret_iv TEXT NOT NULL,
    secret_digest TEXT NOT NULL,
    encryption_algorithm TEXT NOT NULL DEFAULT 'AES-256-GCM',
    last_validation_error TEXT,
    expires_at TIMESTAMPTZ,
    disabled_at TIMESTAMPTZ,
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_git_credentials_type
        CHECK (credential_type IN ('GIT_HTTP_TOKEN', 'GITLAB_PAT')),
    CONSTRAINT chk_git_credentials_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'INVALID'))
);

COMMENT ON TABLE git_credentials IS '可复用的加密 Git/GitLab HTTPS 凭据';
COMMENT ON COLUMN git_credentials.id IS '凭据唯一标识';
COMMENT ON COLUMN git_credentials.legacy_repo_id IS '迁移前的仓库绑定，仅用于兼容旧数据';
COMMENT ON COLUMN git_credentials.credential_type IS '凭据类型';
COMMENT ON COLUMN git_credentials.display_name IS '凭据显示名称';
COMMENT ON COLUMN git_credentials.encrypted_secret IS '加密后的敏感内容';
COMMENT ON COLUMN git_credentials.masked_value IS '用于界面显示的掩码';
COMMENT ON COLUMN git_credentials.credential_version IS '凭据版本';
COMMENT ON COLUMN git_credentials.status IS '凭据状态';
COMMENT ON COLUMN git_credentials.last_validated_at IS '最近验证时间';
COMMENT ON COLUMN git_credentials.server_url IS '凭据适用的 Git 服务地址';
COMMENT ON COLUMN git_credentials.expires_at IS '可选的凭据过期时间';
COMMENT ON COLUMN git_credentials.last_validation_error IS '脱敏后的最近检测失败原因';
COMMENT ON COLUMN git_credentials.created_by IS '创建账号';
COMMENT ON COLUMN git_credentials.created_at IS '创建时间';
COMMENT ON COLUMN git_credentials.updated_at IS '最后更新时间';

CREATE INDEX idx_repository_credentials_repo
    ON git_credentials(legacy_repo_id, created_at DESC);
CREATE INDEX idx_git_credentials_owner
    ON git_credentials(created_by, status, created_at DESC);
CREATE INDEX idx_git_credentials_expiry
    ON git_credentials(status, expires_at);

CREATE TABLE repository_credential_bindings (
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    credential_id UUID NOT NULL REFERENCES git_credentials(id) ON DELETE RESTRICT,
    usage_type TEXT NOT NULL DEFAULT 'CLONE',
    bound_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (repository_id, usage_type),
    CONSTRAINT chk_repository_credential_usage CHECK (usage_type IN ('CLONE'))
);

COMMENT ON TABLE repository_credential_bindings IS '仓库与可复用凭据的用途绑定';

CREATE INDEX idx_repository_credential_bindings_credential
    ON repository_credential_bindings(credential_id, created_at DESC);

CREATE TABLE repository_import_jobs (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    credential_id UUID REFERENCES git_credentials(id) ON DELETE RESTRICT,
    source_type TEXT NOT NULL,
    repository_name TEXT NOT NULL,
    remote_url TEXT NOT NULL,
    branch TEXT,
    status TEXT NOT NULL DEFAULT 'QUEUED',
    current_step TEXT NOT NULL DEFAULT 'queued',
    error_message TEXT,
    result_repository_id UUID REFERENCES repositories(id) ON DELETE SET NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_repository_import_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'))
);

COMMENT ON TABLE repository_import_jobs IS '远程仓库异步导入任务';

CREATE INDEX idx_repository_import_jobs_queue
    ON repository_import_jobs(status, created_at);
CREATE INDEX idx_repository_import_jobs_actor
    ON repository_import_jobs(account_id, created_at DESC);

CREATE TABLE repository_deletion_tombstones (
    repository_id UUID PRIMARY KEY,
    deleted_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    deleted_at TIMESTAMP NOT NULL,
    cleanup_status TEXT NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error_code TEXT,
    cleanup_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE repository_deletion_tombstones IS '仓库逻辑删除后的物理清理任务';
COMMENT ON COLUMN repository_deletion_tombstones.repository_id IS '已删除仓库标识';
COMMENT ON COLUMN repository_deletion_tombstones.deleted_by IS '执行删除的账号';
COMMENT ON COLUMN repository_deletion_tombstones.deleted_at IS '删除请求时间';
COMMENT ON COLUMN repository_deletion_tombstones.cleanup_status IS '派生数据清理状态';
COMMENT ON COLUMN repository_deletion_tombstones.retry_count IS '清理重试次数';
COMMENT ON COLUMN repository_deletion_tombstones.last_error_code IS '最近清理错误编码';
COMMENT ON COLUMN repository_deletion_tombstones.cleanup_updated_at IS '清理状态更新时间';

CREATE INDEX idx_repository_deletion_cleanup_queue
    ON repository_deletion_tombstones(cleanup_status, cleanup_updated_at, deleted_at);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    target_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    target_repo_id UUID REFERENCES repositories(id) ON DELETE SET NULL,
    event_type TEXT NOT NULL,
    result TEXT NOT NULL,
    request_id UUID NOT NULL,
    source_ip TEXT,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE audit_events IS '安全、账号与仓库治理审计事件';
COMMENT ON COLUMN audit_events.id IS '审计事件唯一标识';
COMMENT ON COLUMN audit_events.actor_account_id IS '操作账号';
COMMENT ON COLUMN audit_events.target_account_id IS '目标账号';
COMMENT ON COLUMN audit_events.target_repo_id IS '目标仓库';
COMMENT ON COLUMN audit_events.event_type IS '事件类型';
COMMENT ON COLUMN audit_events.result IS '执行结果';
COMMENT ON COLUMN audit_events.request_id IS '请求追踪标识';
COMMENT ON COLUMN audit_events.source_ip IS '请求来源地址';
COMMENT ON COLUMN audit_events.details IS '脱敏后的扩展信息';
COMMENT ON COLUMN audit_events.created_at IS '发生时间';

CREATE INDEX idx_audit_events_created ON audit_events(created_at DESC, id);
CREATE INDEX idx_audit_events_actor ON audit_events(actor_account_id, created_at DESC);

-- ============================================================================
-- Tasks, code indexing and graph artifacts
-- ============================================================================

CREATE TABLE index_jobs (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id),
    job_type TEXT NOT NULL,
    status TEXT NOT NULL,
    current_step TEXT,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE index_jobs IS '仓库同步、内容索引和 CodeGraph 构建等后台任务';
COMMENT ON COLUMN index_jobs.id IS '任务唯一标识';
COMMENT ON COLUMN index_jobs.repo_id IS '所属仓库';
COMMENT ON COLUMN index_jobs.job_type IS '任务类型';
COMMENT ON COLUMN index_jobs.status IS '任务状态';
COMMENT ON COLUMN index_jobs.current_step IS '当前执行阶段';
COMMENT ON COLUMN index_jobs.error_message IS '失败原因';
COMMENT ON COLUMN index_jobs.started_at IS '开始时间';
COMMENT ON COLUMN index_jobs.finished_at IS '结束时间';
COMMENT ON COLUMN index_jobs.created_at IS '创建时间';

CREATE INDEX idx_index_jobs_repo_created_at ON index_jobs(repo_id, created_at DESC);
CREATE UNIQUE INDEX uq_index_jobs_one_active_per_repository
    ON index_jobs(repo_id) WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE TABLE code_chunks (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id),
    snapshot_id UUID NOT NULL,
    commit_sha TEXT NOT NULL,
    file_path TEXT NOT NULL,
    symbol_id TEXT,
    symbol_name TEXT,
    symbol_kind TEXT,
    language TEXT,
    chunk_type TEXT NOT NULL,
    asset_type VARCHAR(24) NOT NULL DEFAULT 'CODE',
    start_line INTEGER,
    end_line INTEGER,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_code_chunks_asset_type
        CHECK (asset_type IN ('CODE','DOCUMENT','RULE','TASK','CONFIG'))
);

COMMENT ON TABLE code_chunks IS '当前仓库版本切分得到的可检索项目资产片段';
COMMENT ON COLUMN code_chunks.id IS '代码片段唯一标识';
COMMENT ON COLUMN code_chunks.repo_id IS '所属仓库';
COMMENT ON COLUMN code_chunks.snapshot_id IS '生成该片段的内容版本令牌';
COMMENT ON COLUMN code_chunks.commit_sha IS '生成该片段的 Git 提交号';
COMMENT ON COLUMN code_chunks.file_path IS '仓库内相对文件路径';
COMMENT ON COLUMN code_chunks.symbol_id IS '解析器生成的符号标识';
COMMENT ON COLUMN code_chunks.symbol_name IS '类、方法、函数等符号名称';
COMMENT ON COLUMN code_chunks.symbol_kind IS '符号类型';
COMMENT ON COLUMN code_chunks.language IS '编程语言';
COMMENT ON COLUMN code_chunks.chunk_type IS '片段类型';
COMMENT ON COLUMN code_chunks.asset_type IS '仓库资产类型：代码、文档、规则、任务或配置';
COMMENT ON COLUMN code_chunks.start_line IS '起始行号';
COMMENT ON COLUMN code_chunks.end_line IS '结束行号';
COMMENT ON COLUMN code_chunks.content IS '用于检索、引用和 Agent 上下文的资产正文';
COMMENT ON COLUMN code_chunks.content_hash IS '资产正文摘要';
COMMENT ON COLUMN code_chunks.created_at IS '生成时间';

CREATE INDEX idx_code_chunks_repo_file ON code_chunks(repo_id, file_path);
CREATE INDEX idx_code_chunks_repo_symbol ON code_chunks(repo_id, symbol_id);
CREATE INDEX idx_code_chunks_repo_created_at ON code_chunks(repo_id, created_at DESC);
CREATE INDEX idx_code_chunks_repo_language ON code_chunks(repo_id, language);
CREATE INDEX idx_code_chunks_repo_snapshot ON code_chunks(repo_id, snapshot_id);
CREATE INDEX idx_code_chunks_repo_asset_type ON code_chunks(repo_id, asset_type, file_path);

CREATE TABLE chunk_embeddings (
    chunk_id UUID PRIMARY KEY REFERENCES code_chunks(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    model VARCHAR(200) NOT NULL,
    dimension INTEGER NOT NULL,
    embedding vector NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chunk_embeddings_dimension_check CHECK (dimension BETWEEN 1 AND 4096),
    CONSTRAINT chunk_embeddings_vector_dimension_check CHECK (vector_dims(embedding) = dimension)
);

COMMENT ON TABLE chunk_embeddings IS '当前项目资产片段的向量表示';
COMMENT ON COLUMN chunk_embeddings.chunk_id IS '对应项目资产片段';
COMMENT ON COLUMN chunk_embeddings.repo_id IS '所属仓库';
COMMENT ON COLUMN chunk_embeddings.model IS '向量模型标识';
COMMENT ON COLUMN chunk_embeddings.dimension IS '向量维度';
COMMENT ON COLUMN chunk_embeddings.embedding IS '检索向量';
COMMENT ON COLUMN chunk_embeddings.content_hash IS '生成向量时的代码正文摘要';
COMMENT ON COLUMN chunk_embeddings.created_at IS '生成或更新时间';

CREATE INDEX idx_chunk_embeddings_repo ON chunk_embeddings(repo_id);

CREATE TABLE code_graph_edges (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL,
    source_chunk_id UUID REFERENCES code_chunks(id) ON DELETE CASCADE,
    target_chunk_id UUID REFERENCES code_chunks(id) ON DELETE CASCADE,
    source_symbol TEXT NOT NULL,
    target_symbol TEXT NOT NULL,
    relation VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(repo_id, snapshot_id, source_chunk_id, target_chunk_id, relation)
);

COMMENT ON TABLE code_graph_edges IS '从当前代码版本提取的符号关系边';
COMMENT ON COLUMN code_graph_edges.id IS '关系边唯一标识';
COMMENT ON COLUMN code_graph_edges.repo_id IS '所属仓库';
COMMENT ON COLUMN code_graph_edges.snapshot_id IS '生成该关系的内容版本令牌';
COMMENT ON COLUMN code_graph_edges.source_chunk_id IS '起点代码片段';
COMMENT ON COLUMN code_graph_edges.target_chunk_id IS '终点代码片段';
COMMENT ON COLUMN code_graph_edges.source_symbol IS '起点符号';
COMMENT ON COLUMN code_graph_edges.target_symbol IS '终点符号';
COMMENT ON COLUMN code_graph_edges.relation IS '关系类型';
COMMENT ON COLUMN code_graph_edges.created_at IS '生成时间';

CREATE INDEX idx_code_graph_edges_source ON code_graph_edges(repo_id, source_symbol);
CREATE INDEX idx_code_graph_edges_target ON code_graph_edges(repo_id, target_symbol);

CREATE TABLE codegraph_artifacts (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL,
    cli_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    artifact_path TEXT NOT NULL,
    node_count INTEGER,
    edge_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

COMMENT ON TABLE codegraph_artifacts IS '当前代码版本对应的 CodeGraph 分析产物';
COMMENT ON COLUMN codegraph_artifacts.id IS '产物唯一标识';
COMMENT ON COLUMN codegraph_artifacts.repo_id IS '所属仓库';
COMMENT ON COLUMN codegraph_artifacts.snapshot_id IS '生成该产物的内容版本令牌';
COMMENT ON COLUMN codegraph_artifacts.cli_version IS 'CodeGraph CLI 版本';
COMMENT ON COLUMN codegraph_artifacts.status IS '产物状态';
COMMENT ON COLUMN codegraph_artifacts.artifact_path IS '产物存储路径';
COMMENT ON COLUMN codegraph_artifacts.node_count IS '图节点数量';
COMMENT ON COLUMN codegraph_artifacts.edge_count IS '图边数量';
COMMENT ON COLUMN codegraph_artifacts.created_at IS '创建时间';
COMMENT ON COLUMN codegraph_artifacts.published_at IS '发布时间';

CREATE INDEX idx_codegraph_artifacts_repo_snapshot
    ON codegraph_artifacts(repo_id, snapshot_id, created_at DESC);

-- ============================================================================
-- Questions, knowledge and settings
-- ============================================================================

CREATE TABLE qa_conversations (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    client_request_id UUID,
    title VARCHAR(80) NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    snapshot_id UUID,
    provider VARCHAR(160) NOT NULL,
    evidence_status VARCHAR(32) NOT NULL,
    fallback_reason VARCHAR(64),
    answer_payload JSONB NOT NULL,
    thread_id UUID NOT NULL DEFAULT gen_random_uuid(),
    turn_no INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    stop_requested BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_qa_conversations_evidence_status CHECK (
        evidence_status IN ('SUPPORTED','DEGRADED','MODEL_OUTPUT_REJECTED','INSUFFICIENT','UNKNOWN')
    ),
    CONSTRAINT chk_qa_conversations_turn_no CHECK (turn_no > 0),
    CONSTRAINT chk_qa_conversations_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'STOPPED', 'FAILED')
    )
);

COMMENT ON TABLE qa_conversations IS '多轮知识问答线程中的单轮记录';
COMMENT ON COLUMN qa_conversations.id IS '问答唯一标识';
COMMENT ON COLUMN qa_conversations.repo_id IS '所属仓库';
COMMENT ON COLUMN qa_conversations.account_id IS '提问账号';
COMMENT ON COLUMN qa_conversations.client_request_id IS '客户端幂等请求标识';
COMMENT ON COLUMN qa_conversations.title IS '历史记录标题';
COMMENT ON COLUMN qa_conversations.question IS '用户问题';
COMMENT ON COLUMN qa_conversations.answer IS '生成的回答';
COMMENT ON COLUMN qa_conversations.snapshot_id IS '回答所依据的内容版本令牌';
COMMENT ON COLUMN qa_conversations.provider IS '回答提供方';
COMMENT ON COLUMN qa_conversations.evidence_status IS '回答证据状态';
COMMENT ON COLUMN qa_conversations.fallback_reason IS '未使用模型回答或安全降级的原因';
COMMENT ON COLUMN qa_conversations.answer_payload IS '可原样恢复的完整回答快照';
COMMENT ON COLUMN qa_conversations.thread_id IS '多轮问答线程标识；首轮记录通常以自身 ID 作为线程标识';
COMMENT ON COLUMN qa_conversations.turn_no IS '当前记录在线程内的轮次，从 1 开始';
COMMENT ON COLUMN qa_conversations.status IS '生成状态：RUNNING、COMPLETED、STOPPED 或 FAILED';
COMMENT ON COLUMN qa_conversations.stop_requested IS '用户是否已请求停止当前轮次的生成';
COMMENT ON COLUMN qa_conversations.started_at IS '当前轮次开始生成的时间';
COMMENT ON COLUMN qa_conversations.finished_at IS '当前轮次完成、停止或失败的时间';
COMMENT ON COLUMN qa_conversations.created_at IS '创建时间';
COMMENT ON COLUMN qa_conversations.updated_at IS '标题或内容最后更新时间';

CREATE UNIQUE INDEX uk_qa_conversations_client_request
    ON qa_conversations(account_id, repo_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
CREATE INDEX idx_qa_conversations_account_repo_created
    ON qa_conversations(account_id, repo_id, created_at DESC);
CREATE UNIQUE INDEX uk_qa_conversations_thread_turn
    ON qa_conversations(thread_id, turn_no);
CREATE INDEX idx_qa_conversations_thread_created
    ON qa_conversations(thread_id, turn_no, created_at);
CREATE INDEX idx_qa_conversations_account_repo_thread
    ON qa_conversations(account_id, repo_id, thread_id, updated_at DESC);

CREATE TABLE qa_citations (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES qa_conversations(id) ON DELETE CASCADE,
    repository_id UUID REFERENCES repositories(id) ON DELETE SET NULL,
    source_type VARCHAR(20) NOT NULL DEFAULT 'CODE',
    chunk_id UUID REFERENCES code_chunks(id) ON DELETE SET NULL,
    knowledge_card_id UUID,
    title TEXT,
    file_path TEXT NOT NULL,
    symbol_name TEXT,
    start_line INTEGER,
    end_line INTEGER,
    evidence_hash VARCHAR(64) NOT NULL,
    rank INTEGER NOT NULL,
    citation_payload JSONB NOT NULL
);

COMMENT ON TABLE qa_citations IS '问答引用的代码片段及位置';
COMMENT ON COLUMN qa_citations.id IS '引用唯一标识';
COMMENT ON COLUMN qa_citations.conversation_id IS '所属问答';
COMMENT ON COLUMN qa_citations.repository_id IS '引用内容所属仓库';
COMMENT ON COLUMN qa_citations.source_type IS '证据来源类型：代码或知识';
COMMENT ON COLUMN qa_citations.chunk_id IS '对应代码片段';
COMMENT ON COLUMN qa_citations.knowledge_card_id IS '对应知识卡片；代码证据时为空';
COMMENT ON COLUMN qa_citations.title IS '证据显示标题';
COMMENT ON COLUMN qa_citations.file_path IS '引用文件路径';
COMMENT ON COLUMN qa_citations.symbol_name IS '引用符号名称';
COMMENT ON COLUMN qa_citations.start_line IS '引用起始行';
COMMENT ON COLUMN qa_citations.end_line IS '引用结束行';
COMMENT ON COLUMN qa_citations.evidence_hash IS '引用内容摘要';
COMMENT ON COLUMN qa_citations.rank IS '引用排序';
COMMENT ON COLUMN qa_citations.citation_payload IS '可原样恢复的完整引用快照';

CREATE TABLE knowledge_cards (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id),
    title TEXT NOT NULL,
    card_type VARCHAR(40) NOT NULL DEFAULT '模块说明',
    content TEXT NOT NULL,
    source_message_id UUID,
    tags TEXT[] NOT NULL DEFAULT '{}',
    status TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    verified_commit TEXT,
    code_review_status VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED',
    code_reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_knowledge_code_review_status
        CHECK (code_review_status IN ('UNVERIFIED', 'CURRENT', 'REVIEW_REQUIRED'))
);

COMMENT ON TABLE knowledge_cards IS '仓库知识卡片及其当前内容';
COMMENT ON COLUMN knowledge_cards.id IS '知识卡片唯一标识';
COMMENT ON COLUMN knowledge_cards.repo_id IS '所属仓库';
COMMENT ON COLUMN knowledge_cards.title IS '知识标题';
COMMENT ON COLUMN knowledge_cards.card_type IS '知识类型';
COMMENT ON COLUMN knowledge_cards.content IS 'Markdown 知识正文';
COMMENT ON COLUMN knowledge_cards.source_message_id IS '生成该知识的旧问答消息标识';
COMMENT ON COLUMN knowledge_cards.tags IS '知识标签';
COMMENT ON COLUMN knowledge_cards.status IS '草稿、发布、复核或归档状态';
COMMENT ON COLUMN knowledge_cards.revision IS '当前修订号';
COMMENT ON COLUMN knowledge_cards.created_by IS '创建账号';
COMMENT ON COLUMN knowledge_cards.updated_by IS '最后更新账号';
COMMENT ON COLUMN knowledge_cards.verified_commit IS '知识内容最后一次人工确认时对应的代码提交号';
COMMENT ON COLUMN knowledge_cards.code_review_status IS '代码关联状态：未确认、当前有效或代码更新待复核';
COMMENT ON COLUMN knowledge_cards.code_reviewed_at IS '最近一次确认知识与当前代码一致的时间';
COMMENT ON COLUMN knowledge_cards.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_cards.updated_at IS '最后更新时间';

CREATE INDEX idx_knowledge_cards_repo_status
    ON knowledge_cards(repo_id, status, updated_at DESC);

CREATE TABLE knowledge_card_revisions (
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    card_type VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}',
    status VARCHAR(30) NOT NULL,
    changed_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (card_id, revision)
);

COMMENT ON TABLE knowledge_card_revisions IS '知识卡片修订历史，只保存知识历史';
COMMENT ON COLUMN knowledge_card_revisions.card_id IS '知识卡片标识';
COMMENT ON COLUMN knowledge_card_revisions.revision IS '修订号';
COMMENT ON COLUMN knowledge_card_revisions.repo_id IS '所属仓库';
COMMENT ON COLUMN knowledge_card_revisions.title IS '该修订标题';
COMMENT ON COLUMN knowledge_card_revisions.card_type IS '该修订知识类型';
COMMENT ON COLUMN knowledge_card_revisions.content IS '该修订正文';
COMMENT ON COLUMN knowledge_card_revisions.tags IS '该修订标签';
COMMENT ON COLUMN knowledge_card_revisions.status IS '该修订状态';
COMMENT ON COLUMN knowledge_card_revisions.changed_by IS '修改账号';
COMMENT ON COLUMN knowledge_card_revisions.changed_at IS '修改时间';

CREATE INDEX idx_knowledge_card_revisions_repo_card
    ON knowledge_card_revisions(repo_id, card_id, revision DESC);

ALTER TABLE qa_citations
    ADD CONSTRAINT fk_qa_citations_knowledge_card
    FOREIGN KEY (knowledge_card_id) REFERENCES knowledge_cards(id) ON DELETE SET NULL;

CREATE TABLE knowledge_card_embeddings (
    card_id UUID PRIMARY KEY REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    model VARCHAR(200) NOT NULL DEFAULT 'local-hash-64',
    dimension INTEGER NOT NULL,
    embedding vector NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT knowledge_embeddings_dimension_check CHECK (dimension BETWEEN 1 AND 4096),
    CONSTRAINT knowledge_embeddings_vector_dimension_check CHECK (vector_dims(embedding) = dimension)
);

COMMENT ON TABLE knowledge_card_embeddings IS '知识卡片当前修订的检索向量';
COMMENT ON COLUMN knowledge_card_embeddings.card_id IS '知识卡片标识';
COMMENT ON COLUMN knowledge_card_embeddings.repo_id IS '所属仓库';
COMMENT ON COLUMN knowledge_card_embeddings.revision IS '向量对应的知识修订号';
COMMENT ON COLUMN knowledge_card_embeddings.model IS '生成向量的模型标识';
COMMENT ON COLUMN knowledge_card_embeddings.dimension IS '向量维度';
COMMENT ON COLUMN knowledge_card_embeddings.embedding IS '语义检索向量';
COMMENT ON COLUMN knowledge_card_embeddings.content_hash IS '参与向量计算的内容摘要';
COMMENT ON COLUMN knowledge_card_embeddings.created_at IS '向量生成时间';

CREATE INDEX idx_knowledge_card_embeddings_repo
    ON knowledge_card_embeddings(repo_id);

CREATE TABLE knowledge_code_refs (
    card_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    position INTEGER NOT NULL,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    snapshot_id UUID,
    chunk_id UUID REFERENCES code_chunks(id) ON DELETE SET NULL,
    file_path TEXT NOT NULL,
    symbol_name TEXT,
    start_line INTEGER,
    end_line INTEGER,
    content_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (card_id, revision, position),
    FOREIGN KEY (card_id, revision)
        REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE
);

COMMENT ON TABLE knowledge_code_refs IS '知识修订与代码证据的结构化关联';
COMMENT ON COLUMN knowledge_code_refs.card_id IS '知识卡片标识';
COMMENT ON COLUMN knowledge_code_refs.revision IS '知识修订号';
COMMENT ON COLUMN knowledge_code_refs.position IS '关联代码的显示顺序';
COMMENT ON COLUMN knowledge_code_refs.repo_id IS '所属仓库';
COMMENT ON COLUMN knowledge_code_refs.snapshot_id IS '关联代码的内容版本令牌';
COMMENT ON COLUMN knowledge_code_refs.chunk_id IS '关联代码片段；片段删除后可为空';
COMMENT ON COLUMN knowledge_code_refs.file_path IS '关联文件路径';
COMMENT ON COLUMN knowledge_code_refs.symbol_name IS '关联符号名称';
COMMENT ON COLUMN knowledge_code_refs.start_line IS '关联代码起始行';
COMMENT ON COLUMN knowledge_code_refs.end_line IS '关联代码结束行';
COMMENT ON COLUMN knowledge_code_refs.content_hash IS '关联时的代码内容摘要';

CREATE INDEX idx_knowledge_code_refs_card
    ON knowledge_code_refs(card_id, revision);
CREATE INDEX idx_knowledge_code_refs_repo_file
    ON knowledge_code_refs(repo_id, file_path);

CREATE TABLE knowledge_attachments (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    storage_path TEXT NOT NULL,
    uploaded_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    scan_status VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (repo_id, id)
);

COMMENT ON TABLE knowledge_attachments IS '知识卡片上传附件';
COMMENT ON COLUMN knowledge_attachments.id IS '附件唯一标识';
COMMENT ON COLUMN knowledge_attachments.repo_id IS '所属仓库';
COMMENT ON COLUMN knowledge_attachments.original_name IS '上传时文件名';
COMMENT ON COLUMN knowledge_attachments.media_type IS '媒体类型';
COMMENT ON COLUMN knowledge_attachments.size_bytes IS '文件字节数';
COMMENT ON COLUMN knowledge_attachments.sha256 IS '附件内容摘要';
COMMENT ON COLUMN knowledge_attachments.storage_path IS '受管存储路径';
COMMENT ON COLUMN knowledge_attachments.uploaded_by IS '上传账号';
COMMENT ON COLUMN knowledge_attachments.scan_status IS '安全扫描状态';
COMMENT ON COLUMN knowledge_attachments.created_at IS '上传时间';

CREATE INDEX idx_knowledge_attachments_repo_created
    ON knowledge_attachments(repo_id, created_at DESC);

CREATE TABLE knowledge_card_attachment_refs (
    card_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    attachment_id UUID NOT NULL REFERENCES knowledge_attachments(id) ON DELETE RESTRICT,
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (card_id, revision, attachment_id),
    FOREIGN KEY (card_id, revision)
        REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE
);

COMMENT ON TABLE knowledge_card_attachment_refs IS '知识修订与附件的关联';
COMMENT ON COLUMN knowledge_card_attachment_refs.card_id IS '知识卡片标识';
COMMENT ON COLUMN knowledge_card_attachment_refs.revision IS '知识修订号';
COMMENT ON COLUMN knowledge_card_attachment_refs.attachment_id IS '附件标识';
COMMENT ON COLUMN knowledge_card_attachment_refs.position IS '附件显示顺序';

CREATE TABLE system_settings (
    setting_key VARCHAR(120) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE system_settings IS '系统级运行配置';
COMMENT ON COLUMN system_settings.setting_key IS '配置键';
COMMENT ON COLUMN system_settings.setting_value IS '配置值';
COMMENT ON COLUMN system_settings.sensitive IS '读取时是否必须掩码';
COMMENT ON COLUMN system_settings.updated_by IS '最后修改账号';
COMMENT ON COLUMN system_settings.updated_at IS '最后更新时间';

-- ============================================================================
-- LLM provider configuration
-- ============================================================================

CREATE TABLE encrypted_secret_versions (
    id UUID PRIMARY KEY,
    cipher_text TEXT NOT NULL,
    iv TEXT NOT NULL,
    secret_digest CHAR(64) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE encrypted_secret_versions IS 'LLM 密钥的加密版本';
COMMENT ON COLUMN encrypted_secret_versions.id IS '密钥版本唯一标识';
COMMENT ON COLUMN encrypted_secret_versions.cipher_text IS '密文';
COMMENT ON COLUMN encrypted_secret_versions.iv IS '加密初始化向量';
COMMENT ON COLUMN encrypted_secret_versions.secret_digest IS '明文指纹摘要';
COMMENT ON COLUMN encrypted_secret_versions.algorithm IS '加密算法';
COMMENT ON COLUMN encrypted_secret_versions.created_by IS '创建账号';
COMMENT ON COLUMN encrypted_secret_versions.created_at IS '创建时间';

CREATE TABLE vector_model_configs (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    provider_type VARCHAR(40) NOT NULL
        CHECK (provider_type IN ('LOCAL_HASH', 'OPENAI_COMPATIBLE')),
    base_url TEXT,
    model VARCHAR(200) NOT NULL UNIQUE,
    dimension INTEGER NOT NULL,
    request_timeout_ms INTEGER NOT NULL DEFAULT 30000
        CHECK (request_timeout_ms BETWEEN 3000 AND 120000),
    secret_version_id UUID REFERENCES encrypted_secret_versions(id),
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT vector_model_configs_dimension_check
        CHECK (dimension BETWEEN 1 AND 4096)
);

COMMENT ON TABLE vector_model_configs IS '本地及外部向量模型备案';
COMMENT ON COLUMN vector_model_configs.provider_type IS '向量模型运行方式';
COMMENT ON COLUMN vector_model_configs.base_url IS '外部向量服务基础地址';
COMMENT ON COLUMN vector_model_configs.model IS '向量模型标识';
COMMENT ON COLUMN vector_model_configs.dimension IS '输出向量维度';
COMMENT ON COLUMN vector_model_configs.secret_version_id IS '外部服务密钥版本';

CREATE TABLE vector_model_activation (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    active_config_id UUID NOT NULL REFERENCES vector_model_configs(id),
    activation_version BIGINT NOT NULL DEFAULT 0,
    activated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE vector_model_activation IS '系统当前启用的向量模型单例';

CREATE SEQUENCE llm_provider_config_version_seq START WITH 1;
COMMENT ON SEQUENCE llm_provider_config_version_seq IS 'LLM Provider 配置递增版本号';

CREATE TABLE llm_provider_configs (
    id UUID PRIMARY KEY,
    config_version BIGINT NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    base_url TEXT NOT NULL,
    model VARCHAR(200) NOT NULL,
    connect_timeout_ms INTEGER NOT NULL,
    request_timeout_ms INTEGER NOT NULL,
    max_output_tokens INTEGER NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    streaming_enabled BOOLEAN NOT NULL,
    secret_version_id UUID REFERENCES encrypted_secret_versions(id),
    fingerprint CHAR(64) NOT NULL,
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_llm_provider_type CHECK (provider_type IN ('OPENAI_COMPATIBLE')),
    CONSTRAINT chk_llm_connect_timeout CHECK (connect_timeout_ms BETWEEN 1000 AND 10000),
    CONSTRAINT chk_llm_request_timeout CHECK (request_timeout_ms BETWEEN 3000 AND 120000),
    CONSTRAINT chk_llm_max_output_tokens CHECK (max_output_tokens BETWEEN 1 AND 32768),
    CONSTRAINT chk_llm_temperature CHECK (temperature BETWEEN 0 AND 2)
);

COMMENT ON TABLE llm_provider_configs IS 'LLM Provider 不可变配置版本';
COMMENT ON COLUMN llm_provider_configs.id IS '配置唯一标识';
COMMENT ON COLUMN llm_provider_configs.config_version IS '递增配置版本';
COMMENT ON COLUMN llm_provider_configs.name IS '配置显示名称';
COMMENT ON COLUMN llm_provider_configs.provider_type IS 'Provider 协议类型';
COMMENT ON COLUMN llm_provider_configs.base_url IS 'Provider API 基础地址';
COMMENT ON COLUMN llm_provider_configs.model IS '模型标识';
COMMENT ON COLUMN llm_provider_configs.connect_timeout_ms IS '连接超时毫秒数';
COMMENT ON COLUMN llm_provider_configs.request_timeout_ms IS '请求超时毫秒数';
COMMENT ON COLUMN llm_provider_configs.max_output_tokens IS '最大输出 Token 数';
COMMENT ON COLUMN llm_provider_configs.temperature IS '生成温度';
COMMENT ON COLUMN llm_provider_configs.streaming_enabled IS '是否要求流式能力';
COMMENT ON COLUMN llm_provider_configs.secret_version_id IS '使用的加密密钥版本';
COMMENT ON COLUMN llm_provider_configs.fingerprint IS '脱敏配置指纹';
COMMENT ON COLUMN llm_provider_configs.created_by IS '创建账号';
COMMENT ON COLUMN llm_provider_configs.created_at IS '创建时间';

CREATE INDEX idx_llm_configs_created ON llm_provider_configs(created_at DESC);

CREATE TABLE llm_provider_runtime_states (
    config_id UUID PRIMARY KEY REFERENCES llm_provider_configs(id) ON DELETE CASCADE,
    availability VARCHAR(30) NOT NULL DEFAULT 'UNTESTED',
    latest_check_id UUID,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    breaker_state VARCHAR(20) NOT NULL DEFAULT 'CLOSED',
    breaker_opened_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_llm_availability
        CHECK (availability IN ('UNTESTED', 'AVAILABLE', 'DEGRADED', 'UNAVAILABLE')),
    CONSTRAINT chk_llm_breaker CHECK (breaker_state IN ('CLOSED', 'OPEN'))
);

COMMENT ON TABLE llm_provider_runtime_states IS 'LLM Provider 运行状态和熔断状态';
COMMENT ON COLUMN llm_provider_runtime_states.config_id IS '对应 Provider 配置';
COMMENT ON COLUMN llm_provider_runtime_states.availability IS '最近检测得到的可用性';
COMMENT ON COLUMN llm_provider_runtime_states.latest_check_id IS '最近一次连通性检测';
COMMENT ON COLUMN llm_provider_runtime_states.last_success_at IS '最近成功时间';
COMMENT ON COLUMN llm_provider_runtime_states.last_failure_at IS '最近失败时间';
COMMENT ON COLUMN llm_provider_runtime_states.consecutive_failures IS '连续失败次数';
COMMENT ON COLUMN llm_provider_runtime_states.breaker_state IS '熔断器状态';
COMMENT ON COLUMN llm_provider_runtime_states.breaker_opened_at IS '熔断开启时间';
COMMENT ON COLUMN llm_provider_runtime_states.last_error_code IS '最近错误编码';
COMMENT ON COLUMN llm_provider_runtime_states.updated_at IS '最后更新时间';

CREATE TABLE llm_provider_activation (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    active_config_id UUID REFERENCES llm_provider_configs(id),
    activation_version BIGINT NOT NULL DEFAULT 0,
    activated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    activated_at TIMESTAMPTZ,
    CONSTRAINT chk_llm_activation_singleton CHECK (singleton_id = 1)
);

COMMENT ON TABLE llm_provider_activation IS '系统当前启用的 LLM Provider 单例';
COMMENT ON COLUMN llm_provider_activation.singleton_id IS '固定为 1 的单例键';
COMMENT ON COLUMN llm_provider_activation.active_config_id IS '当前启用配置';
COMMENT ON COLUMN llm_provider_activation.activation_version IS '启用状态并发控制版本';
COMMENT ON COLUMN llm_provider_activation.activated_by IS '执行启用的账号';
COMMENT ON COLUMN llm_provider_activation.activated_at IS '启用时间';

CREATE TABLE llm_connectivity_checks (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    config_id UUID REFERENCES llm_provider_configs(id) ON DELETE SET NULL,
    fingerprint CHAR(64) NOT NULL,
    endpoint_host VARCHAR(255) NOT NULL,
    model VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    availability VARCHAR(30) NOT NULL DEFAULT 'UNTESTED',
    current_stage VARCHAR(50),
    stage_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_code VARCHAR(80),
    error_summary VARCHAR(500),
    total_duration_ms BIGINT,
    connect_duration_ms BIGINT,
    first_token_duration_ms BIGINT,
    request_id UUID NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_llm_check_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    CONSTRAINT chk_llm_check_availability
        CHECK (availability IN ('UNTESTED', 'AVAILABLE', 'DEGRADED', 'UNAVAILABLE'))
);

COMMENT ON TABLE llm_connectivity_checks IS 'LLM Provider 连通性检测记录';
COMMENT ON COLUMN llm_connectivity_checks.id IS '检测唯一标识';
COMMENT ON COLUMN llm_connectivity_checks.actor_id IS '发起检测的账号';
COMMENT ON COLUMN llm_connectivity_checks.config_id IS '被检测的已保存配置';
COMMENT ON COLUMN llm_connectivity_checks.fingerprint IS '候选或已保存配置指纹';
COMMENT ON COLUMN llm_connectivity_checks.endpoint_host IS '脱敏后的目标主机';
COMMENT ON COLUMN llm_connectivity_checks.model IS '被检测模型';
COMMENT ON COLUMN llm_connectivity_checks.status IS '检测任务状态';
COMMENT ON COLUMN llm_connectivity_checks.availability IS '检测得到的可用性';
COMMENT ON COLUMN llm_connectivity_checks.current_stage IS '当前或最后检测阶段';
COMMENT ON COLUMN llm_connectivity_checks.stage_results IS '分阶段检测结果';
COMMENT ON COLUMN llm_connectivity_checks.error_code IS '失败错误编码';
COMMENT ON COLUMN llm_connectivity_checks.error_summary IS '脱敏后的失败摘要';
COMMENT ON COLUMN llm_connectivity_checks.total_duration_ms IS '总耗时毫秒数';
COMMENT ON COLUMN llm_connectivity_checks.connect_duration_ms IS '连接耗时毫秒数';
COMMENT ON COLUMN llm_connectivity_checks.first_token_duration_ms IS '首 Token 耗时毫秒数';
COMMENT ON COLUMN llm_connectivity_checks.request_id IS '请求追踪标识';
COMMENT ON COLUMN llm_connectivity_checks.started_at IS '开始时间';
COMMENT ON COLUMN llm_connectivity_checks.finished_at IS '完成时间';
COMMENT ON COLUMN llm_connectivity_checks.created_at IS '记录创建时间';

CREATE INDEX idx_llm_checks_actor_created
    ON llm_connectivity_checks(actor_id, created_at DESC);
CREATE INDEX idx_llm_checks_fingerprint_created
    ON llm_connectivity_checks(fingerprint, created_at DESC);

ALTER TABLE llm_provider_runtime_states
    ADD CONSTRAINT fk_llm_runtime_latest_check
    FOREIGN KEY (latest_check_id) REFERENCES llm_connectivity_checks(id) ON DELETE SET NULL;

-- ============================================================================
-- Triggers and seed data
-- ============================================================================

CREATE OR REPLACE FUNCTION capture_knowledge_card_revision() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(
        card_id, revision, repo_id, title, card_type, content, tags, status, changed_by, changed_at
    ) VALUES (
        NEW.id, NEW.revision, NEW.repo_id, NEW.title, NEW.card_type, NEW.content,
        NEW.tags, NEW.status, NEW.updated_by, NEW.updated_at
    )
    ON CONFLICT (card_id, revision) DO UPDATE SET
        title = EXCLUDED.title,
        card_type = EXCLUDED.card_type,
        content = EXCLUDED.content,
        tags = EXCLUDED.tags,
        status = EXCLUDED.status,
        changed_by = EXCLUDED.changed_by,
        changed_at = EXCLUDED.changed_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION capture_knowledge_card_revision() IS '在知识卡片新增或更新后保存不可丢失的修订记录';

CREATE TRIGGER trg_knowledge_card_revision
AFTER INSERT OR UPDATE OF title, card_type, content, tags, status, revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();

CREATE OR REPLACE FUNCTION mark_repository_knowledge_stale() RETURNS trigger AS $$
BEGIN
    IF OLD.current_commit IS DISTINCT FROM NEW.current_commit THEN
        UPDATE knowledge_cards
           SET code_review_status = 'REVIEW_REQUIRED'
         WHERE repo_id = NEW.id
           AND verified_commit IS NOT NULL
           AND verified_commit IS DISTINCT FROM NEW.current_commit;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION mark_repository_knowledge_stale() IS '仓库代码提交变化后标记需要人工复核的知识卡片';

CREATE TRIGGER trg_repository_knowledge_stale
AFTER UPDATE OF current_commit ON repositories
FOR EACH ROW EXECUTE FUNCTION mark_repository_knowledge_stale();

CREATE OR REPLACE FUNCTION confirm_knowledge_code_version() RETURNS trigger AS $$
BEGIN
    SELECT current_commit INTO NEW.verified_commit FROM repositories WHERE id = NEW.repo_id;
    NEW.code_review_status :=
        CASE WHEN NEW.verified_commit IS NULL THEN 'UNVERIFIED' ELSE 'CURRENT' END;
    NEW.code_reviewed_at :=
        CASE WHEN NEW.verified_commit IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION confirm_knowledge_code_version() IS '知识内容编辑时记录其确认所依据的当前代码提交';

CREATE TRIGGER trg_confirm_knowledge_code_version
BEFORE INSERT OR UPDATE OF title, content, tags, status ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION confirm_knowledge_code_version();

INSERT INTO system_settings(setting_key, setting_value)
VALUES ('externalModelEnabled', 'false');

INSERT INTO llm_provider_activation(singleton_id) VALUES (1);

INSERT INTO vector_model_configs(id,name,provider_type,model,dimension)
VALUES(
    '00000000-0000-0000-0000-000000000064',
    '内置向量模型',
    'LOCAL_HASH',
    'local-hash-64',
    64
);

INSERT INTO vector_model_activation(singleton_id,active_config_id)
VALUES(1,'00000000-0000-0000-0000-000000000064');

-- ============================================================================
-- Retrieval lookup indexes merged into the baseline. Flexible-dimension vectors
-- deliberately use exact cosine scans because pgvector HNSW indexes require a
-- fixed expression dimension.
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_path_line
    ON code_chunks(repo_id, file_path, start_line);

CREATE INDEX IF NOT EXISTS idx_knowledge_refs_current_lookup
    ON knowledge_code_refs(repo_id, file_path, start_line, content_hash);

-- ============================================================================
-- 合并自 V2__markdown_knowledge_sources.sql
-- ============================================================================

-- Repository Markdown files discovered from the current managed snapshot. The
-- source row is stable for a repository path; exact generation provenance is
-- retained separately for each knowledge-card revision.

CREATE TABLE repository_markdown_sources (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL,
    file_path TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    asset_type VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    line_count INTEGER NOT NULL,
    byte_size BIGINT NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_repository_markdown_source_path UNIQUE (repo_id, file_path),
    CONSTRAINT chk_repository_markdown_source_hash
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_repository_markdown_source_asset_type
        CHECK (asset_type IN ('DOCUMENT', 'RULE', 'TASK')),
    CONSTRAINT chk_repository_markdown_source_line_count CHECK (line_count > 0),
    CONSTRAINT chk_repository_markdown_source_byte_size CHECK (byte_size > 0),
    CONSTRAINT chk_repository_markdown_source_path
        CHECK (BTRIM(file_path) <> '' AND file_path !~ '(^|/)\.\.(/|$)')
);

COMMENT ON TABLE repository_markdown_sources IS '当前仓库快照中可生成知识卡片的 Markdown 来源';
COMMENT ON COLUMN repository_markdown_sources.id IS '稳定来源标识，同一仓库相对路径保持不变';
COMMENT ON COLUMN repository_markdown_sources.repo_id IS '所属仓库';
COMMENT ON COLUMN repository_markdown_sources.snapshot_id IS '最近发现该 Markdown 的内容版本令牌';
COMMENT ON COLUMN repository_markdown_sources.file_path IS '仓库内规范化 Markdown 相对路径';
COMMENT ON COLUMN repository_markdown_sources.content_hash IS '完整 UTF-8 Markdown 原文的 SHA-256';
COMMENT ON COLUMN repository_markdown_sources.content IS '用于生成知识卡片的完整 Markdown 原文';

CREATE INDEX idx_repository_markdown_sources_snapshot
    ON repository_markdown_sources(repo_id, snapshot_id, file_path);
CREATE INDEX idx_repository_markdown_sources_path_hash
    ON repository_markdown_sources(repo_id, file_path, content_hash);

CREATE TABLE knowledge_card_markdown_source_links (
    card_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    source_id UUID REFERENCES repository_markdown_sources(id) ON DELETE SET NULL,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    source_snapshot_id UUID NOT NULL,
    source_path TEXT NOT NULL,
    source_content_hash CHAR(64) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (card_id, revision),
    FOREIGN KEY (card_id, revision)
        REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE,
    CONSTRAINT chk_knowledge_markdown_link_hash
        CHECK (source_content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_knowledge_markdown_link_path
        CHECK (BTRIM(source_path) <> '' AND source_path !~ '(^|/)\.\.(/|$)')
);

COMMENT ON TABLE knowledge_card_markdown_source_links IS '知识卡片修订与生成时 Markdown 精确版本的来源凭据';
COMMENT ON COLUMN knowledge_card_markdown_source_links.card_id IS '生成或同步得到的知识卡片';
COMMENT ON COLUMN knowledge_card_markdown_source_links.revision IS '对应知识卡片修订号';
COMMENT ON COLUMN knowledge_card_markdown_source_links.source_id IS '当前来源行；来源删除后允许为空';
COMMENT ON COLUMN knowledge_card_markdown_source_links.source_snapshot_id IS '生成时仓库内容版本令牌';
COMMENT ON COLUMN knowledge_card_markdown_source_links.source_path IS '生成时 Markdown 相对路径';
COMMENT ON COLUMN knowledge_card_markdown_source_links.source_content_hash IS '生成时完整 Markdown 原文 SHA-256';

CREATE INDEX idx_knowledge_markdown_links_source
    ON knowledge_card_markdown_source_links(repo_id, source_path, generated_at DESC);
CREATE INDEX idx_knowledge_markdown_links_card
    ON knowledge_card_markdown_source_links(card_id, revision DESC);

-- ============================================================================
-- 合并自 V3__qa_citation_assessment.sql
-- ============================================================================

-- Citation status describes mechanical coverage only. It must not imply that
-- cited evidence semantically entails the answer. SUPPORTED remains readable
-- for historical rows created before this distinction was introduced.

ALTER TABLE qa_conversations
    DROP CONSTRAINT IF EXISTS chk_qa_conversations_evidence_status;

ALTER TABLE qa_conversations
    ADD CONSTRAINT chk_qa_conversations_evidence_status CHECK (
        evidence_status IN (
            'CITATION_COMPLETE',
            'CITATION_INCOMPLETE',
            'SUPPORTED',
            'DEGRADED',
            'MODEL_OUTPUT_REJECTED',
            'INSUFFICIENT',
            'UNKNOWN'
        )
    );

COMMENT ON COLUMN qa_conversations.evidence_status IS
    '回答证据状态；CITATION_* 仅表示引用编号与段落覆盖，不表示语义蕴含已验证';

-- ============================================================================
-- 合并自 V4__rename_heuristic_call_edges.sql
-- ============================================================================

-- These rows are produced by symbol-token string matching during indexing.
-- They are deliberately kept separate from published CodeGraph CLI artifacts.

ALTER TABLE code_graph_edges RENAME TO heuristic_call_edges;
ALTER INDEX idx_code_graph_edges_source RENAME TO idx_heuristic_call_edges_source;
ALTER INDEX idx_code_graph_edges_target RENAME TO idx_heuristic_call_edges_target;

COMMENT ON TABLE heuristic_call_edges IS
    '索引阶段按“符号名+左括号”字符串规则推断的启发式调用候选，不是 CodeGraph CLI 关系';

-- ============================================================================
-- 合并自 V5__embedding_retrieval_capability.sql
-- ============================================================================

-- A pgvector value does not by itself imply semantic understanding. Persist the
-- generation capability so LOCAL_HASH can never be presented as an embedding model.

ALTER TABLE chunk_embeddings ADD COLUMN retrieval_capability VARCHAR(32);
ALTER TABLE knowledge_card_embeddings ADD COLUMN retrieval_capability VARCHAR(32);

UPDATE chunk_embeddings e
SET retrieval_capability = CASE
    WHEN e.model='local-hash-64' OR EXISTS (
        SELECT 1 FROM vector_model_configs vm
        WHERE vm.model=e.model AND vm.provider_type='LOCAL_HASH'
    ) THEN 'CHARACTER_HASH'
    ELSE 'SEMANTIC_EMBEDDING'
END;

UPDATE knowledge_card_embeddings e
SET retrieval_capability = CASE
    WHEN e.model='local-hash-64' OR EXISTS (
        SELECT 1 FROM vector_model_configs vm
        WHERE vm.model=e.model AND vm.provider_type='LOCAL_HASH'
    ) THEN 'CHARACTER_HASH'
    ELSE 'SEMANTIC_EMBEDDING'
END;

ALTER TABLE chunk_embeddings ALTER COLUMN retrieval_capability SET NOT NULL;
ALTER TABLE knowledge_card_embeddings ALTER COLUMN retrieval_capability SET NOT NULL;

ALTER TABLE chunk_embeddings ADD CONSTRAINT chk_chunk_embeddings_capability
    CHECK (retrieval_capability IN ('CHARACTER_HASH','SEMANTIC_EMBEDDING'));
ALTER TABLE knowledge_card_embeddings ADD CONSTRAINT chk_knowledge_embeddings_capability
    CHECK (retrieval_capability IN ('CHARACTER_HASH','SEMANTIC_EMBEDDING'));

COMMENT ON COLUMN chunk_embeddings.retrieval_capability IS
    'CHARACTER_HASH 为字符哈希相似度；SEMANTIC_EMBEDDING 才表示外部模型语义向量';
COMMENT ON COLUMN knowledge_card_embeddings.retrieval_capability IS
    'CHARACTER_HASH 为字符哈希相似度；SEMANTIC_EMBEDDING 才表示外部模型语义向量';

-- ============================================================================
-- 合并自 V6__index_job_lease_and_failure.sql
-- ============================================================================

ALTER TABLE index_jobs ADD COLUMN heartbeat_at TIMESTAMPTZ;
ALTER TABLE index_jobs ADD COLUMN timeout_at TIMESTAMPTZ;
ALTER TABLE index_jobs ADD COLUMN failure_code VARCHAR(64);

UPDATE index_jobs
SET heartbeat_at=COALESCE(started_at,created_at)
WHERE status IN ('RUNNING','CANCEL_REQUESTED');

COMMENT ON COLUMN index_jobs.heartbeat_at IS 'Worker 最近一次存活心跳；只表示进程仍在处理';
COMMENT ON COLUMN index_jobs.timeout_at IS '任务固定超时截止时间，心跳不会无限延长该截止时间';
COMMENT ON COLUMN index_jobs.failure_code IS '稳定失败代码，例如 CODEGRAPH_TIMEOUT、CODEGRAPH_BUILD_FAILED';

CREATE INDEX idx_index_jobs_running_timeout
    ON index_jobs(timeout_at)
    WHERE status IN ('RUNNING','CANCEL_REQUESTED');

-- ============================================================================
-- 合并自 V7__split_knowledge_states.sql
-- ============================================================================

DROP TRIGGER IF EXISTS trg_confirm_knowledge_code_version ON knowledge_cards;
DROP FUNCTION IF EXISTS confirm_knowledge_code_version();
DROP TRIGGER IF EXISTS trg_repository_knowledge_stale ON repositories;
DROP FUNCTION IF EXISTS mark_repository_knowledge_stale();
DROP TRIGGER IF EXISTS trg_knowledge_card_revision ON knowledge_cards;
DROP FUNCTION IF EXISTS capture_knowledge_card_revision();

ALTER TABLE knowledge_cards RENAME COLUMN status TO publication_status;
ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_code_review_status;
ALTER TABLE knowledge_cards RENAME COLUMN code_review_status TO source_version_status;
ALTER TABLE knowledge_cards RENAME COLUMN code_reviewed_at TO source_version_checked_at;
ALTER TABLE knowledge_cards ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'UNREVIEWED';
ALTER TABLE knowledge_cards ADD COLUMN reviewed_by UUID REFERENCES accounts(id) ON DELETE SET NULL;
ALTER TABLE knowledge_cards ADD COLUMN reviewed_at TIMESTAMPTZ;

UPDATE knowledge_cards
SET publication_status=CASE
        WHEN publication_status='PUBLISHED' THEN 'PUBLISHED'
        WHEN publication_status='ARCHIVED' THEN 'ARCHIVED'
        ELSE 'DRAFT'
    END,
    source_version_status=CASE
        WHEN source_version_status='CURRENT' THEN 'CURRENT'
        WHEN source_version_status='REVIEW_REQUIRED' THEN 'STALE'
        ELSE 'UNVERIFIED'
    END,
    review_status='UNREVIEWED',reviewed_by=NULL,reviewed_at=NULL;

ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_publication_status
    CHECK (publication_status IN ('DRAFT','PUBLISHED','ARCHIVED'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_source_version_status
    CHECK (source_version_status IN ('UNVERIFIED','CURRENT','STALE'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_review_status
    CHECK (review_status IN ('UNREVIEWED','APPROVED','CHANGES_REQUESTED'));

ALTER TABLE knowledge_card_revisions RENAME COLUMN status TO publication_status;

COMMENT ON COLUMN knowledge_cards.publication_status IS '发布状态：草稿、已发布或已归档';
COMMENT ON COLUMN knowledge_cards.source_version_status IS '来源版本状态：未验证、当前或已过期；不表示人工认可内容';
COMMENT ON COLUMN knowledge_cards.source_version_checked_at IS '最近一次自动核对来源版本的时间';
COMMENT ON COLUMN knowledge_cards.review_status IS '人工评审状态，与来源版本及发布状态独立';
COMMENT ON COLUMN knowledge_cards.reviewed_by IS '最近一次人工评审账号';
COMMENT ON COLUMN knowledge_cards.reviewed_at IS '最近一次人工评审时间';
COMMENT ON COLUMN knowledge_card_revisions.publication_status IS '该历史修订保存时的发布状态';

CREATE OR REPLACE FUNCTION capture_knowledge_card_revision() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(
        card_id,revision,repo_id,title,card_type,content,tags,publication_status,changed_by,changed_at
    ) VALUES(
        NEW.id,NEW.revision,NEW.repo_id,NEW.title,NEW.card_type,NEW.content,NEW.tags,
        NEW.publication_status,NEW.updated_by,NEW.updated_at
    )
    ON CONFLICT(card_id,revision) DO UPDATE SET
        title=EXCLUDED.title,card_type=EXCLUDED.card_type,content=EXCLUDED.content,
        tags=EXCLUDED.tags,publication_status=EXCLUDED.publication_status,
        changed_by=EXCLUDED.changed_by,changed_at=EXCLUDED.changed_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_knowledge_card_revision
AFTER INSERT OR UPDATE OF title,card_type,content,tags,publication_status,revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();

CREATE OR REPLACE FUNCTION mark_repository_knowledge_stale() RETURNS trigger AS $$
BEGIN
    IF OLD.current_commit IS DISTINCT FROM NEW.current_commit THEN
        UPDATE knowledge_cards
        SET source_version_status='STALE',source_version_checked_at=CURRENT_TIMESTAMP
        WHERE repo_id=NEW.id AND verified_commit IS NOT NULL
          AND verified_commit IS DISTINCT FROM NEW.current_commit;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_repository_knowledge_stale
AFTER UPDATE OF current_commit ON repositories
FOR EACH ROW EXECUTE FUNCTION mark_repository_knowledge_stale();

-- ============================================================================
-- 合并自 V8__index_execution_plan.sql
-- ============================================================================

ALTER TABLE index_jobs ADD COLUMN execution_mode VARCHAR(20);
ALTER TABLE index_jobs ADD COLUMN fallback_reason VARCHAR(64);

ALTER TABLE index_jobs ADD CONSTRAINT chk_index_jobs_execution_mode
    CHECK (execution_mode IS NULL OR execution_mode IN ('FULL','INCREMENTAL'));

COMMENT ON COLUMN index_jobs.execution_mode IS '实际执行模式；可能与请求的 job_type 不同';
COMMENT ON COLUMN index_jobs.fallback_reason IS '增量请求回退全量的稳定原因代码';

-- ============================================================================
-- 合并自 V9__engineering_knowledge.sql
-- ============================================================================

ALTER TABLE knowledge_cards
    ADD COLUMN knowledge_kind VARCHAR(40) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    ADD COLUMN enforcement VARCHAR(20) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN owner_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    ADD COLUMN scope_payload JSONB NOT NULL DEFAULT '{"pathPatterns":[],"symbols":[],"modules":[]}'::jsonb,
    ADD COLUMN obligations_payload JSONB NOT NULL DEFAULT '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[]}'::jsonb,
    ADD COLUMN last_verified_snapshot_id UUID,
    ADD COLUMN verification_note TEXT;

ALTER TABLE knowledge_card_revisions
    ADD COLUMN knowledge_kind VARCHAR(40) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    ADD COLUMN enforcement VARCHAR(20) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN owner_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    ADD COLUMN scope_payload JSONB NOT NULL DEFAULT '{"pathPatterns":[],"symbols":[],"modules":[]}'::jsonb,
    ADD COLUMN obligations_payload JSONB NOT NULL DEFAULT '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[]}'::jsonb,
    ADD COLUMN last_verified_snapshot_id UUID,
    ADD COLUMN verification_note TEXT;

ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_source_version_status;
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_source_version_status
    CHECK (source_version_status IN ('UNVERIFIED','CURRENT','SUSPECT','STALE'));

ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_kind
    CHECK (knowledge_kind IN (
        'REFERENCE','BUSINESS_RULE','ARCH_DECISION','API_CONTRACT','DATA_CONSTRAINT',
        'TEST_OBLIGATION','SECURITY_POLICY','RUNBOOK','INCIDENT_LESSON','OWNERSHIP','TECH_DEBT'
    ));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_severity
    CHECK (severity IN ('INFO','WARNING','CRITICAL'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_enforcement
    CHECK (enforcement IN ('REFERENCE','ADVISORY','REQUIRED'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_scope_payload
    CHECK (
        jsonb_typeof(scope_payload)='object'
        AND jsonb_typeof(scope_payload->'pathPatterns')='array'
        AND jsonb_typeof(scope_payload->'symbols')='array'
        AND jsonb_typeof(scope_payload->'modules')='array'
    );
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_obligations_payload
    CHECK (
        jsonb_typeof(obligations_payload)='object'
        AND jsonb_typeof(obligations_payload->'requiredTests')='array'
        AND jsonb_typeof(obligations_payload->'requiredApproverAccountIds')='array'
        AND jsonb_typeof(obligations_payload->'instructions')='array'
    );

ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_kind
    CHECK (knowledge_kind IN (
        'REFERENCE','BUSINESS_RULE','ARCH_DECISION','API_CONTRACT','DATA_CONSTRAINT',
        'TEST_OBLIGATION','SECURITY_POLICY','RUNBOOK','INCIDENT_LESSON','OWNERSHIP','TECH_DEBT'
    ));
ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_severity
    CHECK (severity IN ('INFO','WARNING','CRITICAL'));
ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_enforcement
    CHECK (enforcement IN ('REFERENCE','ADVISORY','REQUIRED'));

CREATE INDEX idx_knowledge_cards_engineering_policy
    ON knowledge_cards(repo_id,knowledge_kind,enforcement,publication_status);

COMMENT ON COLUMN knowledge_cards.knowledge_kind IS '可参与开发检查的工程知识类型';
COMMENT ON COLUMN knowledge_cards.severity IS '知识不满足时的业务严重程度';
COMMENT ON COLUMN knowledge_cards.enforcement IS '参考、建议或必须执行';
COMMENT ON COLUMN knowledge_cards.owner_account_id IS '工程知识负责人';
COMMENT ON COLUMN knowledge_cards.scope_payload IS '仓库内适用路径、符号和模块';
COMMENT ON COLUMN knowledge_cards.obligations_payload IS '命中知识后要求的测试、审批和开发动作';
COMMENT ON COLUMN knowledge_cards.last_verified_snapshot_id IS '最近完成人工或代码证据验证的仓库快照';
COMMENT ON COLUMN knowledge_cards.verification_note IS '最近验证说明';

DROP TRIGGER IF EXISTS trg_knowledge_card_revision ON knowledge_cards;
DROP FUNCTION IF EXISTS capture_knowledge_card_revision();

CREATE OR REPLACE FUNCTION capture_knowledge_card_revision() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(
        card_id,revision,repo_id,title,card_type,content,tags,publication_status,
        knowledge_kind,severity,enforcement,owner_account_id,scope_payload,obligations_payload,
        last_verified_snapshot_id,verification_note,changed_by,changed_at
    ) VALUES(
        NEW.id,NEW.revision,NEW.repo_id,NEW.title,NEW.card_type,NEW.content,NEW.tags,
        NEW.publication_status,NEW.knowledge_kind,NEW.severity,NEW.enforcement,NEW.owner_account_id,
        NEW.scope_payload,NEW.obligations_payload,NEW.last_verified_snapshot_id,
        NEW.verification_note,NEW.updated_by,NEW.updated_at
    )
    ON CONFLICT(card_id,revision) DO UPDATE SET
        title=EXCLUDED.title,card_type=EXCLUDED.card_type,content=EXCLUDED.content,
        tags=EXCLUDED.tags,publication_status=EXCLUDED.publication_status,
        knowledge_kind=EXCLUDED.knowledge_kind,severity=EXCLUDED.severity,
        enforcement=EXCLUDED.enforcement,owner_account_id=EXCLUDED.owner_account_id,
        scope_payload=EXCLUDED.scope_payload,obligations_payload=EXCLUDED.obligations_payload,
        last_verified_snapshot_id=EXCLUDED.last_verified_snapshot_id,
        verification_note=EXCLUDED.verification_note,changed_by=EXCLUDED.changed_by,
        changed_at=EXCLUDED.changed_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_knowledge_card_revision
AFTER INSERT OR UPDATE OF title,card_type,content,tags,publication_status,knowledge_kind,
    severity,enforcement,owner_account_id,scope_payload,obligations_payload,
    last_verified_snapshot_id,verification_note,revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();

-- ============================================================================
-- 合并自 V10__task_reviews.sql
-- ============================================================================

CREATE TABLE task_reviews (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES accounts(id),
    client_request_id UUID NOT NULL,
    task TEXT,
    change_source VARCHAR(24) NOT NULL,
    base_ref VARCHAR(200),
    head_ref VARCHAR(200),
    model_config_id UUID REFERENCES llm_provider_configs(id) ON DELETE SET NULL,
    base_commit VARCHAR(64),
    head_commit VARCHAR(64),
    snapshot_id UUID NOT NULL,
    worktree_digest VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    result_payload JSONB,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT chk_task_reviews_source
        CHECK (change_source IN ('WORKTREE','SINGLE_COMMIT','COMMIT_RANGE')),
    CONSTRAINT chk_task_reviews_status
        CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_task_reviews_terminal
        CHECK (
            (status='RUNNING' AND finished_at IS NULL AND result_payload IS NULL)
            OR (status='COMPLETED' AND finished_at IS NOT NULL AND result_payload IS NOT NULL
                AND error_code IS NULL AND error_message IS NULL)
            OR (status='FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_task_reviews_client_request
    ON task_reviews(created_by,repo_id,client_request_id);
CREATE INDEX idx_task_reviews_repo_created
    ON task_reviews(repo_id,created_at DESC,id DESC);

COMMENT ON TABLE task_reviews IS '绑定 Git 版本、仓库快照和确定性知识事实的不可变任务审查';
COMMENT ON COLUMN task_reviews.client_request_id IS '调用方提供的幂等请求标识';
COMMENT ON COLUMN task_reviews.model_config_id IS '预留的模型配置，本阶段只保存且不调用模型';
COMMENT ON COLUMN task_reviews.result_payload IS '完成后不可变的完整审查结果 JSON';

CREATE OR REPLACE FUNCTION prevent_terminal_task_review_update() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('COMPLETED','FAILED') THEN
        RAISE EXCEPTION 'terminal task review is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_reviews_immutable
BEFORE UPDATE ON task_reviews
FOR EACH ROW EXECUTE FUNCTION prevent_terminal_task_review_update();

-- ============================================================================
-- 合并自 V11__knowledge_drift_audit.sql
-- ============================================================================

DROP TRIGGER IF EXISTS trg_repository_knowledge_stale ON repositories;
DROP FUNCTION IF EXISTS mark_repository_knowledge_stale();

CREATE TABLE knowledge_drift_events (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    card_revision INTEGER NOT NULL CHECK (card_revision > 0),
    from_snapshot_id UUID,
    to_snapshot_id UUID NOT NULL,
    from_commit VARCHAR(128),
    to_commit VARCHAR(128),
    previous_status VARCHAR(32) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(40) NOT NULL,
    reasons_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    note TEXT,
    actor_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_knowledge_drift_previous_status
        CHECK (previous_status IN ('UNVERIFIED','CURRENT','SUSPECT','STALE')),
    CONSTRAINT chk_knowledge_drift_result_status
        CHECK (result_status IN ('CURRENT','SUSPECT','STALE')),
    CONSTRAINT chk_knowledge_drift_trigger_type
        CHECK (trigger_type IN ('AUTOMATIC_DIFF','MANUAL_CONFIRM_CURRENT','MANUAL_MARK_STALE')),
    CONSTRAINT chk_knowledge_drift_reasons_payload CHECK (jsonb_typeof(reasons_payload)='array')
);

CREATE UNIQUE INDEX uq_knowledge_drift_automatic_snapshot
    ON knowledge_drift_events(card_id,card_revision,to_snapshot_id,trigger_type)
    WHERE trigger_type='AUTOMATIC_DIFF';
CREATE INDEX idx_knowledge_drift_card_created
    ON knowledge_drift_events(repo_id,card_id,created_at DESC,id DESC);

COMMENT ON TABLE knowledge_drift_events IS '知识来源版本的自动漂移与人工复核审计';
COMMENT ON COLUMN knowledge_drift_events.reasons_payload IS '触发状态变化的结构化 Git、代码引用或符号证据';

-- ============================================================================
-- 合并自 V12__ci_knowledge_obligations.sql
-- ============================================================================

UPDATE knowledge_cards
SET obligations_payload = obligations_payload
    || CASE WHEN NOT (obligations_payload ? 'prohibitedPathPatterns')
        THEN '{"prohibitedPathPatterns":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (obligations_payload ? 'knowledgeUpdateRequired')
        THEN '{"knowledgeUpdateRequired":false}'::jsonb ELSE '{}'::jsonb END
WHERE NOT (obligations_payload ? 'prohibitedPathPatterns')
   OR NOT (obligations_payload ? 'knowledgeUpdateRequired');

UPDATE knowledge_card_revisions
SET obligations_payload = obligations_payload
    || CASE WHEN NOT (obligations_payload ? 'prohibitedPathPatterns')
        THEN '{"prohibitedPathPatterns":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (obligations_payload ? 'knowledgeUpdateRequired')
        THEN '{"knowledgeUpdateRequired":false}'::jsonb ELSE '{}'::jsonb END
WHERE NOT (obligations_payload ? 'prohibitedPathPatterns')
   OR NOT (obligations_payload ? 'knowledgeUpdateRequired');

ALTER TABLE knowledge_cards ALTER COLUMN obligations_payload SET DEFAULT
    '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[],"prohibitedPathPatterns":[],"knowledgeUpdateRequired":false}'::jsonb;
ALTER TABLE knowledge_card_revisions ALTER COLUMN obligations_payload SET DEFAULT
    '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[],"prohibitedPathPatterns":[],"knowledgeUpdateRequired":false}'::jsonb;

ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_obligations_payload;
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_obligations_payload CHECK (
    jsonb_typeof(obligations_payload)='object'
    AND jsonb_typeof(obligations_payload->'requiredTests')='array'
    AND jsonb_typeof(obligations_payload->'requiredApproverAccountIds')='array'
    AND jsonb_typeof(obligations_payload->'instructions')='array'
    AND jsonb_typeof(obligations_payload->'prohibitedPathPatterns')='array'
    AND jsonb_typeof(obligations_payload->'knowledgeUpdateRequired')='boolean'
);

ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_obligations_payload CHECK (
    jsonb_typeof(obligations_payload)='object'
    AND jsonb_typeof(obligations_payload->'requiredTests')='array'
    AND jsonb_typeof(obligations_payload->'requiredApproverAccountIds')='array'
    AND jsonb_typeof(obligations_payload->'instructions')='array'
    AND jsonb_typeof(obligations_payload->'prohibitedPathPatterns')='array'
    AND jsonb_typeof(obligations_payload->'knowledgeUpdateRequired')='boolean'
);

COMMENT ON COLUMN knowledge_cards.obligations_payload IS
    '命中知识后要求的测试、审批、禁止路径、知识同步和补充开发动作';

-- ============================================================================
-- 合并自 V13__engineering_projects.sql
-- ============================================================================

CREATE TABLE engineering_projects (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES accounts(id),
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_engineering_project_version CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_engineering_projects_name
    ON engineering_projects(normalized_name) WHERE deleted_at IS NULL;

CREATE TABLE engineering_project_repositories (
    project_id UUID NOT NULL REFERENCES engineering_projects(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE RESTRICT,
    service_name VARCHAR(100) NOT NULL,
    normalized_service_name VARCHAR(100) NOT NULL,
    added_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(project_id,repo_id),
    CONSTRAINT uq_engineering_project_service UNIQUE(project_id,normalized_service_name)
);

CREATE INDEX idx_engineering_project_repositories_repo
    ON engineering_project_repositories(repo_id,project_id);

CREATE TABLE engineering_project_contracts (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES engineering_projects(id) ON DELETE CASCADE,
    contract_key VARCHAR(120) NOT NULL,
    normalized_contract_key VARCHAR(120) NOT NULL,
    name VARCHAR(160) NOT NULL,
    provider_repo_id UUID NOT NULL,
    consumer_repo_id UUID NOT NULL,
    provider_snapshot_id UUID NOT NULL,
    consumer_snapshot_id UUID NOT NULL,
    provider_evidence_path VARCHAR(1000) NOT NULL,
    consumer_evidence_path VARCHAR(1000) NOT NULL,
    provider_content_fingerprint VARCHAR(64) NOT NULL,
    consumer_content_fingerprint VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_engineering_project_contract UNIQUE(project_id,normalized_contract_key),
    CONSTRAINT chk_engineering_contract_repositories CHECK(provider_repo_id<>consumer_repo_id),
    CONSTRAINT fk_engineering_contract_provider FOREIGN KEY(project_id,provider_repo_id)
        REFERENCES engineering_project_repositories(project_id,repo_id) ON DELETE RESTRICT,
    CONSTRAINT fk_engineering_contract_consumer FOREIGN KEY(project_id,consumer_repo_id)
        REFERENCES engineering_project_repositories(project_id,repo_id) ON DELETE RESTRICT
);

CREATE INDEX idx_engineering_project_contracts_provider
    ON engineering_project_contracts(provider_repo_id,project_id);
CREATE INDEX idx_engineering_project_contracts_consumer
    ON engineering_project_contracts(consumer_repo_id,project_id);

COMMENT ON TABLE engineering_projects IS '把多个真实仓库组织为一个可治理的工程项目';
COMMENT ON TABLE engineering_project_repositories IS '工程项目中的仓库及其显式服务身份';
COMMENT ON TABLE engineering_project_contracts IS '以两端当前代码路径和内容指纹验证的跨仓接口契约';
COMMENT ON COLUMN engineering_project_contracts.provider_content_fingerprint IS
    '创建或更新时对提供方当前路径全部 Chunk 内容哈希生成的稳定指纹';
COMMENT ON COLUMN engineering_project_contracts.consumer_content_fingerprint IS
    '创建或更新时对消费方当前路径全部 Chunk 内容哈希生成的稳定指纹';

UPDATE knowledge_cards
SET scope_payload = scope_payload
    || CASE WHEN NOT (scope_payload ? 'repositoryIds')
        THEN '{"repositoryIds":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'serviceNames')
        THEN '{"serviceNames":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'contractIds')
        THEN '{"contractIds":[]}'::jsonb ELSE '{}'::jsonb END
WHERE NOT (scope_payload ? 'repositoryIds')
   OR NOT (scope_payload ? 'serviceNames')
   OR NOT (scope_payload ? 'contractIds');

UPDATE knowledge_card_revisions
SET scope_payload = scope_payload
    || CASE WHEN NOT (scope_payload ? 'repositoryIds')
        THEN '{"repositoryIds":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'serviceNames')
        THEN '{"serviceNames":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'contractIds')
        THEN '{"contractIds":[]}'::jsonb ELSE '{}'::jsonb END
WHERE NOT (scope_payload ? 'repositoryIds')
   OR NOT (scope_payload ? 'serviceNames')
   OR NOT (scope_payload ? 'contractIds');

ALTER TABLE knowledge_cards ALTER COLUMN scope_payload SET DEFAULT
    '{"pathPatterns":[],"symbols":[],"modules":[],"repositoryIds":[],"serviceNames":[],"contractIds":[]}'::jsonb;
ALTER TABLE knowledge_card_revisions ALTER COLUMN scope_payload SET DEFAULT
    '{"pathPatterns":[],"symbols":[],"modules":[],"repositoryIds":[],"serviceNames":[],"contractIds":[]}'::jsonb;

ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_scope_payload;
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_scope_payload CHECK (
    jsonb_typeof(scope_payload)='object'
    AND jsonb_typeof(scope_payload->'pathPatterns')='array'
    AND jsonb_typeof(scope_payload->'symbols')='array'
    AND jsonb_typeof(scope_payload->'modules')='array'
    AND jsonb_typeof(scope_payload->'repositoryIds')='array'
    AND jsonb_typeof(scope_payload->'serviceNames')='array'
    AND jsonb_typeof(scope_payload->'contractIds')='array'
);

ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_scope_payload CHECK (
    jsonb_typeof(scope_payload)='object'
    AND jsonb_typeof(scope_payload->'pathPatterns')='array'
    AND jsonb_typeof(scope_payload->'symbols')='array'
    AND jsonb_typeof(scope_payload->'modules')='array'
    AND jsonb_typeof(scope_payload->'repositoryIds')='array'
    AND jsonb_typeof(scope_payload->'serviceNames')='array'
    AND jsonb_typeof(scope_payload->'contractIds')='array'
);

COMMENT ON COLUMN knowledge_cards.scope_payload IS
    '仓库内路径/符号/模块及工程项目中的仓库/服务/契约适用范围';

-- ============================================================================
-- 合并自 V14__task_review_outcomes.sql
-- ============================================================================

ALTER TABLE task_reviews
    ADD CONSTRAINT uq_task_reviews_id_repo UNIQUE (id, repo_id);

CREATE TABLE task_review_outcomes (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    review_id UUID NOT NULL,
    reported_by UUID NOT NULL REFERENCES accounts(id),
    client_request_id UUID NOT NULL,
    final_commit VARCHAR(64) NOT NULL,
    commit_binding VARCHAR(40) NOT NULL,
    summary VARCHAR(4000) NOT NULL,
    tests_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    approvals_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    payload_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_review_outcomes_review
        FOREIGN KEY (review_id, repo_id) REFERENCES task_reviews(id, repo_id) ON DELETE CASCADE,
    CONSTRAINT chk_task_review_outcomes_commit
        CHECK (final_commit ~ '^[0-9a-f]{40,64}$'),
    CONSTRAINT chk_task_review_outcomes_binding
        CHECK (commit_binding IN ('EXACT_REVIEW_HEAD','REPORTER_ASSERTED_FINAL')),
    CONSTRAINT chk_task_review_outcomes_summary
        CHECK (length(trim(summary)) BETWEEN 1 AND 4000),
    CONSTRAINT chk_task_review_outcomes_tests
        CHECK (jsonb_typeof(tests_payload)='array'),
    CONSTRAINT chk_task_review_outcomes_approvals
        CHECK (jsonb_typeof(approvals_payload)='array'),
    CONSTRAINT chk_task_review_outcomes_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_task_review_outcomes_client_request
    ON task_review_outcomes(review_id, reported_by, client_request_id);
CREATE INDEX idx_task_review_outcomes_review_created
    ON task_review_outcomes(review_id, created_at DESC, id DESC);
CREATE INDEX idx_task_review_outcomes_repo_created
    ON task_review_outcomes(repo_id, created_at DESC, id DESC);

CREATE TABLE task_review_feedback (
    id UUID PRIMARY KEY,
    outcome_id UUID NOT NULL REFERENCES task_review_outcomes(id) ON DELETE CASCADE,
    kind VARCHAR(30) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_key VARCHAR(500) NOT NULL,
    knowledge_id UUID,
    knowledge_update_assessment VARCHAR(20),
    comment VARCHAR(2000) NOT NULL,
    evidence_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_task_review_feedback_kind
        CHECK (kind IN ('FALSE_POSITIVE','FALSE_NEGATIVE','KNOWLEDGE_UPDATE')),
    CONSTRAINT chk_task_review_feedback_target
        CHECK (target_type IN (
            'KNOWLEDGE','REQUIRED_TEST','REQUIRED_APPROVAL','STALE_KNOWLEDGE',
            'UNKNOWN','FILE','SYMBOL','OTHER'
        )),
    CONSTRAINT chk_task_review_feedback_key
        CHECK (length(trim(target_key)) BETWEEN 1 AND 500),
    CONSTRAINT chk_task_review_feedback_comment
        CHECK (length(trim(comment)) BETWEEN 1 AND 2000),
    CONSTRAINT chk_task_review_feedback_evidence
        CHECK (jsonb_typeof(evidence_urls)='array'),
    CONSTRAINT chk_task_review_feedback_knowledge_update
        CHECK (
            (kind='KNOWLEDGE_UPDATE'
                AND target_type='KNOWLEDGE'
                AND knowledge_id IS NOT NULL
                AND knowledge_update_assessment IN ('NEEDED','NOT_NEEDED','UNKNOWN'))
            OR
            (kind IN ('FALSE_POSITIVE','FALSE_NEGATIVE')
                AND knowledge_update_assessment IS NULL)
        )
);

CREATE INDEX idx_task_review_feedback_outcome
    ON task_review_feedback(outcome_id, created_at, id);
CREATE INDEX idx_task_review_feedback_knowledge
    ON task_review_feedback(knowledge_id)
    WHERE knowledge_id IS NOT NULL;

COMMENT ON TABLE task_review_outcomes IS
    '对不可变 Task Review 的追加式开发结果回报；每条保留报告人和幂等请求';
COMMENT ON COLUMN task_review_outcomes.commit_binding IS
    '仅完全等于审查 Head 时为精确绑定；其他 Commit 只是报告人声明';
COMMENT ON TABLE task_review_feedback IS
    '具名人工误报、漏报和知识更新判断；只供评测和改进，不触发知识修改';

CREATE OR REPLACE FUNCTION prevent_task_review_outcome_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'task review outcomes and feedback are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_review_outcomes_immutable
BEFORE UPDATE ON task_review_outcomes
FOR EACH ROW EXECUTE FUNCTION prevent_task_review_outcome_update();

CREATE TRIGGER trg_task_review_feedback_immutable
BEFORE UPDATE ON task_review_feedback
FOR EACH ROW EXECUTE FUNCTION prevent_task_review_outcome_update();
