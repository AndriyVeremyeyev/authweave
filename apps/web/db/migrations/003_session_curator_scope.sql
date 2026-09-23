BEGIN;

SELECT pg_advisory_xact_lock(41852001);

DO $migration$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM web.schema_migrations WHERE version = 3) THEN
    -- Existing sessions stay assessor-only; a later login must supply a verified grant.
    ALTER TABLE web.sessions
      ADD COLUMN curator_project_id text,
      ADD COLUMN curator_org_id text,
      ADD CONSTRAINT sessions_curator_scope_ck CHECK (
        (curator_project_id IS NULL AND curator_org_id IS NULL) OR
        (curator_project_id IS NOT NULL AND curator_org_id IS NOT NULL AND
         curator_project_id ~ '^[0-9]{1,40}$' AND curator_org_id ~ '^[0-9]{1,40}$')
      );
    INSERT INTO web.schema_migrations (version) VALUES (3);
  END IF;
END
$migration$;

COMMIT;
