-- 跨仓工程项目退出产品范围。非空部署必须先迁移/导出跨仓数据，不能静默扩大知识范围。
LOCK TABLE engineering_projects, engineering_project_repositories, engineering_project_contracts,
    knowledge_cards, knowledge_card_revisions IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM engineering_projects)
       OR EXISTS (SELECT 1 FROM engineering_project_repositories)
       OR EXISTS (SELECT 1 FROM engineering_project_contracts) THEN
        RAISE EXCEPTION 'Cross-repository project data must be exported/migrated before V9';
    END IF;
    IF EXISTS (
        SELECT 1 FROM knowledge_cards
        WHERE jsonb_array_length(COALESCE(scope_payload->'repositoryIds', '[]'::jsonb)) > 0
           OR jsonb_array_length(COALESCE(scope_payload->'serviceNames', '[]'::jsonb)) > 0
           OR jsonb_array_length(COALESCE(scope_payload->'contractIds', '[]'::jsonb)) > 0
    ) OR EXISTS (
        SELECT 1 FROM knowledge_card_revisions
        WHERE jsonb_array_length(COALESCE(scope_payload->'repositoryIds', '[]'::jsonb)) > 0
           OR jsonb_array_length(COALESCE(scope_payload->'serviceNames', '[]'::jsonb)) > 0
           OR jsonb_array_length(COALESCE(scope_payload->'contractIds', '[]'::jsonb)) > 0
    ) THEN
        RAISE EXCEPTION 'Cross-repository knowledge scopes must be migrated before V9';
    END IF;
END $$;

ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_scope_payload;
ALTER TABLE knowledge_card_revisions DROP CONSTRAINT chk_knowledge_revision_scope_payload;

-- 结构清理不能触发重写当前修订的正文、状态或审计信息。
ALTER TABLE knowledge_cards DISABLE TRIGGER trg_knowledge_card_revision;

UPDATE knowledge_cards
SET scope_payload = scope_payload - 'repositoryIds' - 'serviceNames' - 'contractIds'
WHERE scope_payload ?| ARRAY['repositoryIds', 'serviceNames', 'contractIds'];
UPDATE knowledge_card_revisions
SET scope_payload = scope_payload - 'repositoryIds' - 'serviceNames' - 'contractIds'
WHERE scope_payload ?| ARRAY['repositoryIds', 'serviceNames', 'contractIds'];

ALTER TABLE knowledge_cards ENABLE TRIGGER trg_knowledge_card_revision;

ALTER TABLE knowledge_cards ALTER COLUMN scope_payload SET DEFAULT
    '{"pathPatterns":[],"symbols":[],"modules":[]}'::jsonb;
ALTER TABLE knowledge_card_revisions ALTER COLUMN scope_payload SET DEFAULT
    '{"pathPatterns":[],"symbols":[],"modules":[]}'::jsonb;
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_scope_payload CHECK (
    jsonb_typeof(scope_payload)='object'
    AND jsonb_typeof(scope_payload->'pathPatterns')='array'
    AND jsonb_typeof(scope_payload->'symbols')='array'
    AND jsonb_typeof(scope_payload->'modules')='array'
    AND NOT (scope_payload ?| ARRAY['repositoryIds', 'serviceNames', 'contractIds'])
);
ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_scope_payload CHECK (
    jsonb_typeof(scope_payload)='object'
    AND jsonb_typeof(scope_payload->'pathPatterns')='array'
    AND jsonb_typeof(scope_payload->'symbols')='array'
    AND jsonb_typeof(scope_payload->'modules')='array'
    AND NOT (scope_payload ?| ARRAY['repositoryIds', 'serviceNames', 'contractIds'])
);
COMMENT ON COLUMN knowledge_cards.scope_payload IS '项目代码中的路径、符号和模块适用范围';
COMMENT ON COLUMN knowledge_card_revisions.scope_payload IS '知识修订中的路径、符号和模块适用范围';

DROP TABLE engineering_project_contracts;
DROP TABLE engineering_project_repositories;
DROP TABLE engineering_projects;
