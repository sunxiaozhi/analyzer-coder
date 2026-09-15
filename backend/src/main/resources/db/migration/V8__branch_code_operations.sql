-- Project permissions stay on repositories; code operations belong to branches and snapshots.
ALTER TABLE branch_snapshots ADD COLUMN content_indexed_at TIMESTAMPTZ;
ALTER TABLE repository_branches ADD COLUMN last_synced_at TIMESTAMPTZ;
UPDATE repository_branches b SET last_synced_at=s.created_at FROM branch_snapshots s
WHERE s.repo_id=b.repo_id AND s.branch_id=b.id AND s.id=b.published_snapshot_id;
UPDATE branch_snapshots s SET content_indexed_at=s.created_at
WHERE EXISTS(SELECT 1 FROM code_chunks c WHERE c.repo_id=s.repo_id AND c.snapshot_id=s.id);

-- Keep legacy SNAPSHOT jobs readable while adding independent branch operations.
ALTER TABLE branch_preparation_jobs DROP CONSTRAINT branch_preparation_jobs_kind_check;
ALTER TABLE branch_preparation_jobs DROP CONSTRAINT branch_preparation_jobs_check;
ALTER TABLE branch_preparation_jobs ADD CONSTRAINT branch_job_kind
CHECK(kind IN ('SNAPSHOT','SYNC','CONTENT','GRAPH','VECTORS','PREPARE'));
ALTER TABLE branch_preparation_jobs ADD CONSTRAINT branch_job_target
CHECK(kind IN ('SNAPSHOT','SYNC','PREPARE') OR target_snapshot IS NOT NULL);
CREATE INDEX branch_job_snapshot ON branch_preparation_jobs(repo_id,branch_id,target_snapshot,kind,created_at DESC);
COMMENT ON COLUMN branch_snapshots.content_indexed_at IS 'Content index publication for this exact immutable snapshot';
COMMENT ON COLUMN repositories.default_branch IS 'Initial branch reading preference; sync and indexes belong to individual branches';

-- Changing the reading preference alone must not publish or create any branch code.
DROP TRIGGER repositories_default_branch_snapshot ON repositories;
CREATE TRIGGER repositories_default_branch_snapshot
AFTER INSERT OR UPDATE OF current_snapshot_id,current_snapshot_path,current_commit
ON repositories FOR EACH ROW EXECUTE FUNCTION synchronize_default_repository_branch();

-- Legacy branch preparation writes chunks before it publishes the branch snapshot.
CREATE FUNCTION initialize_branch_content_state() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM code_chunks c WHERE c.repo_id=NEW.repo_id AND c.snapshot_id=NEW.id) THEN
        NEW.content_indexed_at=COALESCE(NEW.content_indexed_at,CURRENT_TIMESTAMP);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_initialize_branch_content_state BEFORE INSERT ON branch_snapshots
    FOR EACH ROW EXECUTE FUNCTION initialize_branch_content_state();
