-- An OIDC principal owns one immutable personal workspace in the Core API.
CREATE TABLE core.personal_workspaces (
  issuer varchar(2048) NOT NULL CHECK (length(issuer) BETWEEN 1 AND 2048),
  subject varchar(256) NOT NULL CHECK (length(subject) BETWEEN 1 AND 256),
  workspace_id uuid NOT NULL UNIQUE REFERENCES core.workspaces (id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT personal_workspaces_pk PRIMARY KEY (issuer, subject)
);

REVOKE ALL ON core.personal_workspaces FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT, INSERT ON core.personal_workspaces TO authweave_core_runtime;

DO $privileges$
BEGIN
  IF NOT has_table_privilege('authweave_core_runtime', 'core.personal_workspaces', 'SELECT') OR
     NOT has_table_privilege('authweave_core_runtime', 'core.personal_workspaces', 'INSERT') OR
     has_table_privilege('authweave_core_runtime', 'core.personal_workspaces', 'UPDATE') OR
     has_table_privilege('authweave_core_runtime', 'core.personal_workspaces', 'DELETE') OR
     has_table_privilege('authweave_web_runtime', 'core.personal_workspaces', 'SELECT') THEN
    RAISE EXCEPTION 'Personal workspace ownership privileges are not isolated';
  END IF;
END
$privileges$;

COMMENT ON TABLE core.personal_workspaces IS
  'Immutable OIDC issuer/subject ownership binding. Other Core API routes still require future workspace authorization.';
