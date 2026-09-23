BEGIN;

SELECT pg_advisory_xact_lock(41852001);

DO $migration$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM web.schema_migrations WHERE version = 4) THEN
    ALTER TABLE web.oidc_login_transactions
      ADD COLUMN purpose varchar(8) NOT NULL DEFAULT 'LOGIN',
      ADD COLUMN session_hash bytea REFERENCES web.sessions(session_hash) ON DELETE CASCADE,
      ADD CONSTRAINT oidc_login_transactions_purpose_ck CHECK (
        (purpose = 'LOGIN' AND session_hash IS NULL) OR
        (purpose = 'REAUTH' AND session_hash IS NOT NULL AND octet_length(session_hash) = 32)
      );
    INSERT INTO web.schema_migrations (version) VALUES (4);
  END IF;
END
$migration$;

COMMIT;
