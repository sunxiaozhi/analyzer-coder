-- Fresh-install schema consolidated from V1-V9. Requires PostgreSQL with pgvector.
-- Apply only to an empty database; existing Flyway histories are intentionally unsupported.

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';

CREATE FUNCTION assign_code_chunk_branch() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.branch_id IS NULL THEN
        SELECT b.id INTO NEW.branch_id FROM repository_branches b
        JOIN repositories r ON r.id=b.repo_id
        WHERE b.repo_id=NEW.repo_id
          AND (b.content_version=NEW.content_version
               OR b.previous_content_version=NEW.content_version
               OR b.name=COALESCE(r.default_branch,'WORKSPACE'))
        ORDER BY CASE WHEN b.content_version=NEW.content_version THEN 0
                      WHEN b.previous_content_version=NEW.content_version THEN 1 ELSE 2 END
        LIMIT 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION capture_knowledge_card_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(
        card_id,revision,repo_id,branch_id,title,card_type,content,tags,publication_status,
        knowledge_kind,severity,enforcement,owner_account_id,scope_payload,obligations_payload,
        last_verified_content_version,verification_note,changed_by,changed_at
    ) VALUES(
        NEW.id,NEW.revision,NEW.repo_id,NEW.branch_id,NEW.title,NEW.card_type,NEW.content,NEW.tags,
        NEW.publication_status,NEW.knowledge_kind,NEW.severity,NEW.enforcement,NEW.owner_account_id,
        NEW.scope_payload,NEW.obligations_payload,NEW.last_verified_content_version,
        NEW.verification_note,NEW.updated_by,NEW.updated_at
    )
    ON CONFLICT(card_id,revision) DO UPDATE SET
        branch_id=EXCLUDED.branch_id,title=EXCLUDED.title,card_type=EXCLUDED.card_type,
        content=EXCLUDED.content,tags=EXCLUDED.tags,publication_status=EXCLUDED.publication_status,
        knowledge_kind=EXCLUDED.knowledge_kind,severity=EXCLUDED.severity,
        enforcement=EXCLUDED.enforcement,owner_account_id=EXCLUDED.owner_account_id,
        scope_payload=EXCLUDED.scope_payload,obligations_payload=EXCLUDED.obligations_payload,
        last_verified_content_version=EXCLUDED.last_verified_content_version,
        verification_note=EXCLUDED.verification_note,changed_by=EXCLUDED.changed_by,
        changed_at=EXCLUDED.changed_at;
    RETURN NEW;
END;
$$;

CREATE FUNCTION cleanup_deleted_repository_branches() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM repository_branches WHERE repo_id=NEW.id;
    RETURN NEW;
END;
$$;

CREATE FUNCTION synchronize_default_repository_branch() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE branch_uuid UUID;
BEGIN
    branch_uuid := md5(NEW.id::text || ':branch:' || COALESCE(NEW.default_branch,'WORKSPACE'))::uuid;
    INSERT INTO repository_branches(id,repo_id,name)
    VALUES(branch_uuid,NEW.id,COALESCE(NEW.default_branch,'WORKSPACE'))
    ON CONFLICT(repo_id,name) DO NOTHING;
    IF NEW.source_type='ZIP' THEN
        DELETE FROM repository_branches WHERE repo_id=NEW.id AND id<>branch_uuid;
    END IF;
    SELECT id INTO branch_uuid FROM repository_branches
    WHERE repo_id=NEW.id AND name=COALESCE(NEW.default_branch,'WORKSPACE');
    IF NEW.current_content_version IS NOT NULL AND NEW.current_workspace_path IS NOT NULL
       AND EXISTS(SELECT 1 FROM repository_branches WHERE id=branch_uuid AND tracking_status='ACTIVE') THEN
        UPDATE repository_branches SET
            content_version=NEW.current_content_version,
            commit_sha=COALESCE(NEW.current_commit,NEW.worktree_digest,'WORKSPACE'),
            content_path=NEW.current_workspace_path,
            published_at=COALESCE(NEW.content_published_at,CURRENT_TIMESTAMP),
            preparation_status='READY',preparation_error=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE id=branch_uuid AND repo_id=NEW.id;
    END IF;
    RETURN NEW;
END $$;

CREATE TABLE account_access_tokens (
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    name character varying(80) NOT NULL,
    token_hash character varying(64) NOT NULL,
    token_prefix character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    CONSTRAINT account_access_tokens_check CHECK ((expires_at > created_at))
);

CREATE TABLE accounts (
    id uuid NOT NULL,
    username text NOT NULL,
    display_name text NOT NULL,
    password_hash text NOT NULL,
    account_role text NOT NULL,
    role text GENERATED ALWAYS AS (account_role) STORED,
    enabled boolean DEFAULT true NOT NULL,
    must_change_password boolean DEFAULT true NOT NULL,
    failed_attempts integer DEFAULT 0 NOT NULL,
    locked_until timestamp without time zone,
    temporary_password_expires_at timestamp without time zone,
    last_login_at timestamp without time zone,
    last_login_ip text,
    account_version bigint DEFAULT 1 NOT NULL,
    last_repository_id uuid,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_accounts_version_positive CHECK ((account_version > 0))
);

COMMENT ON TABLE accounts IS '平台账号';

COMMENT ON COLUMN accounts.id IS '账号唯一标识';

COMMENT ON COLUMN accounts.username IS '登录用户名';

COMMENT ON COLUMN accounts.display_name IS '账号显示名称';

COMMENT ON COLUMN accounts.password_hash IS '不可逆密码摘要';

COMMENT ON COLUMN accounts.account_role IS '账号角色：SUPER_ADMIN 或 NORMAL';

COMMENT ON COLUMN accounts.role IS '由 account_role 自动生成的兼容角色字段';

COMMENT ON COLUMN accounts.enabled IS '账号是否启用';

COMMENT ON COLUMN accounts.must_change_password IS '下次登录是否必须修改密码';

COMMENT ON COLUMN accounts.failed_attempts IS '账号累计失败登录次数，用于登录锁定';

COMMENT ON COLUMN accounts.locked_until IS '账号锁定截止时间';

COMMENT ON COLUMN accounts.temporary_password_expires_at IS '临时密码失效时间';

COMMENT ON COLUMN accounts.last_login_at IS '最后成功登录时间';

COMMENT ON COLUMN accounts.last_login_ip IS '最后成功登录来源地址';

COMMENT ON COLUMN accounts.account_version IS '账号资料乐观锁版本';

COMMENT ON COLUMN accounts.last_repository_id IS '用户最后选择的仓库';

COMMENT ON COLUMN accounts.created_at IS '创建时间';

COMMENT ON COLUMN accounts.updated_at IS '最后更新时间';

CREATE TABLE audit_events (
    id uuid NOT NULL,
    actor_account_id uuid,
    target_account_id uuid,
    target_repo_id uuid,
    event_type text NOT NULL,
    result text NOT NULL,
    request_id uuid NOT NULL,
    source_ip text,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone NOT NULL
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

CREATE TABLE branch_context_knowledge (
    context_id uuid NOT NULL,
    card_id uuid NOT NULL,
    revision integer NOT NULL
);

CREATE TABLE branch_preparation_jobs (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    branch_id uuid NOT NULL,
    account_id uuid NOT NULL,
    status text NOT NULL,
    stage text DEFAULT 'QUEUED'::text NOT NULL,
    attempt_token uuid,
    target_commit text,
    kind text DEFAULT 'PREPARE'::text NOT NULL,
    target_content_version uuid,
    error text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT branch_job_kind CHECK ((kind = ANY (ARRAY['SYNC'::text, 'CONTENT'::text, 'GRAPH'::text, 'VECTORS'::text, 'PREPARE'::text]))),
    CONSTRAINT branch_job_target CHECK (((kind = ANY (ARRAY['SYNC'::text, 'PREPARE'::text])) OR (target_content_version IS NOT NULL))),
    CONSTRAINT branch_preparation_jobs_status_check CHECK ((status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'SUCCEEDED'::text, 'FAILED'::text])))
);

CREATE TABLE branch_read_contexts (
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    repo_id uuid NOT NULL,
    branch_id uuid NOT NULL,
    content_version uuid NOT NULL,
    expires_at timestamp with time zone NOT NULL
);

CREATE TABLE chunk_embeddings (
    chunk_id uuid NOT NULL,
    repo_id uuid NOT NULL,
    model character varying(200) NOT NULL,
    dimension integer NOT NULL,
    embedding public.vector NOT NULL,
    content_hash character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    retrieval_capability character varying(32) NOT NULL,
    CONSTRAINT chk_chunk_embeddings_capability CHECK (((retrieval_capability)::text = ANY ((ARRAY['CHARACTER_HASH'::character varying, 'SEMANTIC_EMBEDDING'::character varying])::text[]))),
    CONSTRAINT chunk_embeddings_dimension_check CHECK (((dimension >= 1) AND (dimension <= 4096))),
    CONSTRAINT chunk_embeddings_vector_dimension_check CHECK ((public.vector_dims(embedding) = dimension))
);

COMMENT ON TABLE chunk_embeddings IS '当前项目资产片段的向量表示';

COMMENT ON COLUMN chunk_embeddings.chunk_id IS '对应项目资产片段';

COMMENT ON COLUMN chunk_embeddings.repo_id IS '所属仓库';

COMMENT ON COLUMN chunk_embeddings.model IS '向量模型标识';

COMMENT ON COLUMN chunk_embeddings.dimension IS '向量维度';

COMMENT ON COLUMN chunk_embeddings.embedding IS '检索向量';

COMMENT ON COLUMN chunk_embeddings.content_hash IS '生成向量时的代码正文摘要';

COMMENT ON COLUMN chunk_embeddings.created_at IS '生成或更新时间';

COMMENT ON COLUMN chunk_embeddings.retrieval_capability IS 'CHARACTER_HASH 为字符哈希相似度；SEMANTIC_EMBEDDING 才表示外部模型语义向量';

CREATE TABLE code_chunks (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    content_version uuid NOT NULL,
    commit_sha text NOT NULL,
    file_path text NOT NULL,
    symbol_id text,
    symbol_name text,
    symbol_kind text,
    language text,
    chunk_type text NOT NULL,
    asset_type character varying(24) DEFAULT 'CODE'::character varying NOT NULL,
    start_line integer,
    end_line integer,
    content text NOT NULL,
    content_hash text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    branch_id uuid NOT NULL,
    CONSTRAINT chk_code_chunks_asset_type CHECK (((asset_type)::text = ANY ((ARRAY['CODE'::character varying, 'DOCUMENT'::character varying, 'RULE'::character varying, 'TASK'::character varying, 'CONFIG'::character varying])::text[])))
);

COMMENT ON TABLE code_chunks IS '当前仓库版本切分得到的可检索项目资产片段';

COMMENT ON COLUMN code_chunks.id IS '代码片段唯一标识';

COMMENT ON COLUMN code_chunks.repo_id IS '所属仓库';

COMMENT ON COLUMN code_chunks.content_version IS '生成该片段的内容版本令牌';

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

CREATE TABLE codegraph_artifacts (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    content_version uuid NOT NULL,
    cli_version character varying(40) NOT NULL,
    status character varying(30) NOT NULL,
    artifact_path text NOT NULL,
    node_count integer,
    edge_count integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at timestamp with time zone
);

COMMENT ON TABLE codegraph_artifacts IS '当前代码版本对应的 CodeGraph 分析产物';

COMMENT ON COLUMN codegraph_artifacts.id IS '产物唯一标识';

COMMENT ON COLUMN codegraph_artifacts.repo_id IS '所属仓库';

COMMENT ON COLUMN codegraph_artifacts.content_version IS '生成该产物的内容版本令牌';

COMMENT ON COLUMN codegraph_artifacts.cli_version IS 'CodeGraph CLI 版本';

COMMENT ON COLUMN codegraph_artifacts.status IS '产物状态';

COMMENT ON COLUMN codegraph_artifacts.artifact_path IS '产物存储路径';

COMMENT ON COLUMN codegraph_artifacts.node_count IS '图节点数量';

COMMENT ON COLUMN codegraph_artifacts.edge_count IS '图边数量';

COMMENT ON COLUMN codegraph_artifacts.created_at IS '创建时间';

COMMENT ON COLUMN codegraph_artifacts.published_at IS '发布时间';

CREATE TABLE encrypted_secret_versions (
    id uuid NOT NULL,
    cipher_text text NOT NULL,
    iv text NOT NULL,
    secret_digest character(64) NOT NULL,
    algorithm character varying(40) NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE encrypted_secret_versions IS 'LLM 密钥的加密版本';

COMMENT ON COLUMN encrypted_secret_versions.id IS '密钥版本唯一标识';

COMMENT ON COLUMN encrypted_secret_versions.cipher_text IS '密文';

COMMENT ON COLUMN encrypted_secret_versions.iv IS '加密初始化向量';

COMMENT ON COLUMN encrypted_secret_versions.secret_digest IS '明文指纹摘要';

COMMENT ON COLUMN encrypted_secret_versions.algorithm IS '加密算法';

COMMENT ON COLUMN encrypted_secret_versions.created_by IS '创建账号';

COMMENT ON COLUMN encrypted_secret_versions.created_at IS '创建时间';

CREATE TABLE git_credentials (
    id uuid NOT NULL,
    legacy_repo_id uuid,
    credential_type text NOT NULL,
    display_name text NOT NULL,
    encrypted_secret text NOT NULL,
    masked_value text NOT NULL,
    credential_version bigint DEFAULT 1 NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    last_validated_at timestamp without time zone,
    server_url text NOT NULL,
    username text,
    secret_iv text NOT NULL,
    secret_digest text NOT NULL,
    encryption_algorithm text DEFAULT 'AES-256-GCM'::text NOT NULL,
    last_validation_error text,
    expires_at timestamp with time zone,
    disabled_at timestamp with time zone,
    created_by uuid,
    updated_by uuid,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_git_credentials_status CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'DISABLED'::text, 'INVALID'::text]))),
    CONSTRAINT chk_git_credentials_type CHECK ((credential_type = ANY (ARRAY['GIT_HTTP_TOKEN'::text, 'GITLAB_PAT'::text])))
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

COMMENT ON COLUMN git_credentials.last_validation_error IS '脱敏后的最近检测失败原因';

COMMENT ON COLUMN git_credentials.expires_at IS '可选的凭据过期时间';

COMMENT ON COLUMN git_credentials.created_by IS '创建账号';

COMMENT ON COLUMN git_credentials.created_at IS '创建时间';

COMMENT ON COLUMN git_credentials.updated_at IS '最后更新时间';

CREATE TABLE heuristic_call_edges (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    content_version uuid NOT NULL,
    source_chunk_id uuid,
    target_chunk_id uuid,
    source_symbol text NOT NULL,
    target_symbol text NOT NULL,
    relation character varying(30) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE heuristic_call_edges IS '索引阶段按“符号名+左括号”字符串规则推断的启发式调用候选，不是 CodeGraph CLI 关系';

COMMENT ON COLUMN heuristic_call_edges.id IS '关系边唯一标识';

COMMENT ON COLUMN heuristic_call_edges.repo_id IS '所属仓库';

COMMENT ON COLUMN heuristic_call_edges.content_version IS '生成该关系的内容版本令牌';

COMMENT ON COLUMN heuristic_call_edges.source_chunk_id IS '起点代码片段';

COMMENT ON COLUMN heuristic_call_edges.target_chunk_id IS '终点代码片段';

COMMENT ON COLUMN heuristic_call_edges.source_symbol IS '起点符号';

COMMENT ON COLUMN heuristic_call_edges.target_symbol IS '终点符号';

COMMENT ON COLUMN heuristic_call_edges.relation IS '关系类型';

COMMENT ON COLUMN heuristic_call_edges.created_at IS '生成时间';

CREATE TABLE index_job_branch_targets (
    job_id uuid NOT NULL,
    repo_id uuid NOT NULL,
    branch_id uuid NOT NULL,
    content_version uuid NOT NULL
);

CREATE TABLE index_jobs (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    job_type text NOT NULL,
    status text NOT NULL,
    current_step text,
    error_message text,
    started_at timestamp without time zone,
    finished_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    heartbeat_at timestamp with time zone,
    timeout_at timestamp with time zone,
    failure_code character varying(64),
    execution_mode character varying(20),
    fallback_reason character varying(64),
    CONSTRAINT chk_index_jobs_execution_mode CHECK (((execution_mode IS NULL) OR ((execution_mode)::text = ANY ((ARRAY['FULL'::character varying, 'INCREMENTAL'::character varying])::text[]))))
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

COMMENT ON COLUMN index_jobs.heartbeat_at IS 'Worker 最近一次存活心跳；只表示进程仍在处理';

COMMENT ON COLUMN index_jobs.timeout_at IS '任务固定超时截止时间，心跳不会无限延长该截止时间';

COMMENT ON COLUMN index_jobs.failure_code IS '稳定失败代码，例如 CODEGRAPH_TIMEOUT、CODEGRAPH_BUILD_FAILED';

COMMENT ON COLUMN index_jobs.execution_mode IS '实际执行模式；可能与请求的 job_type 不同';

COMMENT ON COLUMN index_jobs.fallback_reason IS '增量请求回退全量的稳定原因代码';

CREATE TABLE knowledge_attachments (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    original_name character varying(255) NOT NULL,
    media_type character varying(120) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character(64) NOT NULL,
    storage_path text NOT NULL,
    uploaded_by uuid,
    scan_status character varying(20) DEFAULT 'READY'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT knowledge_attachments_size_bytes_check CHECK ((size_bytes > 0))
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

CREATE TABLE knowledge_branch_validations (
    card_id uuid NOT NULL,
    revision integer NOT NULL,
    branch_id uuid NOT NULL,
    content_version uuid NOT NULL,
    state text NOT NULL,
    note text DEFAULT ''::text NOT NULL,
    checked_by uuid,
    checked_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT knowledge_branch_validations_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'UNVERIFIED'::text, 'REVIEW_REQUIRED'::text, 'INVALID'::text])))
);

CREATE TABLE knowledge_card_attachment_refs (
    card_id uuid NOT NULL,
    revision integer NOT NULL,
    attachment_id uuid NOT NULL,
    "position" integer DEFAULT 0 NOT NULL
);

COMMENT ON TABLE knowledge_card_attachment_refs IS '知识修订与附件的关联';

COMMENT ON COLUMN knowledge_card_attachment_refs.card_id IS '知识卡片标识';

COMMENT ON COLUMN knowledge_card_attachment_refs.revision IS '知识修订号';

COMMENT ON COLUMN knowledge_card_attachment_refs.attachment_id IS '附件标识';

COMMENT ON COLUMN knowledge_card_attachment_refs."position" IS '附件显示顺序';

CREATE TABLE knowledge_card_embeddings (
    card_id uuid NOT NULL,
    repo_id uuid NOT NULL,
    revision integer NOT NULL,
    model character varying(200) DEFAULT 'local-hash-64'::character varying NOT NULL,
    dimension integer NOT NULL,
    embedding public.vector NOT NULL,
    content_hash character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    retrieval_capability character varying(32) NOT NULL,
    CONSTRAINT chk_knowledge_embeddings_capability CHECK (((retrieval_capability)::text = ANY ((ARRAY['CHARACTER_HASH'::character varying, 'SEMANTIC_EMBEDDING'::character varying])::text[]))),
    CONSTRAINT knowledge_embeddings_dimension_check CHECK (((dimension >= 1) AND (dimension <= 4096))),
    CONSTRAINT knowledge_embeddings_vector_dimension_check CHECK ((public.vector_dims(embedding) = dimension))
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

COMMENT ON COLUMN knowledge_card_embeddings.retrieval_capability IS 'CHARACTER_HASH 为字符哈希相似度；SEMANTIC_EMBEDDING 才表示外部模型语义向量';

CREATE TABLE knowledge_card_markdown_source_links (
    card_id uuid NOT NULL,
    revision integer NOT NULL,
    source_id uuid,
    repo_id uuid NOT NULL,
    source_content_version uuid NOT NULL,
    source_path text NOT NULL,
    source_content_hash character(64) NOT NULL,
    generated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    source_branch_id uuid NOT NULL,
    CONSTRAINT chk_knowledge_markdown_link_hash CHECK ((source_content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT chk_knowledge_markdown_link_path CHECK (((btrim(source_path) <> ''::text) AND (source_path !~ '(^|/)\.\.(/|$)'::text)))
);

COMMENT ON TABLE knowledge_card_markdown_source_links IS '知识卡片修订与生成时 Markdown 精确版本的来源凭据';

COMMENT ON COLUMN knowledge_card_markdown_source_links.card_id IS '生成或同步得到的知识卡片';

COMMENT ON COLUMN knowledge_card_markdown_source_links.revision IS '对应知识卡片修订号';

COMMENT ON COLUMN knowledge_card_markdown_source_links.source_id IS '当前来源行；来源删除后允许为空';

COMMENT ON COLUMN knowledge_card_markdown_source_links.source_content_version IS '生成时仓库内容版本令牌';

COMMENT ON COLUMN knowledge_card_markdown_source_links.source_path IS '生成时 Markdown 相对路径';

COMMENT ON COLUMN knowledge_card_markdown_source_links.source_content_hash IS '生成时完整 Markdown 原文 SHA-256';

COMMENT ON COLUMN knowledge_card_markdown_source_links.source_branch_id IS '生成知识修订时 Markdown 来源所属分支';

CREATE TABLE knowledge_card_revisions (
    card_id uuid NOT NULL,
    revision integer NOT NULL,
    repo_id uuid NOT NULL,
    title text NOT NULL,
    card_type character varying(40) NOT NULL,
    content text NOT NULL,
    tags text[] DEFAULT '{}'::text[] NOT NULL,
    publication_status character varying(30) NOT NULL,
    changed_by uuid,
    changed_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    knowledge_kind character varying(40) DEFAULT 'REFERENCE'::character varying NOT NULL,
    severity character varying(20) DEFAULT 'INFO'::character varying NOT NULL,
    enforcement character varying(20) DEFAULT 'REFERENCE'::character varying NOT NULL,
    owner_account_id uuid,
    scope_payload jsonb DEFAULT '{"modules": [], "symbols": [], "pathPatterns": []}'::jsonb NOT NULL,
    obligations_payload jsonb DEFAULT '{"instructions": [], "requiredTests": [], "prohibitedPathPatterns": [], "knowledgeUpdateRequired": false, "requiredApproverAccountIds": []}'::jsonb NOT NULL,
    last_verified_content_version uuid,
    verification_note text,
    branch_id uuid NOT NULL,
    CONSTRAINT chk_knowledge_revision_enforcement CHECK (((enforcement)::text = ANY ((ARRAY['REFERENCE'::character varying, 'ADVISORY'::character varying, 'REQUIRED'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_revision_kind CHECK (((knowledge_kind)::text = ANY ((ARRAY['REFERENCE'::character varying, 'BUSINESS_RULE'::character varying, 'ARCH_DECISION'::character varying, 'API_CONTRACT'::character varying, 'DATA_CONSTRAINT'::character varying, 'TEST_OBLIGATION'::character varying, 'SECURITY_POLICY'::character varying, 'RUNBOOK'::character varying, 'INCIDENT_LESSON'::character varying, 'OWNERSHIP'::character varying, 'TECH_DEBT'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_revision_obligations_payload CHECK (((jsonb_typeof(obligations_payload) = 'object'::text) AND (jsonb_typeof((obligations_payload -> 'requiredTests'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'requiredApproverAccountIds'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'instructions'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'prohibitedPathPatterns'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'knowledgeUpdateRequired'::text)) = 'boolean'::text))),
    CONSTRAINT chk_knowledge_revision_scope_payload CHECK (((jsonb_typeof(scope_payload) = 'object'::text) AND (jsonb_typeof((scope_payload -> 'pathPatterns'::text)) = 'array'::text) AND (jsonb_typeof((scope_payload -> 'symbols'::text)) = 'array'::text) AND (jsonb_typeof((scope_payload -> 'modules'::text)) = 'array'::text) AND (NOT (scope_payload ?| ARRAY['repositoryIds'::text, 'serviceNames'::text, 'contractIds'::text])))),
    CONSTRAINT chk_knowledge_revision_severity CHECK (((severity)::text = ANY ((ARRAY['INFO'::character varying, 'WARNING'::character varying, 'CRITICAL'::character varying])::text[])))
);

COMMENT ON TABLE knowledge_card_revisions IS '知识卡片修订历史，只保存知识历史';

COMMENT ON COLUMN knowledge_card_revisions.card_id IS '知识卡片标识';

COMMENT ON COLUMN knowledge_card_revisions.revision IS '修订号';

COMMENT ON COLUMN knowledge_card_revisions.repo_id IS '所属仓库';

COMMENT ON COLUMN knowledge_card_revisions.title IS '该修订标题';

COMMENT ON COLUMN knowledge_card_revisions.card_type IS '该修订知识类型';

COMMENT ON COLUMN knowledge_card_revisions.content IS '该修订正文';

COMMENT ON COLUMN knowledge_card_revisions.tags IS '该修订标签';

COMMENT ON COLUMN knowledge_card_revisions.publication_status IS '该历史修订保存时的发布状态';

COMMENT ON COLUMN knowledge_card_revisions.changed_by IS '修改账号';

COMMENT ON COLUMN knowledge_card_revisions.changed_at IS '修改时间';

COMMENT ON COLUMN knowledge_card_revisions.scope_payload IS '知识修订中的路径、符号和模块适用范围';

CREATE TABLE knowledge_cards (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    title text NOT NULL,
    card_type character varying(40) DEFAULT '模块说明'::character varying NOT NULL,
    content text NOT NULL,
    tags text[] DEFAULT '{}'::text[] NOT NULL,
    publication_status text NOT NULL,
    revision integer DEFAULT 1 NOT NULL,
    created_by uuid,
    updated_by uuid,
    verified_commit text,
    source_version_status character varying(30) DEFAULT 'UNVERIFIED'::character varying NOT NULL,
    source_version_checked_at timestamp with time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    review_status character varying(32) DEFAULT 'UNREVIEWED'::character varying NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    knowledge_kind character varying(40) DEFAULT 'REFERENCE'::character varying NOT NULL,
    severity character varying(20) DEFAULT 'INFO'::character varying NOT NULL,
    enforcement character varying(20) DEFAULT 'REFERENCE'::character varying NOT NULL,
    owner_account_id uuid,
    scope_payload jsonb DEFAULT '{"modules": [], "symbols": [], "pathPatterns": []}'::jsonb NOT NULL,
    obligations_payload jsonb DEFAULT '{"instructions": [], "requiredTests": [], "prohibitedPathPatterns": [], "knowledgeUpdateRequired": false, "requiredApproverAccountIds": []}'::jsonb NOT NULL,
    last_verified_content_version uuid,
    verification_note text,
    branch_id uuid NOT NULL,
    CONSTRAINT chk_knowledge_enforcement CHECK (((enforcement)::text = ANY ((ARRAY['REFERENCE'::character varying, 'ADVISORY'::character varying, 'REQUIRED'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_kind CHECK (((knowledge_kind)::text = ANY ((ARRAY['REFERENCE'::character varying, 'BUSINESS_RULE'::character varying, 'ARCH_DECISION'::character varying, 'API_CONTRACT'::character varying, 'DATA_CONSTRAINT'::character varying, 'TEST_OBLIGATION'::character varying, 'SECURITY_POLICY'::character varying, 'RUNBOOK'::character varying, 'INCIDENT_LESSON'::character varying, 'OWNERSHIP'::character varying, 'TECH_DEBT'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_obligations_payload CHECK (((jsonb_typeof(obligations_payload) = 'object'::text) AND (jsonb_typeof((obligations_payload -> 'requiredTests'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'requiredApproverAccountIds'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'instructions'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'prohibitedPathPatterns'::text)) = 'array'::text) AND (jsonb_typeof((obligations_payload -> 'knowledgeUpdateRequired'::text)) = 'boolean'::text))),
    CONSTRAINT chk_knowledge_publication_status CHECK ((publication_status = ANY (ARRAY['DRAFT'::text, 'PUBLISHED'::text, 'ARCHIVED'::text]))),
    CONSTRAINT chk_knowledge_review_status CHECK (((review_status)::text = ANY ((ARRAY['UNREVIEWED'::character varying, 'APPROVED'::character varying, 'CHANGES_REQUESTED'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_scope_payload CHECK (((jsonb_typeof(scope_payload) = 'object'::text) AND (jsonb_typeof((scope_payload -> 'pathPatterns'::text)) = 'array'::text) AND (jsonb_typeof((scope_payload -> 'symbols'::text)) = 'array'::text) AND (jsonb_typeof((scope_payload -> 'modules'::text)) = 'array'::text) AND (NOT (scope_payload ?| ARRAY['repositoryIds'::text, 'serviceNames'::text, 'contractIds'::text])))),
    CONSTRAINT chk_knowledge_severity CHECK (((severity)::text = ANY ((ARRAY['INFO'::character varying, 'WARNING'::character varying, 'CRITICAL'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_source_version_status CHECK (((source_version_status)::text = ANY ((ARRAY['UNVERIFIED'::character varying, 'CURRENT'::character varying, 'SUSPECT'::character varying, 'STALE'::character varying])::text[])))
);

COMMENT ON TABLE knowledge_cards IS '仓库知识卡片及其当前内容';

COMMENT ON COLUMN knowledge_cards.id IS '知识卡片唯一标识';

COMMENT ON COLUMN knowledge_cards.repo_id IS '所属仓库';

COMMENT ON COLUMN knowledge_cards.title IS '知识标题';

COMMENT ON COLUMN knowledge_cards.card_type IS '知识类型';

COMMENT ON COLUMN knowledge_cards.content IS 'Markdown 知识正文';

COMMENT ON COLUMN knowledge_cards.tags IS '知识标签';

COMMENT ON COLUMN knowledge_cards.publication_status IS '发布状态：草稿、已发布或已归档';

COMMENT ON COLUMN knowledge_cards.revision IS '当前修订号';

COMMENT ON COLUMN knowledge_cards.created_by IS '创建账号';

COMMENT ON COLUMN knowledge_cards.updated_by IS '最后更新账号';

COMMENT ON COLUMN knowledge_cards.verified_commit IS '知识内容最后一次人工确认时对应的代码提交号';

COMMENT ON COLUMN knowledge_cards.source_version_status IS '来源版本状态：未验证、当前或已过期；不表示人工认可内容';

COMMENT ON COLUMN knowledge_cards.source_version_checked_at IS '最近一次自动核对来源版本的时间';

COMMENT ON COLUMN knowledge_cards.created_at IS '创建时间';

COMMENT ON COLUMN knowledge_cards.updated_at IS '最后更新时间';

COMMENT ON COLUMN knowledge_cards.review_status IS '人工评审状态，与来源版本及发布状态独立';

COMMENT ON COLUMN knowledge_cards.reviewed_by IS '最近一次人工评审账号';

COMMENT ON COLUMN knowledge_cards.reviewed_at IS '最近一次人工评审时间';

COMMENT ON COLUMN knowledge_cards.knowledge_kind IS '可参与开发检查的工程知识类型';

COMMENT ON COLUMN knowledge_cards.severity IS '知识不满足时的业务严重程度';

COMMENT ON COLUMN knowledge_cards.enforcement IS '参考、建议或必须执行';

COMMENT ON COLUMN knowledge_cards.owner_account_id IS '工程知识负责人';

COMMENT ON COLUMN knowledge_cards.scope_payload IS '项目代码中的路径、符号和模块适用范围';

COMMENT ON COLUMN knowledge_cards.obligations_payload IS '命中知识后要求的测试、审批、禁止路径、知识同步和补充开发动作';

COMMENT ON COLUMN knowledge_cards.last_verified_content_version IS '最近完成人工或代码证据验证的仓库内容版本';

COMMENT ON COLUMN knowledge_cards.verification_note IS '最近验证说明';

CREATE TABLE knowledge_code_refs (
    card_id uuid NOT NULL,
    revision integer NOT NULL,
    "position" integer NOT NULL,
    repo_id uuid NOT NULL,
    content_version uuid,
    chunk_id uuid,
    file_path text NOT NULL,
    symbol_name text,
    start_line integer,
    end_line integer,
    content_hash character varying(64) NOT NULL
);

COMMENT ON TABLE knowledge_code_refs IS '知识修订与代码证据的结构化关联';

COMMENT ON COLUMN knowledge_code_refs.card_id IS '知识卡片标识';

COMMENT ON COLUMN knowledge_code_refs.revision IS '知识修订号';

COMMENT ON COLUMN knowledge_code_refs."position" IS '关联代码的显示顺序';

COMMENT ON COLUMN knowledge_code_refs.repo_id IS '所属仓库';

COMMENT ON COLUMN knowledge_code_refs.content_version IS '关联代码的内容版本令牌';

COMMENT ON COLUMN knowledge_code_refs.chunk_id IS '关联代码片段；片段删除后可为空';

COMMENT ON COLUMN knowledge_code_refs.file_path IS '关联文件路径';

COMMENT ON COLUMN knowledge_code_refs.symbol_name IS '关联符号名称';

COMMENT ON COLUMN knowledge_code_refs.start_line IS '关联代码起始行';

COMMENT ON COLUMN knowledge_code_refs.end_line IS '关联代码结束行';

COMMENT ON COLUMN knowledge_code_refs.content_hash IS '关联时的代码内容摘要';

CREATE TABLE knowledge_drift_events (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    card_id uuid NOT NULL,
    card_revision integer NOT NULL,
    from_content_version uuid,
    to_content_version uuid NOT NULL,
    from_commit character varying(128),
    to_commit character varying(128),
    previous_status character varying(32) NOT NULL,
    result_status character varying(32) NOT NULL,
    trigger_type character varying(40) NOT NULL,
    reasons_payload jsonb DEFAULT '[]'::jsonb NOT NULL,
    note text,
    actor_id uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_knowledge_drift_previous_status CHECK (((previous_status)::text = ANY ((ARRAY['UNVERIFIED'::character varying, 'CURRENT'::character varying, 'SUSPECT'::character varying, 'STALE'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_drift_reasons_payload CHECK ((jsonb_typeof(reasons_payload) = 'array'::text)),
    CONSTRAINT chk_knowledge_drift_result_status CHECK (((result_status)::text = ANY ((ARRAY['CURRENT'::character varying, 'SUSPECT'::character varying, 'STALE'::character varying])::text[]))),
    CONSTRAINT chk_knowledge_drift_trigger_type CHECK (((trigger_type)::text = ANY ((ARRAY['AUTOMATIC_DIFF'::character varying, 'MANUAL_CONFIRM_CURRENT'::character varying, 'MANUAL_MARK_STALE'::character varying])::text[]))),
    CONSTRAINT knowledge_drift_events_card_revision_check CHECK ((card_revision > 0))
);

COMMENT ON TABLE knowledge_drift_events IS '知识来源版本的自动漂移与人工复核审计';

COMMENT ON COLUMN knowledge_drift_events.reasons_payload IS '触发状态变化的结构化 Git、代码引用或符号证据';

CREATE TABLE llm_connectivity_checks (
    id uuid NOT NULL,
    actor_id uuid,
    config_id uuid,
    fingerprint character(64) NOT NULL,
    endpoint_host character varying(255) NOT NULL,
    model character varying(200) NOT NULL,
    status character varying(30) NOT NULL,
    availability character varying(30) DEFAULT 'UNTESTED'::character varying NOT NULL,
    current_stage character varying(50),
    stage_results jsonb DEFAULT '[]'::jsonb NOT NULL,
    error_code character varying(80),
    error_summary character varying(500),
    total_duration_ms bigint,
    connect_duration_ms bigint,
    first_token_duration_ms bigint,
    request_id uuid NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_llm_check_availability CHECK (((availability)::text = ANY ((ARRAY['UNTESTED'::character varying, 'AVAILABLE'::character varying, 'DEGRADED'::character varying, 'UNAVAILABLE'::character varying])::text[]))),
    CONSTRAINT chk_llm_check_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'CANCELED'::character varying])::text[])))
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

CREATE SEQUENCE llm_provider_config_version_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

COMMENT ON SEQUENCE llm_provider_config_version_seq IS 'LLM Provider 配置递增版本号';

CREATE TABLE llm_provider_configs (
    id uuid NOT NULL,
    config_version bigint NOT NULL,
    name character varying(100) NOT NULL,
    provider_type character varying(40) NOT NULL,
    base_url text NOT NULL,
    model character varying(200) NOT NULL,
    connect_timeout_ms integer NOT NULL,
    request_timeout_ms integer NOT NULL,
    max_output_tokens integer NOT NULL,
    temperature double precision NOT NULL,
    streaming_enabled boolean NOT NULL,
    secret_version_id uuid,
    fingerprint character(64) NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_llm_connect_timeout CHECK (((connect_timeout_ms >= 1000) AND (connect_timeout_ms <= 10000))),
    CONSTRAINT chk_llm_max_output_tokens CHECK (((max_output_tokens >= 1) AND (max_output_tokens <= 32768))),
    CONSTRAINT chk_llm_provider_type CHECK (((provider_type)::text = 'OPENAI_COMPATIBLE'::text)),
    CONSTRAINT chk_llm_request_timeout CHECK (((request_timeout_ms >= 3000) AND (request_timeout_ms <= 120000))),
    CONSTRAINT chk_llm_temperature CHECK (((temperature >= (0)::double precision) AND (temperature <= (2)::double precision)))
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

CREATE TABLE llm_provider_runtime_states (
    config_id uuid NOT NULL,
    availability character varying(30) DEFAULT 'UNTESTED'::character varying NOT NULL,
    latest_check_id uuid,
    last_success_at timestamp with time zone,
    last_failure_at timestamp with time zone,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    breaker_state character varying(20) DEFAULT 'CLOSED'::character varying NOT NULL,
    breaker_opened_at timestamp with time zone,
    last_error_code character varying(80),
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_llm_availability CHECK (((availability)::text = ANY ((ARRAY['UNTESTED'::character varying, 'AVAILABLE'::character varying, 'DEGRADED'::character varying, 'UNAVAILABLE'::character varying])::text[]))),
    CONSTRAINT chk_llm_breaker CHECK (((breaker_state)::text = ANY ((ARRAY['CLOSED'::character varying, 'OPEN'::character varying])::text[])))
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

CREATE TABLE login_captcha_challenges (
    id uuid NOT NULL,
    username_normalized text NOT NULL,
    answer_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE login_captcha_challenges IS '登录验证码挑战';

COMMENT ON COLUMN login_captcha_challenges.id IS '验证码唯一标识';

COMMENT ON COLUMN login_captcha_challenges.username_normalized IS '规范化登录用户名';

COMMENT ON COLUMN login_captcha_challenges.answer_hash IS '验证码答案摘要';

COMMENT ON COLUMN login_captcha_challenges.expires_at IS '验证码失效时间';

COMMENT ON COLUMN login_captcha_challenges.used_at IS '验证码消费时间';

COMMENT ON COLUMN login_captcha_challenges.created_at IS '创建时间';

CREATE TABLE login_failure_counters (
    username_normalized text NOT NULL,
    failure_count integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE login_failure_counters IS '持久化登录失败计数';

COMMENT ON COLUMN login_failure_counters.username_normalized IS '规范化登录用户名';

COMMENT ON COLUMN login_failure_counters.failure_count IS '连续失败次数';

COMMENT ON COLUMN login_failure_counters.updated_at IS '最后失败或重置时间';

CREATE TABLE login_sessions (
    token_hash text NOT NULL,
    account_id uuid NOT NULL,
    csrf_token text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    last_seen_at timestamp without time zone NOT NULL,
    expires_at timestamp without time zone NOT NULL
);

COMMENT ON TABLE login_sessions IS '登录会话';

COMMENT ON COLUMN login_sessions.token_hash IS '会话令牌摘要';

COMMENT ON COLUMN login_sessions.account_id IS '所属账号';

COMMENT ON COLUMN login_sessions.csrf_token IS '修改请求使用的 CSRF 令牌';

COMMENT ON COLUMN login_sessions.created_at IS '会话创建时间';

COMMENT ON COLUMN login_sessions.last_seen_at IS '最近活动时间';

COMMENT ON COLUMN login_sessions.expires_at IS '绝对失效时间';

CREATE TABLE qa_citations (
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    repository_id uuid,
    source_type character varying(20) DEFAULT 'CODE'::character varying NOT NULL,
    chunk_id uuid,
    knowledge_card_id uuid,
    title text,
    file_path text NOT NULL,
    symbol_name text,
    start_line integer,
    end_line integer,
    evidence_hash character varying(64) NOT NULL,
    rank integer NOT NULL,
    citation_payload jsonb NOT NULL
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

COMMENT ON COLUMN qa_citations.citation_payload IS '可原样恢复的完整引用内容版本';

CREATE TABLE qa_conversations (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    account_id uuid NOT NULL,
    client_request_id uuid,
    title character varying(80) NOT NULL,
    question text NOT NULL,
    answer text NOT NULL,
    content_version uuid,
    provider character varying(160) NOT NULL,
    evidence_status character varying(32) NOT NULL,
    fallback_reason character varying(64),
    answer_payload jsonb NOT NULL,
    thread_id uuid DEFAULT gen_random_uuid() NOT NULL,
    turn_no integer DEFAULT 1 NOT NULL,
    status character varying(20) DEFAULT 'COMPLETED'::character varying NOT NULL,
    started_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    branch_id uuid,
    context_id uuid,
    branch_name text,
    commit_sha text,
    CONSTRAINT chk_qa_conversations_evidence_status CHECK (((evidence_status)::text = ANY ((ARRAY['CITATION_COMPLETE'::character varying, 'CITATION_INCOMPLETE'::character varying, 'SUPPORTED'::character varying, 'DEGRADED'::character varying, 'MODEL_OUTPUT_REJECTED'::character varying, 'INSUFFICIENT'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT chk_qa_conversations_status CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying, 'STOPPED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT chk_qa_conversations_turn_no CHECK ((turn_no > 0))
);

COMMENT ON TABLE qa_conversations IS '多轮知识问答线程中的单轮记录';

COMMENT ON COLUMN qa_conversations.id IS '问答唯一标识';

COMMENT ON COLUMN qa_conversations.repo_id IS '所属仓库';

COMMENT ON COLUMN qa_conversations.account_id IS '提问账号';

COMMENT ON COLUMN qa_conversations.client_request_id IS '客户端幂等请求标识';

COMMENT ON COLUMN qa_conversations.title IS '历史记录标题';

COMMENT ON COLUMN qa_conversations.question IS '用户问题';

COMMENT ON COLUMN qa_conversations.answer IS '生成的回答';

COMMENT ON COLUMN qa_conversations.content_version IS '回答所依据的内容版本令牌';

COMMENT ON COLUMN qa_conversations.provider IS '回答提供方';

COMMENT ON COLUMN qa_conversations.evidence_status IS '回答证据状态；CITATION_* 仅表示引用编号与段落覆盖，不表示语义蕴含已验证';

COMMENT ON COLUMN qa_conversations.fallback_reason IS '未使用模型回答或安全降级的原因';

COMMENT ON COLUMN qa_conversations.answer_payload IS '可原样恢复的完整回答内容版本';

COMMENT ON COLUMN qa_conversations.thread_id IS '多轮问答线程标识；首轮记录通常以自身 ID 作为线程标识';

COMMENT ON COLUMN qa_conversations.turn_no IS '当前记录在线程内的轮次，从 1 开始';

COMMENT ON COLUMN qa_conversations.status IS '生成状态：RUNNING、COMPLETED、STOPPED 或 FAILED';

COMMENT ON COLUMN qa_conversations.started_at IS '当前轮次开始生成的时间';

COMMENT ON COLUMN qa_conversations.finished_at IS '当前轮次完成、停止或失败的时间';

COMMENT ON COLUMN qa_conversations.created_at IS '创建时间';

COMMENT ON COLUMN qa_conversations.updated_at IS '标题或内容最后更新时间';

COMMENT ON COLUMN qa_conversations.branch_id IS '问答所属分支；旧的默认版本问答允许为空';

CREATE TABLE repositories (
    id uuid NOT NULL,
    name text NOT NULL,
    normalized_name text NOT NULL,
    description character varying(500) DEFAULT ''::character varying NOT NULL,
    path text NOT NULL,
    source_type text DEFAULT 'LOCAL_GIT'::text NOT NULL,
    default_branch text,
    remote_url text,
    current_commit text,
    worktree_digest text,
    worktree_dirty boolean DEFAULT false NOT NULL,
    current_content_version uuid,
    current_workspace_path text,
    content_published_at timestamp without time zone,
    codegraph_path text,
    last_scanned_at timestamp without time zone,
    owner_account_id uuid NOT NULL,
    ownership_version bigint DEFAULT 0 NOT NULL,
    repository_status text DEFAULT 'READY'::text NOT NULL,
    repository_version bigint DEFAULT 1 NOT NULL,
    deleted_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_repositories_version_positive CHECK ((repository_version > 0))
);

COMMENT ON TABLE repositories IS '项目及代码来源容器；各分支当前代码由 repository_branches 管理';

COMMENT ON COLUMN repositories.id IS '仓库唯一标识';

COMMENT ON COLUMN repositories.name IS '仓库显示名称';

COMMENT ON COLUMN repositories.normalized_name IS '同一所有者下用于唯一性判断的规范化名称';

COMMENT ON COLUMN repositories.description IS '仓库说明';

COMMENT ON COLUMN repositories.path IS '源代码目录或平台受管 Git 工作目录';

COMMENT ON COLUMN repositories.source_type IS '来源类型：LOCAL_GIT、REMOTE_GIT、GITLAB 或 ZIP';

COMMENT ON COLUMN repositories.default_branch IS 'Initial branch reading preference; sync and indexes belong to individual branches';

COMMENT ON COLUMN repositories.remote_url IS '远程 Git/GitLab HTTPS 克隆地址';

COMMENT ON COLUMN repositories.current_commit IS '当前已发布代码版本的 Git 提交号';

COMMENT ON COLUMN repositories.worktree_digest IS '当前代码文件清单及内容摘要';

COMMENT ON COLUMN repositories.worktree_dirty IS '最近同步时源工作区是否包含未提交变化';

COMMENT ON COLUMN repositories.current_content_version IS '当前内容版本令牌，用于保证代码及派生数据版本一致';

COMMENT ON COLUMN repositories.current_workspace_path IS '当前已发布代码的只读受管目录';

COMMENT ON COLUMN repositories.content_published_at IS '当前代码版本发布时间';

COMMENT ON COLUMN repositories.codegraph_path IS '当前 CodeGraph 产物路径';

COMMENT ON COLUMN repositories.last_scanned_at IS '最近一次检查源代码变化的时间';

COMMENT ON COLUMN repositories.owner_account_id IS '仓库所有者账号';

COMMENT ON COLUMN repositories.ownership_version IS '所有权并发控制版本';

COMMENT ON COLUMN repositories.repository_status IS '仓库生命周期状态';

COMMENT ON COLUMN repositories.repository_version IS '仓库资料乐观锁版本';

COMMENT ON COLUMN repositories.deleted_at IS '逻辑删除时间';

COMMENT ON COLUMN repositories.created_at IS '创建时间';

COMMENT ON COLUMN repositories.updated_at IS '最后更新时间';

CREATE TABLE repository_branches (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    name text NOT NULL,
    content_version uuid,
    previous_content_version uuid,
    commit_sha text,
    content_path text,
    published_at timestamp with time zone,
    generation bigint DEFAULT 0 NOT NULL,
    preparation_status text DEFAULT 'PENDING'::text NOT NULL,
    preparation_error text,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tracking_status text DEFAULT 'ACTIVE'::text NOT NULL,
    archived_at timestamp with time zone,
    archived_by uuid,
    last_synced_at timestamp with time zone,
    content_indexed_at timestamp with time zone,
    CONSTRAINT repository_branches_preparation_status_check CHECK ((preparation_status = ANY (ARRAY['PENDING'::text, 'BUILDING'::text, 'READY'::text, 'FAILED'::text]))),
    CONSTRAINT repository_branches_tracking_status_check CHECK ((tracking_status = ANY (ARRAY['ACTIVE'::text, 'ARCHIVED'::text])))
);

COMMENT ON COLUMN repository_branches.content_version IS '当前代码发布代次，只用于并发隔离和派生产物一致性';

COMMENT ON COLUMN repository_branches.tracking_status IS '受管分支生命周期；归档只停止新上下文和新任务，不删除历史证据';

COMMENT ON COLUMN repository_branches.content_indexed_at IS '当前分支内容索引完成时间';

CREATE TABLE repository_credential_bindings (
    repository_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    usage_type text DEFAULT 'CLONE'::text NOT NULL,
    bound_by uuid,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_repository_credential_usage CHECK ((usage_type = 'CLONE'::text))
);

COMMENT ON TABLE repository_credential_bindings IS '仓库与可复用凭据的用途绑定';

CREATE TABLE repository_deletion_tombstones (
    repository_id uuid NOT NULL,
    deleted_by uuid,
    deleted_at timestamp without time zone NOT NULL,
    cleanup_status text DEFAULT 'PENDING'::text NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    last_error_code text,
    cleanup_updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE repository_deletion_tombstones IS '仓库逻辑删除后的物理清理任务';

COMMENT ON COLUMN repository_deletion_tombstones.repository_id IS '已删除仓库标识';

COMMENT ON COLUMN repository_deletion_tombstones.deleted_by IS '执行删除的账号';

COMMENT ON COLUMN repository_deletion_tombstones.deleted_at IS '删除请求时间';

COMMENT ON COLUMN repository_deletion_tombstones.cleanup_status IS '派生数据清理状态';

COMMENT ON COLUMN repository_deletion_tombstones.retry_count IS '清理重试次数';

COMMENT ON COLUMN repository_deletion_tombstones.last_error_code IS '最近清理错误编码';

COMMENT ON COLUMN repository_deletion_tombstones.cleanup_updated_at IS '清理状态更新时间';

CREATE TABLE repository_import_jobs (
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    credential_id uuid,
    source_type text NOT NULL,
    repository_name text NOT NULL,
    remote_url text NOT NULL,
    branch text,
    status text DEFAULT 'QUEUED'::text NOT NULL,
    current_step text DEFAULT 'queued'::text NOT NULL,
    error_message text,
    result_repository_id uuid,
    cancel_requested boolean DEFAULT false NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    project_draft_id uuid,
    CONSTRAINT chk_repository_import_status CHECK ((status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'CANCELED'::text])))
);

COMMENT ON TABLE repository_import_jobs IS '远程仓库异步导入任务';

CREATE TABLE repository_markdown_sources (
    id uuid NOT NULL,
    repo_id uuid NOT NULL,
    content_version uuid NOT NULL,
    file_path text NOT NULL,
    content_hash character(64) NOT NULL,
    title character varying(200) NOT NULL,
    asset_type character varying(24) NOT NULL,
    content text NOT NULL,
    line_count integer NOT NULL,
    byte_size bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    branch_id uuid NOT NULL,
    CONSTRAINT chk_repository_markdown_source_asset_type CHECK (((asset_type)::text = ANY ((ARRAY['DOCUMENT'::character varying, 'RULE'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_repository_markdown_source_byte_size CHECK ((byte_size > 0)),
    CONSTRAINT chk_repository_markdown_source_hash CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT chk_repository_markdown_source_line_count CHECK ((line_count > 0)),
    CONSTRAINT chk_repository_markdown_source_path CHECK (((btrim(file_path) <> ''::text) AND (file_path !~ '(^|/)\.\.(/|$)'::text)))
);

COMMENT ON TABLE repository_markdown_sources IS '当前仓库内容版本中可生成知识卡片的 Markdown 来源';

COMMENT ON COLUMN repository_markdown_sources.id IS '稳定来源标识，同一仓库相对路径保持不变';

COMMENT ON COLUMN repository_markdown_sources.repo_id IS '所属仓库';

COMMENT ON COLUMN repository_markdown_sources.content_version IS '最近发现该 Markdown 的内容版本令牌';

COMMENT ON COLUMN repository_markdown_sources.file_path IS '仓库内规范化 Markdown 相对路径';

COMMENT ON COLUMN repository_markdown_sources.content_hash IS '完整 UTF-8 Markdown 原文的 SHA-256';

COMMENT ON COLUMN repository_markdown_sources.content IS '用于生成知识卡片的完整 Markdown 原文';

COMMENT ON COLUMN repository_markdown_sources.branch_id IS 'Markdown 来源所属的稳定分支身份，同路径跨分支互不覆盖';

CREATE TABLE repository_permissions (
    account_id uuid NOT NULL,
    repo_id uuid NOT NULL,
    permission_level text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    CONSTRAINT repository_permissions_permission_level_check CHECK ((permission_level = ANY (ARRAY['READ'::text, 'MAINTAIN'::text, 'MANAGE'::text])))
);

COMMENT ON TABLE repository_permissions IS '仓库成员权限，不包含由 owner_account_id 表达的 OWNER';

COMMENT ON COLUMN repository_permissions.account_id IS '成员账号';

COMMENT ON COLUMN repository_permissions.repo_id IS '目标仓库';

COMMENT ON COLUMN repository_permissions.permission_level IS '权限等级：READ、MAINTAIN 或 MANAGE';

COMMENT ON COLUMN repository_permissions.created_at IS '授权时间';

COMMENT ON COLUMN repository_permissions.updated_at IS '最后调整时间';

CREATE TABLE repository_project_drafts (
    id uuid NOT NULL,
    owner_account_id uuid NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500) DEFAULT ''::character varying NOT NULL,
    source_type text,
    source_location text,
    credential_id uuid,
    lifecycle_status text DEFAULT 'DRAFT'::text NOT NULL,
    result_repository_id uuid,
    error text,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT repository_project_drafts_lifecycle_status_check CHECK ((lifecycle_status = ANY (ARRAY['DRAFT'::text, 'SOURCE_CONFIGURED'::text, 'IMPORTING'::text, 'READY'::text, 'FAILED'::text])))
);

CREATE TABLE system_settings (
    setting_key character varying(120) NOT NULL,
    setting_value text NOT NULL,
    sensitive boolean DEFAULT false NOT NULL,
    updated_by uuid,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE system_settings IS '系统级运行配置';

COMMENT ON COLUMN system_settings.setting_key IS '配置键';

COMMENT ON COLUMN system_settings.setting_value IS '配置值';

COMMENT ON COLUMN system_settings.sensitive IS '读取时是否必须掩码';

COMMENT ON COLUMN system_settings.updated_by IS '最后修改账号';

COMMENT ON COLUMN system_settings.updated_at IS '最后更新时间';

CREATE TABLE vector_model_activation (
    singleton_id smallint DEFAULT 1 NOT NULL,
    active_config_id uuid NOT NULL,
    activation_version bigint DEFAULT 0 NOT NULL,
    activated_by uuid,
    activated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT vector_model_activation_singleton_id_check CHECK ((singleton_id = 1))
);

COMMENT ON TABLE vector_model_activation IS '系统当前启用的向量模型单例';

CREATE TABLE vector_model_configs (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    provider_type character varying(40) NOT NULL,
    base_url text,
    model character varying(200) NOT NULL,
    dimension integer NOT NULL,
    request_timeout_ms integer DEFAULT 30000 NOT NULL,
    secret_version_id uuid,
    created_by uuid,
    updated_by uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT vector_model_configs_dimension_check CHECK (((dimension >= 1) AND (dimension <= 4096))),
    CONSTRAINT vector_model_configs_provider_type_check CHECK (((provider_type)::text = ANY ((ARRAY['LOCAL_HASH'::character varying, 'OPENAI_COMPATIBLE'::character varying])::text[]))),
    CONSTRAINT vector_model_configs_request_timeout_ms_check CHECK (((request_timeout_ms >= 3000) AND (request_timeout_ms <= 120000)))
);

COMMENT ON TABLE vector_model_configs IS '本地及外部向量模型备案';

COMMENT ON COLUMN vector_model_configs.provider_type IS '向量模型运行方式';

COMMENT ON COLUMN vector_model_configs.base_url IS '外部向量服务基础地址';

COMMENT ON COLUMN vector_model_configs.model IS '向量模型标识';

COMMENT ON COLUMN vector_model_configs.dimension IS '输出向量维度';

COMMENT ON COLUMN vector_model_configs.secret_version_id IS '外部服务密钥版本';

ALTER TABLE ONLY account_access_tokens
    ADD CONSTRAINT account_access_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY account_access_tokens
    ADD CONSTRAINT account_access_tokens_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY accounts
    ADD CONSTRAINT accounts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit_events
    ADD CONSTRAINT audit_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY branch_context_knowledge
    ADD CONSTRAINT branch_context_knowledge_pkey PRIMARY KEY (context_id, card_id);

ALTER TABLE ONLY branch_preparation_jobs
    ADD CONSTRAINT branch_preparation_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY branch_read_contexts
    ADD CONSTRAINT branch_read_contexts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY chunk_embeddings
    ADD CONSTRAINT chunk_embeddings_pkey PRIMARY KEY (chunk_id);

ALTER TABLE ONLY code_chunks
    ADD CONSTRAINT code_chunks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY heuristic_call_edges
    ADD CONSTRAINT code_graph_edges_pkey PRIMARY KEY (id);

ALTER TABLE ONLY heuristic_call_edges
    ADD CONSTRAINT code_graph_edges_repo_id_content_version_source_chunk_id_ta_key UNIQUE (repo_id, content_version, source_chunk_id, target_chunk_id, relation);

ALTER TABLE ONLY codegraph_artifacts
    ADD CONSTRAINT codegraph_artifacts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY encrypted_secret_versions
    ADD CONSTRAINT encrypted_secret_versions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY git_credentials
    ADD CONSTRAINT git_credentials_pkey PRIMARY KEY (id);

ALTER TABLE ONLY index_job_branch_targets
    ADD CONSTRAINT index_job_branch_targets_pkey PRIMARY KEY (job_id);

ALTER TABLE ONLY index_jobs
    ADD CONSTRAINT index_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY knowledge_attachments
    ADD CONSTRAINT knowledge_attachments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY knowledge_attachments
    ADD CONSTRAINT knowledge_attachments_repo_id_id_key UNIQUE (repo_id, id);

ALTER TABLE ONLY knowledge_branch_validations
    ADD CONSTRAINT knowledge_branch_validations_pkey PRIMARY KEY (card_id, revision, branch_id, content_version);

ALTER TABLE ONLY knowledge_card_attachment_refs
    ADD CONSTRAINT knowledge_card_attachment_refs_pkey PRIMARY KEY (card_id, revision, attachment_id);

ALTER TABLE ONLY knowledge_card_embeddings
    ADD CONSTRAINT knowledge_card_embeddings_pkey PRIMARY KEY (card_id);

ALTER TABLE ONLY knowledge_card_markdown_source_links
    ADD CONSTRAINT knowledge_card_markdown_source_links_pkey PRIMARY KEY (card_id, revision);

ALTER TABLE ONLY knowledge_card_revisions
    ADD CONSTRAINT knowledge_card_revisions_pkey PRIMARY KEY (card_id, revision);

ALTER TABLE ONLY knowledge_cards
    ADD CONSTRAINT knowledge_cards_pkey PRIMARY KEY (id);

ALTER TABLE ONLY knowledge_code_refs
    ADD CONSTRAINT knowledge_code_refs_pkey PRIMARY KEY (card_id, revision, "position");

ALTER TABLE ONLY knowledge_drift_events
    ADD CONSTRAINT knowledge_drift_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY llm_connectivity_checks
    ADD CONSTRAINT llm_connectivity_checks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY llm_provider_configs
    ADD CONSTRAINT llm_provider_configs_config_version_key UNIQUE (config_version);

ALTER TABLE ONLY llm_provider_configs
    ADD CONSTRAINT llm_provider_configs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY llm_provider_runtime_states
    ADD CONSTRAINT llm_provider_runtime_states_pkey PRIMARY KEY (config_id);

ALTER TABLE ONLY login_captcha_challenges
    ADD CONSTRAINT login_captcha_challenges_pkey PRIMARY KEY (id);

ALTER TABLE ONLY login_failure_counters
    ADD CONSTRAINT login_failure_counters_pkey PRIMARY KEY (username_normalized);

ALTER TABLE ONLY login_sessions
    ADD CONSTRAINT login_sessions_pkey PRIMARY KEY (token_hash);

ALTER TABLE ONLY qa_citations
    ADD CONSTRAINT qa_citations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY qa_conversations
    ADD CONSTRAINT qa_conversations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY repositories
    ADD CONSTRAINT repositories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY repository_branches
    ADD CONSTRAINT repository_branches_pkey PRIMARY KEY (id);

ALTER TABLE ONLY repository_branches
    ADD CONSTRAINT repository_branches_repo_id_id_key UNIQUE (repo_id, id);

ALTER TABLE ONLY repository_branches
    ADD CONSTRAINT repository_branches_repo_id_name_key UNIQUE (repo_id, name);

ALTER TABLE ONLY repository_credential_bindings
    ADD CONSTRAINT repository_credential_bindings_pkey PRIMARY KEY (repository_id, usage_type);

ALTER TABLE ONLY repository_deletion_tombstones
    ADD CONSTRAINT repository_deletion_tombstones_pkey PRIMARY KEY (repository_id);

ALTER TABLE ONLY repository_import_jobs
    ADD CONSTRAINT repository_import_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY repository_markdown_sources
    ADD CONSTRAINT repository_markdown_sources_pkey PRIMARY KEY (id);

ALTER TABLE ONLY repository_permissions
    ADD CONSTRAINT repository_permissions_pkey PRIMARY KEY (account_id, repo_id);

ALTER TABLE ONLY repository_project_drafts
    ADD CONSTRAINT repository_project_drafts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (setting_key);

ALTER TABLE ONLY repository_markdown_sources
    ADD CONSTRAINT uq_repository_markdown_source_branch_path UNIQUE (repo_id, branch_id, file_path);

ALTER TABLE ONLY vector_model_activation
    ADD CONSTRAINT vector_model_activation_pkey PRIMARY KEY (singleton_id);

ALTER TABLE ONLY vector_model_configs
    ADD CONSTRAINT vector_model_configs_model_key UNIQUE (model);

ALTER TABLE ONLY vector_model_configs
    ADD CONSTRAINT vector_model_configs_pkey PRIMARY KEY (id);

CREATE INDEX branch_job_contentversion ON branch_preparation_jobs USING btree (repo_id, branch_id, target_content_version, kind, created_at DESC);

CREATE UNIQUE INDEX branch_preparation_one_active ON branch_preparation_jobs USING btree (branch_id, kind) WHERE (status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text]));

CREATE INDEX branch_preparation_queue ON branch_preparation_jobs USING btree (status, created_at);

CREATE INDEX idx_account_access_tokens_account ON account_access_tokens USING btree (account_id, created_at DESC);

CREATE INDEX idx_accounts_last_repository ON accounts USING btree (last_repository_id);

CREATE INDEX idx_audit_events_actor ON audit_events USING btree (actor_account_id, created_at DESC);

CREATE INDEX idx_audit_events_created ON audit_events USING btree (created_at DESC, id);

CREATE INDEX idx_branch_context_expiry ON branch_read_contexts USING btree (expires_at);

CREATE INDEX idx_branch_graph_target_content_version ON index_job_branch_targets USING btree (repo_id, content_version);

CREATE INDEX idx_chunk_embeddings_repo ON chunk_embeddings USING btree (repo_id);

CREATE INDEX idx_chunk_embeddings_reuse ON chunk_embeddings USING btree (repo_id, content_hash, model, dimension, retrieval_capability);

CREATE INDEX idx_code_chunks_branch_version ON code_chunks USING btree (repo_id, branch_id, content_version);

CREATE INDEX idx_code_chunks_repo_asset_type ON code_chunks USING btree (repo_id, asset_type, file_path);

CREATE INDEX idx_code_chunks_repo_contentversion ON code_chunks USING btree (repo_id, content_version);

CREATE INDEX idx_code_chunks_repo_created_at ON code_chunks USING btree (repo_id, created_at DESC);

CREATE INDEX idx_code_chunks_repo_file ON code_chunks USING btree (repo_id, file_path);

CREATE INDEX idx_code_chunks_repo_language ON code_chunks USING btree (repo_id, language);

CREATE INDEX idx_code_chunks_repo_path_line ON code_chunks USING btree (repo_id, file_path, start_line);

CREATE INDEX idx_code_chunks_repo_symbol ON code_chunks USING btree (repo_id, symbol_id);

CREATE INDEX idx_codegraph_artifacts_repo_contentversion ON codegraph_artifacts USING btree (repo_id, content_version, created_at DESC);

CREATE INDEX idx_git_credentials_expiry ON git_credentials USING btree (status, expires_at);

CREATE INDEX idx_git_credentials_owner ON git_credentials USING btree (created_by, status, created_at DESC);

CREATE INDEX idx_heuristic_call_edges_source ON heuristic_call_edges USING btree (repo_id, source_symbol);

CREATE INDEX idx_heuristic_call_edges_target ON heuristic_call_edges USING btree (repo_id, target_symbol);

CREATE INDEX idx_index_jobs_repo_created_at ON index_jobs USING btree (repo_id, created_at DESC);

CREATE INDEX idx_index_jobs_running_timeout ON index_jobs USING btree (timeout_at) WHERE (status = ANY (ARRAY['RUNNING'::text, 'CANCEL_REQUESTED'::text]));

CREATE INDEX idx_knowledge_attachments_repo_created ON knowledge_attachments USING btree (repo_id, created_at DESC);

CREATE INDEX idx_knowledge_card_embeddings_repo ON knowledge_card_embeddings USING btree (repo_id);

CREATE INDEX idx_knowledge_card_revisions_repo_card ON knowledge_card_revisions USING btree (repo_id, card_id, revision DESC);

CREATE INDEX idx_knowledge_cards_branch_status ON knowledge_cards USING btree (repo_id, branch_id, publication_status, updated_at DESC);

CREATE INDEX idx_knowledge_cards_engineering_policy ON knowledge_cards USING btree (repo_id, knowledge_kind, enforcement, publication_status);

CREATE INDEX idx_knowledge_cards_repo_status ON knowledge_cards USING btree (repo_id, publication_status, updated_at DESC);

CREATE INDEX idx_knowledge_code_refs_card ON knowledge_code_refs USING btree (card_id, revision);

CREATE INDEX idx_knowledge_code_refs_repo_file ON knowledge_code_refs USING btree (repo_id, file_path);

CREATE INDEX idx_knowledge_drift_card_created ON knowledge_drift_events USING btree (repo_id, card_id, created_at DESC, id DESC);

CREATE INDEX idx_knowledge_markdown_links_card ON knowledge_card_markdown_source_links USING btree (card_id, revision DESC);

CREATE INDEX idx_knowledge_markdown_links_source ON knowledge_card_markdown_source_links USING btree (repo_id, source_branch_id, source_path, generated_at DESC);

CREATE INDEX idx_knowledge_refs_current_lookup ON knowledge_code_refs USING btree (repo_id, file_path, start_line, content_hash);

CREATE INDEX idx_llm_checks_actor_created ON llm_connectivity_checks USING btree (actor_id, created_at DESC);

CREATE INDEX idx_llm_checks_fingerprint_created ON llm_connectivity_checks USING btree (fingerprint, created_at DESC);

CREATE INDEX idx_llm_configs_created ON llm_provider_configs USING btree (created_at DESC);

CREATE INDEX idx_login_captcha_user ON login_captcha_challenges USING btree (username_normalized, created_at DESC);

CREATE INDEX idx_login_sessions_account ON login_sessions USING btree (account_id);

CREATE INDEX idx_login_sessions_expiry ON login_sessions USING btree (expires_at);

CREATE INDEX idx_qa_conversations_account_repo_created ON qa_conversations USING btree (account_id, repo_id, created_at DESC);

CREATE INDEX idx_qa_conversations_account_repo_thread ON qa_conversations USING btree (account_id, repo_id, thread_id, updated_at DESC);

CREATE INDEX idx_qa_conversations_branch_history ON qa_conversations USING btree (account_id, repo_id, branch_id, updated_at DESC);

CREATE INDEX idx_qa_conversations_thread_created ON qa_conversations USING btree (thread_id, turn_no, created_at);

CREATE INDEX idx_repositories_owner_status ON repositories USING btree (owner_account_id, repository_status, created_at DESC);

CREATE INDEX idx_repository_branches_active ON repository_branches USING btree (repo_id, name) WHERE (tracking_status = 'ACTIVE'::text);

CREATE INDEX idx_repository_credential_bindings_credential ON repository_credential_bindings USING btree (credential_id, created_at DESC);

CREATE INDEX idx_repository_credentials_repo ON git_credentials USING btree (legacy_repo_id, created_at DESC);

CREATE INDEX idx_repository_deletion_cleanup_queue ON repository_deletion_tombstones USING btree (cleanup_status, cleanup_updated_at, deleted_at);

CREATE INDEX idx_repository_import_jobs_actor ON repository_import_jobs USING btree (account_id, created_at DESC);

CREATE INDEX idx_repository_import_jobs_queue ON repository_import_jobs USING btree (status, created_at);

CREATE INDEX idx_repository_markdown_sources_contentversion ON repository_markdown_sources USING btree (repo_id, branch_id, content_version, file_path);

CREATE INDEX idx_repository_markdown_sources_path_hash ON repository_markdown_sources USING btree (repo_id, branch_id, file_path, content_hash);

CREATE INDEX idx_repository_permissions_repo ON repository_permissions USING btree (repo_id);

CREATE INDEX idx_repository_project_drafts_owner ON repository_project_drafts USING btree (owner_account_id, updated_at DESC);

CREATE UNIQUE INDEX uk_qa_conversations_client_request ON qa_conversations USING btree (account_id, repo_id, client_request_id) WHERE (client_request_id IS NOT NULL);

CREATE UNIQUE INDEX uk_qa_conversations_thread_turn ON qa_conversations USING btree (thread_id, turn_no);

CREATE UNIQUE INDEX uq_accounts_username_normalized ON accounts USING btree (lower(btrim(username)));

CREATE UNIQUE INDEX uq_index_jobs_one_active_per_repository ON index_jobs USING btree (repo_id) WHERE (status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'CANCEL_REQUESTED'::text]));

CREATE UNIQUE INDEX uq_knowledge_drift_automatic_contentversion ON knowledge_drift_events USING btree (card_id, card_revision, to_content_version, trigger_type) WHERE ((trigger_type)::text = 'AUTOMATIC_DIFF'::text);

CREATE UNIQUE INDEX uq_repositories_normalized_path ON repositories USING btree (path);

CREATE UNIQUE INDEX uq_repositories_owner_normalized_name ON repositories USING btree (owner_account_id, normalized_name) WHERE (deleted_at IS NULL);

CREATE TRIGGER repositories_default_branch_contentversion AFTER INSERT OR UPDATE OF current_content_version, current_workspace_path, current_commit ON repositories FOR EACH ROW EXECUTE FUNCTION synchronize_default_repository_branch();

CREATE TRIGGER trg_assign_code_chunk_branch BEFORE INSERT ON code_chunks FOR EACH ROW EXECUTE FUNCTION assign_code_chunk_branch();

CREATE TRIGGER trg_cleanup_deleted_repository_branches AFTER UPDATE OF repository_status ON repositories FOR EACH ROW WHEN (((new.repository_status = 'DELETED'::text) AND (old.repository_status IS DISTINCT FROM new.repository_status))) EXECUTE FUNCTION cleanup_deleted_repository_branches();

CREATE TRIGGER trg_knowledge_card_revision AFTER INSERT OR UPDATE OF title, card_type, content, tags, publication_status, knowledge_kind, severity, enforcement, owner_account_id, scope_payload, obligations_payload, last_verified_content_version, verification_note, revision ON knowledge_cards FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();

ALTER TABLE ONLY account_access_tokens
    ADD CONSTRAINT account_access_tokens_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY audit_events
    ADD CONSTRAINT audit_events_actor_account_id_fkey FOREIGN KEY (actor_account_id) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY audit_events
    ADD CONSTRAINT audit_events_target_account_id_fkey FOREIGN KEY (target_account_id) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY audit_events
    ADD CONSTRAINT audit_events_target_repo_id_fkey FOREIGN KEY (target_repo_id) REFERENCES repositories(id) ON DELETE SET NULL;

ALTER TABLE ONLY branch_context_knowledge
    ADD CONSTRAINT branch_context_knowledge_card_id_fkey FOREIGN KEY (card_id) REFERENCES knowledge_cards(id) ON DELETE CASCADE;

ALTER TABLE ONLY branch_context_knowledge
    ADD CONSTRAINT branch_context_knowledge_context_id_fkey FOREIGN KEY (context_id) REFERENCES branch_read_contexts(id) ON DELETE CASCADE;

ALTER TABLE ONLY branch_preparation_jobs
    ADD CONSTRAINT branch_preparation_jobs_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id);

ALTER TABLE ONLY branch_preparation_jobs
    ADD CONSTRAINT branch_preparation_jobs_repo_id_branch_id_fkey FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY branch_read_contexts
    ADD CONSTRAINT branch_read_contexts_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY branch_read_contexts
    ADD CONSTRAINT branch_read_contexts_repo_id_branch_id_fkey FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY chunk_embeddings
    ADD CONSTRAINT chunk_embeddings_chunk_id_fkey FOREIGN KEY (chunk_id) REFERENCES code_chunks(id) ON DELETE CASCADE;

ALTER TABLE ONLY chunk_embeddings
    ADD CONSTRAINT chunk_embeddings_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY code_chunks
    ADD CONSTRAINT code_chunks_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id);

ALTER TABLE ONLY heuristic_call_edges
    ADD CONSTRAINT code_graph_edges_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY heuristic_call_edges
    ADD CONSTRAINT code_graph_edges_source_chunk_id_fkey FOREIGN KEY (source_chunk_id) REFERENCES code_chunks(id) ON DELETE CASCADE;

ALTER TABLE ONLY heuristic_call_edges
    ADD CONSTRAINT code_graph_edges_target_chunk_id_fkey FOREIGN KEY (target_chunk_id) REFERENCES code_chunks(id) ON DELETE CASCADE;

ALTER TABLE ONLY codegraph_artifacts
    ADD CONSTRAINT codegraph_artifacts_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY encrypted_secret_versions
    ADD CONSTRAINT encrypted_secret_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY accounts
    ADD CONSTRAINT fk_accounts_last_repository FOREIGN KEY (last_repository_id) REFERENCES repositories(id) ON DELETE SET NULL;

ALTER TABLE ONLY code_chunks
    ADD CONSTRAINT fk_code_chunk_branch FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_cards
    ADD CONSTRAINT fk_knowledge_card_branch FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_card_markdown_source_links
    ADD CONSTRAINT fk_knowledge_markdown_source_branch FOREIGN KEY (repo_id, source_branch_id) REFERENCES repository_branches(repo_id, id);

ALTER TABLE ONLY knowledge_card_revisions
    ADD CONSTRAINT fk_knowledge_revision_branch FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY llm_provider_runtime_states
    ADD CONSTRAINT fk_llm_runtime_latest_check FOREIGN KEY (latest_check_id) REFERENCES llm_connectivity_checks(id) ON DELETE SET NULL;

ALTER TABLE ONLY repository_markdown_sources
    ADD CONSTRAINT fk_markdown_source_branch FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY qa_citations
    ADD CONSTRAINT fk_qa_citations_knowledge_card FOREIGN KEY (knowledge_card_id) REFERENCES knowledge_cards(id) ON DELETE SET NULL;

ALTER TABLE ONLY qa_conversations
    ADD CONSTRAINT fk_qa_conversation_branch FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id);

ALTER TABLE ONLY qa_conversations
    ADD CONSTRAINT fk_qa_conversation_context FOREIGN KEY (context_id) REFERENCES branch_read_contexts(id) ON DELETE SET NULL;

ALTER TABLE ONLY git_credentials
    ADD CONSTRAINT git_credentials_created_by_fkey FOREIGN KEY (created_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY git_credentials
    ADD CONSTRAINT git_credentials_legacy_repo_id_fkey FOREIGN KEY (legacy_repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY git_credentials
    ADD CONSTRAINT git_credentials_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY index_job_branch_targets
    ADD CONSTRAINT index_job_branch_targets_job_id_fkey FOREIGN KEY (job_id) REFERENCES index_jobs(id) ON DELETE CASCADE;

ALTER TABLE ONLY index_job_branch_targets
    ADD CONSTRAINT index_job_branch_targets_repo_id_branch_id_fkey FOREIGN KEY (repo_id, branch_id) REFERENCES repository_branches(repo_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY index_jobs
    ADD CONSTRAINT index_jobs_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id);

ALTER TABLE ONLY knowledge_attachments
    ADD CONSTRAINT knowledge_attachments_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_attachments
    ADD CONSTRAINT knowledge_attachments_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_branch_validations
    ADD CONSTRAINT knowledge_branch_validations_branch_id_fkey FOREIGN KEY (branch_id) REFERENCES repository_branches(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_branch_validations
    ADD CONSTRAINT knowledge_branch_validations_card_id_fkey FOREIGN KEY (card_id) REFERENCES knowledge_cards(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_branch_validations
    ADD CONSTRAINT knowledge_branch_validations_card_id_revision_fkey FOREIGN KEY (card_id, revision) REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_branch_validations
    ADD CONSTRAINT knowledge_branch_validations_checked_by_fkey FOREIGN KEY (checked_by) REFERENCES accounts(id);

ALTER TABLE ONLY knowledge_card_attachment_refs
    ADD CONSTRAINT knowledge_card_attachment_refs_attachment_id_fkey FOREIGN KEY (attachment_id) REFERENCES knowledge_attachments(id) ON DELETE RESTRICT;

ALTER TABLE ONLY knowledge_card_attachment_refs
    ADD CONSTRAINT knowledge_card_attachment_refs_card_id_revision_fkey FOREIGN KEY (card_id, revision) REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_card_embeddings
    ADD CONSTRAINT knowledge_card_embeddings_card_id_fkey FOREIGN KEY (card_id) REFERENCES knowledge_cards(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_card_embeddings
    ADD CONSTRAINT knowledge_card_embeddings_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_card_markdown_source_links
    ADD CONSTRAINT knowledge_card_markdown_source_links_card_id_revision_fkey FOREIGN KEY (card_id, revision) REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_card_markdown_source_links
    ADD CONSTRAINT knowledge_card_markdown_source_links_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_card_markdown_source_links
    ADD CONSTRAINT knowledge_card_markdown_source_links_source_id_fkey FOREIGN KEY (source_id) REFERENCES repository_markdown_sources(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_card_revisions
    ADD CONSTRAINT knowledge_card_revisions_card_id_fkey FOREIGN KEY (card_id) REFERENCES knowledge_cards(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_card_revisions
    ADD CONSTRAINT knowledge_card_revisions_changed_by_fkey FOREIGN KEY (changed_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_card_revisions
    ADD CONSTRAINT knowledge_card_revisions_owner_account_id_fkey FOREIGN KEY (owner_account_id) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_card_revisions
    ADD CONSTRAINT knowledge_card_revisions_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_cards
    ADD CONSTRAINT knowledge_cards_created_by_fkey FOREIGN KEY (created_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_cards
    ADD CONSTRAINT knowledge_cards_owner_account_id_fkey FOREIGN KEY (owner_account_id) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_cards
    ADD CONSTRAINT knowledge_cards_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id);

ALTER TABLE ONLY knowledge_cards
    ADD CONSTRAINT knowledge_cards_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_cards
    ADD CONSTRAINT knowledge_cards_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_code_refs
    ADD CONSTRAINT knowledge_code_refs_card_id_revision_fkey FOREIGN KEY (card_id, revision) REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_code_refs
    ADD CONSTRAINT knowledge_code_refs_chunk_id_fkey FOREIGN KEY (chunk_id) REFERENCES code_chunks(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_code_refs
    ADD CONSTRAINT knowledge_code_refs_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_drift_events
    ADD CONSTRAINT knowledge_drift_events_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY knowledge_drift_events
    ADD CONSTRAINT knowledge_drift_events_card_id_fkey FOREIGN KEY (card_id) REFERENCES knowledge_cards(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_drift_events
    ADD CONSTRAINT knowledge_drift_events_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY llm_connectivity_checks
    ADD CONSTRAINT llm_connectivity_checks_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY llm_connectivity_checks
    ADD CONSTRAINT llm_connectivity_checks_config_id_fkey FOREIGN KEY (config_id) REFERENCES llm_provider_configs(id) ON DELETE SET NULL;

ALTER TABLE ONLY llm_provider_configs
    ADD CONSTRAINT llm_provider_configs_created_by_fkey FOREIGN KEY (created_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY llm_provider_configs
    ADD CONSTRAINT llm_provider_configs_secret_version_id_fkey FOREIGN KEY (secret_version_id) REFERENCES encrypted_secret_versions(id);

ALTER TABLE ONLY llm_provider_runtime_states
    ADD CONSTRAINT llm_provider_runtime_states_config_id_fkey FOREIGN KEY (config_id) REFERENCES llm_provider_configs(id) ON DELETE CASCADE;

ALTER TABLE ONLY login_sessions
    ADD CONSTRAINT login_sessions_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY qa_citations
    ADD CONSTRAINT qa_citations_chunk_id_fkey FOREIGN KEY (chunk_id) REFERENCES code_chunks(id) ON DELETE SET NULL;

ALTER TABLE ONLY qa_citations
    ADD CONSTRAINT qa_citations_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES qa_conversations(id) ON DELETE CASCADE;

ALTER TABLE ONLY qa_citations
    ADD CONSTRAINT qa_citations_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE SET NULL;

ALTER TABLE ONLY qa_conversations
    ADD CONSTRAINT qa_conversations_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY qa_conversations
    ADD CONSTRAINT qa_conversations_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY repositories
    ADD CONSTRAINT repositories_owner_account_id_fkey FOREIGN KEY (owner_account_id) REFERENCES accounts(id);

ALTER TABLE ONLY repository_branches
    ADD CONSTRAINT repository_branches_archived_by_fkey FOREIGN KEY (archived_by) REFERENCES accounts(id);

ALTER TABLE ONLY repository_branches
    ADD CONSTRAINT repository_branches_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY repository_credential_bindings
    ADD CONSTRAINT repository_credential_bindings_bound_by_fkey FOREIGN KEY (bound_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY repository_credential_bindings
    ADD CONSTRAINT repository_credential_bindings_credential_id_fkey FOREIGN KEY (credential_id) REFERENCES git_credentials(id) ON DELETE RESTRICT;

ALTER TABLE ONLY repository_credential_bindings
    ADD CONSTRAINT repository_credential_bindings_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY repository_deletion_tombstones
    ADD CONSTRAINT repository_deletion_tombstones_deleted_by_fkey FOREIGN KEY (deleted_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY repository_import_jobs
    ADD CONSTRAINT repository_import_jobs_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY repository_import_jobs
    ADD CONSTRAINT repository_import_jobs_credential_id_fkey FOREIGN KEY (credential_id) REFERENCES git_credentials(id) ON DELETE RESTRICT;

ALTER TABLE ONLY repository_import_jobs
    ADD CONSTRAINT repository_import_jobs_project_draft_id_fkey FOREIGN KEY (project_draft_id) REFERENCES repository_project_drafts(id) ON DELETE SET NULL;

ALTER TABLE ONLY repository_import_jobs
    ADD CONSTRAINT repository_import_jobs_result_repository_id_fkey FOREIGN KEY (result_repository_id) REFERENCES repositories(id) ON DELETE SET NULL;

ALTER TABLE ONLY repository_markdown_sources
    ADD CONSTRAINT repository_markdown_sources_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY repository_permissions
    ADD CONSTRAINT repository_permissions_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY repository_permissions
    ADD CONSTRAINT repository_permissions_repo_id_fkey FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY repository_project_drafts
    ADD CONSTRAINT repository_project_drafts_credential_id_fkey FOREIGN KEY (credential_id) REFERENCES git_credentials(id) ON DELETE SET NULL;

ALTER TABLE ONLY repository_project_drafts
    ADD CONSTRAINT repository_project_drafts_owner_account_id_fkey FOREIGN KEY (owner_account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY repository_project_drafts
    ADD CONSTRAINT repository_project_drafts_result_repository_id_fkey FOREIGN KEY (result_repository_id) REFERENCES repositories(id) ON DELETE SET NULL;

ALTER TABLE ONLY system_settings
    ADD CONSTRAINT system_settings_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY vector_model_activation
    ADD CONSTRAINT vector_model_activation_activated_by_fkey FOREIGN KEY (activated_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY vector_model_activation
    ADD CONSTRAINT vector_model_activation_active_config_id_fkey FOREIGN KEY (active_config_id) REFERENCES vector_model_configs(id);

ALTER TABLE ONLY vector_model_configs
    ADD CONSTRAINT vector_model_configs_created_by_fkey FOREIGN KEY (created_by) REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE ONLY vector_model_configs
    ADD CONSTRAINT vector_model_configs_secret_version_id_fkey FOREIGN KEY (secret_version_id) REFERENCES encrypted_secret_versions(id);

ALTER TABLE ONLY vector_model_configs
    ADD CONSTRAINT vector_model_configs_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES accounts(id) ON DELETE SET NULL;

-- Initial application settings and local vector model.
INSERT INTO system_settings(setting_key, setting_value)
VALUES ('externalModelEnabled', 'false');

INSERT INTO vector_model_configs(id,name,provider_type,model,dimension)
VALUES ('00000000-0000-0000-0000-000000000064','内置向量模型','LOCAL_HASH','local-hash-64',64);

INSERT INTO vector_model_activation(singleton_id,active_config_id)
VALUES (1,'00000000-0000-0000-0000-000000000064');
