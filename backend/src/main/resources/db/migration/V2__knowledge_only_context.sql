-- A knowledge workspace has an account-bound read context, but no code version.
ALTER TABLE branch_read_contexts ALTER COLUMN content_version DROP NOT NULL;
