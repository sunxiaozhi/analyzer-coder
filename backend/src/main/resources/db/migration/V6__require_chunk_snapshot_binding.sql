DELETE FROM code_chunks WHERE snapshot_id IS NULL;

ALTER TABLE code_chunks
  ALTER COLUMN snapshot_id SET NOT NULL;
