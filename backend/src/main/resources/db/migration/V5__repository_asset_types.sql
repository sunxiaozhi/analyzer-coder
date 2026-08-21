ALTER TABLE code_chunks
    ADD COLUMN asset_type VARCHAR(24) NOT NULL DEFAULT 'CODE';

UPDATE code_chunks
SET asset_type = CASE
    WHEN LOWER(file_path) ~ '(^|/)(agents|claude|codex)\\.md$'
      OR LOWER(file_path) LIKE '%/.github/instructions/%'
      OR LOWER(file_path) LIKE '%/rules/%' THEN 'RULE'
    WHEN LOWER(file_path) ~ '(^|/)(tasks?|todo|roadmap|checklist|gate)\\.md$'
      OR LOWER(file_path) LIKE '%/tasks/%'
      OR LOWER(file_path) LIKE '%/gates/%' THEN 'TASK'
    WHEN LOWER(COALESCE(language, '')) IN ('yaml','json','xml','properties','toml','ini','conf','env')
      OR LOWER(file_path) ~ '\\.(ya?ml|json|xml|properties|toml|ini|conf|env)$' THEN 'CONFIG'
    WHEN LOWER(COALESCE(language, '')) IN ('markdown','text')
      OR LOWER(file_path) ~ '\\.(md|mdx|rst|txt|adoc)$' THEN 'DOCUMENT'
    ELSE 'CODE'
END;

ALTER TABLE code_chunks
    ADD CONSTRAINT chk_code_chunks_asset_type
    CHECK (asset_type IN ('CODE','DOCUMENT','RULE','TASK','CONFIG'));

CREATE INDEX idx_code_chunks_repo_asset_type
    ON code_chunks(repo_id, asset_type, file_path);

COMMENT ON COLUMN code_chunks.asset_type IS
    '仓库资产类型：代码、文档、规则、任务或配置';
COMMENT ON TABLE code_chunks IS
    '当前仓库版本切分得到的可检索项目资产片段';
COMMENT ON COLUMN code_chunks.content IS
    '用于检索、引用和 Agent 上下文的资产正文';
COMMENT ON COLUMN code_chunks.content_hash IS
    '资产正文摘要';
