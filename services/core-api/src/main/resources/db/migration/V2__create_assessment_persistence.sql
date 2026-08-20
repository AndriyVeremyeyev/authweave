CREATE TABLE core.workspaces (
  id uuid PRIMARY KEY,
  created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE core.assessments (
  workspace_id uuid NOT NULL,
  id uuid NOT NULL,
  status varchar(32) NOT NULL,
  profile_schema_version smallint NOT NULL DEFAULT 1,
  profile jsonb NOT NULL,
  lock_version bigint NOT NULL DEFAULT 0,
  created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT assessments_pk PRIMARY KEY (workspace_id, id),
  CONSTRAINT assessments_workspace_fk
    FOREIGN KEY (workspace_id)
    REFERENCES core.workspaces (id)
    ON DELETE RESTRICT,
  CONSTRAINT assessments_status_ck
    CHECK (status IN (
      'DRAFT',
      'READY_FOR_EVALUATION',
      'EVALUATED',
      'DECIDED',
      'ARCHIVED'
    )),
  CONSTRAINT assessments_profile_schema_version_ck
    CHECK (profile_schema_version = 1),
  CONSTRAINT assessments_profile_object_ck
    CHECK (jsonb_typeof(profile) = 'object'),
  CONSTRAINT assessments_lock_version_ck
    CHECK (lock_version >= 0),
  CONSTRAINT assessments_timestamps_ck
    CHECK (updated_at >= created_at)
);

CREATE INDEX assessments_workspace_updated_idx
  ON core.assessments (workspace_id, updated_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE
  ON core.workspaces, core.assessments
  TO authweave_core_runtime;

COMMENT ON TABLE core.workspaces IS
  'Personal AuthWeave workspace boundary; authentication ownership is added later.';
COMMENT ON TABLE core.assessments IS
  'Identity-architecture assessment aggregate with an optimistic lock version.';
COMMENT ON COLUMN core.assessments.profile IS
  'Canonical ApplicationIdentityProfile JSON; validated by the core domain.';
