-- Codebase Knowledge Platform database baseline.
-- This file represents the complete current schema. A repository tracks one branch
-- and retains only one published code version; snapshot_id columns are consistency
-- tokens for derived data, not references to retained historical source copies.

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

CREATE TABLE repository_credentials (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    credential_type TEXT NOT NULL,
    display_name TEXT NOT NULL,
    encrypted_secret TEXT NOT NULL,
    masked_value TEXT NOT NULL,
    credential_version BIGINT NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    last_validated_at TIMESTAMP,
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE repository_credentials IS '仓库专用加密凭据';
COMMENT ON COLUMN repository_credentials.id IS '凭据唯一标识';
COMMENT ON COLUMN repository_credentials.repo_id IS '所属仓库';
COMMENT ON COLUMN repository_credentials.credential_type IS '凭据类型';
COMMENT ON COLUMN repository_credentials.display_name IS '凭据显示名称';
COMMENT ON COLUMN repository_credentials.encrypted_secret IS '加密后的敏感内容';
COMMENT ON COLUMN repository_credentials.masked_value IS '用于界面显示的掩码';
COMMENT ON COLUMN repository_credentials.credential_version IS '凭据版本';
COMMENT ON COLUMN repository_credentials.status IS '凭据状态';
COMMENT ON COLUMN repository_credentials.last_validated_at IS '最近验证时间';
COMMENT ON COLUMN repository_credentials.created_by IS '创建账号';
COMMENT ON COLUMN repository_credentials.created_at IS '创建时间';
COMMENT ON COLUMN repository_credentials.updated_at IS '最后更新时间';

CREATE INDEX idx_repository_credentials_repo
    ON repository_credentials(repo_id, created_at DESC);

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
    start_line INTEGER,
    end_line INTEGER,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE code_chunks IS '当前代码版本切分得到的可检索代码片段';
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
COMMENT ON COLUMN code_chunks.start_line IS '起始行号';
COMMENT ON COLUMN code_chunks.end_line IS '结束行号';
COMMENT ON COLUMN code_chunks.content IS '用于检索和引用的代码正文';
COMMENT ON COLUMN code_chunks.content_hash IS '代码正文摘要';
COMMENT ON COLUMN code_chunks.created_at IS '生成时间';

CREATE INDEX idx_code_chunks_repo_file ON code_chunks(repo_id, file_path);
CREATE INDEX idx_code_chunks_repo_symbol ON code_chunks(repo_id, symbol_id);
CREATE INDEX idx_code_chunks_repo_created_at ON code_chunks(repo_id, created_at DESC);
CREATE INDEX idx_code_chunks_repo_language ON code_chunks(repo_id, language);
CREATE INDEX idx_code_chunks_repo_snapshot ON code_chunks(repo_id, snapshot_id);

CREATE TABLE chunk_embeddings (
    chunk_id UUID PRIMARY KEY REFERENCES code_chunks(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    model VARCHAR(100) NOT NULL,
    dimension INTEGER NOT NULL,
    embedding vector(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE chunk_embeddings IS '当前代码片段的向量表示';
COMMENT ON COLUMN chunk_embeddings.chunk_id IS '对应代码片段';
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

CREATE TABLE qa_sessions (
    id UUID PRIMARY KEY,
    repo_id UUID REFERENCES repositories(id),
    title TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE qa_sessions IS '兼容旧问答模型的会话';
COMMENT ON COLUMN qa_sessions.id IS '会话唯一标识';
COMMENT ON COLUMN qa_sessions.repo_id IS '所属仓库';
COMMENT ON COLUMN qa_sessions.title IS '会话标题';
COMMENT ON COLUMN qa_sessions.created_at IS '创建时间';
COMMENT ON COLUMN qa_sessions.updated_at IS '最后更新时间';

CREATE INDEX idx_qa_sessions_repo_updated_at ON qa_sessions(repo_id, updated_at DESC);

CREATE TABLE qa_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES qa_sessions(id),
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    citations JSONB,
    retrieval_trace JSONB,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE qa_messages IS '兼容旧问答模型的消息';
COMMENT ON COLUMN qa_messages.id IS '消息唯一标识';
COMMENT ON COLUMN qa_messages.session_id IS '所属会话';
COMMENT ON COLUMN qa_messages.role IS '消息角色';
COMMENT ON COLUMN qa_messages.content IS '消息正文';
COMMENT ON COLUMN qa_messages.citations IS '引用快照';
COMMENT ON COLUMN qa_messages.retrieval_trace IS '检索过程记录';
COMMENT ON COLUMN qa_messages.created_at IS '创建时间';

CREATE TABLE qa_conversations (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    snapshot_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE qa_conversations IS '代码问答记录';
COMMENT ON COLUMN qa_conversations.id IS '问答唯一标识';
COMMENT ON COLUMN qa_conversations.repo_id IS '所属仓库';
COMMENT ON COLUMN qa_conversations.account_id IS '提问账号';
COMMENT ON COLUMN qa_conversations.question IS '用户问题';
COMMENT ON COLUMN qa_conversations.answer IS '生成的回答';
COMMENT ON COLUMN qa_conversations.snapshot_id IS '回答所依据的内容版本令牌';
COMMENT ON COLUMN qa_conversations.created_at IS '创建时间';

CREATE TABLE qa_citations (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES qa_conversations(id) ON DELETE CASCADE,
    source_type VARCHAR(20) NOT NULL DEFAULT 'CODE',
    chunk_id UUID REFERENCES code_chunks(id) ON DELETE SET NULL,
    knowledge_card_id UUID,
    title TEXT,
    file_path TEXT NOT NULL,
    symbol_name TEXT,
    start_line INTEGER,
    end_line INTEGER,
    evidence_hash VARCHAR(64) NOT NULL,
    rank INTEGER NOT NULL
);

COMMENT ON TABLE qa_citations IS '问答引用的代码片段及位置';
COMMENT ON COLUMN qa_citations.id IS '引用唯一标识';
COMMENT ON COLUMN qa_citations.conversation_id IS '所属问答';
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
    embedding vector(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE knowledge_card_embeddings IS '知识卡片当前修订的检索向量';
COMMENT ON COLUMN knowledge_card_embeddings.card_id IS '知识卡片标识';
COMMENT ON COLUMN knowledge_card_embeddings.repo_id IS '所属仓库';
COMMENT ON COLUMN knowledge_card_embeddings.revision IS '向量对应的知识修订号';
COMMENT ON COLUMN knowledge_card_embeddings.model IS '生成向量的模型标识';
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
    dimension INTEGER NOT NULL CHECK (dimension = 64),
    request_timeout_ms INTEGER NOT NULL DEFAULT 30000
        CHECK (request_timeout_ms BETWEEN 3000 AND 120000),
    secret_version_id UUID REFERENCES encrypted_secret_versions(id),
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
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
-- Retrieval accuracy indexes merged from V2
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_cosine
    ON chunk_embeddings USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_knowledge_embeddings_cosine
    ON knowledge_card_embeddings USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_path_line
    ON code_chunks(repo_id, file_path, start_line);

CREATE INDEX IF NOT EXISTS idx_knowledge_refs_current_lookup
    ON knowledge_code_refs(repo_id, file_path, start_line, content_hash);
