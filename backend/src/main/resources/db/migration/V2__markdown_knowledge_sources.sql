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
