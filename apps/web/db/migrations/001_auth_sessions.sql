BEGIN;

SELECT pg_advisory_xact_lock(41852001);

CREATE TABLE IF NOT EXISTS web.schema_migrations (
  version integer PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $migration$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM web.schema_migrations WHERE version = 1) THEN
    CREATE TABLE web.oidc_login_transactions (
      state_hash bytea PRIMARY KEY CHECK (octet_length(state_hash) = 32),
      browser_binding_hash bytea NOT NULL CHECK (octet_length(browser_binding_hash) = 32),
      code_verifier varchar(128) NOT NULL,
      nonce varchar(128) NOT NULL,
      created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
      expires_at timestamptz NOT NULL,
      CONSTRAINT oidc_login_transactions_expiry_ck CHECK (expires_at > created_at)
    );

    CREATE INDEX oidc_login_transactions_expiry_idx
      ON web.oidc_login_transactions (expires_at);

    CREATE TABLE web.sessions (
      session_hash bytea PRIMARY KEY CHECK (octet_length(session_hash) = 32),
      issuer text NOT NULL,
      subject text NOT NULL,
      email text,
      display_name text,
      authenticated_at timestamptz NOT NULL,
      created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
      last_seen_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
      idle_expires_at timestamptz NOT NULL,
      absolute_expires_at timestamptz NOT NULL,
      CONSTRAINT sessions_expiry_ck CHECK (
        idle_expires_at > created_at AND
        absolute_expires_at > created_at AND
        idle_expires_at <= absolute_expires_at
      )
    );

    CREATE INDEX sessions_expiry_idx
      ON web.sessions (absolute_expires_at, idle_expires_at);

    INSERT INTO web.schema_migrations (version) VALUES (1);
  END IF;
END
$migration$;

REVOKE ALL ON web.schema_migrations, web.oidc_login_transactions, web.sessions FROM PUBLIC;
REVOKE ALL ON web.schema_migrations, web.oidc_login_transactions, web.sessions
  FROM authweave_core_runtime, authweave_web_runtime;

GRANT SELECT, INSERT, DELETE ON web.oidc_login_transactions TO authweave_web_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON web.sessions TO authweave_web_runtime;

DO $privileges$
BEGIN
  IF has_table_privilege('authweave_core_runtime', 'web.sessions', 'SELECT') OR
     has_table_privilege('authweave_core_runtime', 'web.oidc_login_transactions', 'SELECT') OR
     has_table_privilege('authweave_web_runtime', 'web.schema_migrations', 'SELECT') OR
     has_table_privilege('authweave_web_runtime', 'web.oidc_login_transactions', 'UPDATE') OR
     NOT has_table_privilege('authweave_web_runtime', 'web.sessions', 'INSERT') OR
     NOT has_table_privilege('authweave_web_runtime', 'web.oidc_login_transactions', 'DELETE') THEN
    RAISE EXCEPTION 'Web authentication table privileges are not isolated';
  END IF;
END
$privileges$;

COMMIT;
