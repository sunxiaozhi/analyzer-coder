-- Project permissions stay on repositories; code operations and readiness belong to branches.
ALTER TABLE repository_branches
    ADD COLUMN last_synced_at TIMESTAMPTZ,
    ADD COLUMN content_indexed_at TIMESTAMPTZ;
UPDATE repository_branches b SET
    last_synced_at=COALESCE(b.published_at,b.updated_at),
    content_indexed_at=CASE WHEN EXISTS(
        SELECT 1 FROM code_chunks c WHERE c.repo_id=b.repo_id AND c.content_version=b.content_version
    ) THEN COALESCE(b.published_at,b.updated_at) END;

-- Support independent branch operations and a one-click preparation pipeline.
ALTER TABLE branch_preparation_jobs DROP CONSTRAINT branch_preparation_jobs_kind_check;
ALTER TABLE branch_preparation_jobs DROP CONSTRAINT branch_preparation_jobs_check;
ALTER TABLE branch_preparation_jobs ADD CONSTRAINT branch_job_kind
CHECK(kind IN ('SYNC','CONTENT','GRAPH','VECTORS','PREPARE'));
ALTER TABLE branch_preparation_jobs ADD CONSTRAINT branch_job_target
CHECK(kind IN ('SYNC','PREPARE') OR target_content_version IS NOT NULL);
CREATE INDEX branch_job_contentVersion ON branch_preparation_jobs(repo_id,branch_id,target_content_version,kind,created_at DESC);
COMMENT ON COLUMN repository_branches.content_version IS '当前代码发布代次，只用于并发隔离和派生产物一致性';
COMMENT ON COLUMN repository_branches.content_indexed_at IS '当前分支内容索引完成时间';
COMMENT ON COLUMN repositories.default_branch IS 'Initial branch reading preference; sync and indexes belong to individual branches';

-- Changing the reading preference alone must not publish or create any branch code.
DROP TRIGGER repositories_default_branch_contentVersion ON repositories;
CREATE TRIGGER repositories_default_branch_contentVersion
AFTER INSERT OR UPDATE OF current_content_version,current_workspace_path,current_commit
ON repositories FOR EACH ROW EXECUTE FUNCTION synchronize_default_repository_branch();
