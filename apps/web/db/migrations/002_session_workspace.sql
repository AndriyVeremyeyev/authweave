BEGIN;

SELECT pg_advisory_xact_lock(41852001);

DO $migration$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM web.schema_migrations WHERE version = 2) THEN
    -- Pre-existing sessions remain null and are rejected by current application reads.
    ALTER TABLE web.sessions ADD COLUMN workspace_id uuid;
    INSERT INTO web.schema_migrations (version) VALUES (2);
  END IF;
END
$migration$;

DO $privileges$
BEGIN
  IF has_table_privilege('authweave_web_runtime', 'core.personal_workspaces', 'SELECT') OR
     NOT has_column_privilege('authweave_web_runtime', 'web.sessions', 'workspace_id', 'INSERT') OR
     NOT has_column_privilege('authweave_web_runtime', 'web.sessions', 'workspace_id', 'SELECT') THEN
    RAISE EXCEPTION 'Web session workspace privilege boundary is invalid';
  END IF;
END
$privileges$;

COMMIT;
