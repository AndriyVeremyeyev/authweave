CREATE TABLE core.assessment_revisions (
  workspace_id uuid NOT NULL,
  assessment_id uuid NOT NULL,
  version bigint NOT NULL CHECK (version >= 0),
  status varchar(32) NOT NULL CHECK (status IN (
    'DRAFT', 'READY_FOR_EVALUATION', 'EVALUATED', 'DECIDED', 'ARCHIVED'
  )),
  profile_schema_version smallint NOT NULL CHECK (profile_schema_version = 1),
  profile jsonb NOT NULL CHECK (jsonb_typeof(profile) = 'object'),
  origin varchar(16) NOT NULL CHECK (origin IN ('CREATED', 'UPDATED', 'BASELINE')),
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT assessment_revisions_pk PRIMARY KEY (workspace_id, assessment_id, version),
  CONSTRAINT assessment_revisions_assessment_fk FOREIGN KEY (workspace_id, assessment_id)
    REFERENCES core.assessments (workspace_id, id) ON DELETE RESTRICT
);

-- Override the broader core-schema default privileges established in V1.
REVOKE ALL ON core.assessment_revisions FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT, INSERT ON core.assessment_revisions TO authweave_core_runtime;

CREATE TABLE audit.assessment_events (
  id uuid NOT NULL UNIQUE,
  workspace_id uuid NOT NULL,
  assessment_id uuid NOT NULL,
  version bigint NOT NULL,
  previous_version bigint,
  action varchar(32) NOT NULL,
  actor_type varchar(16) NOT NULL CHECK (actor_type IN ('SERVICE', 'MIGRATION')),
  actor_id varchar(64) NOT NULL CHECK (length(actor_id) > 0),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  changed_sections text[] NOT NULL,
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT assessment_events_pk PRIMARY KEY (workspace_id, assessment_id, version),
  CONSTRAINT assessment_events_revision_fk FOREIGN KEY (workspace_id, assessment_id, version)
    REFERENCES core.assessment_revisions (workspace_id, assessment_id, version) ON DELETE RESTRICT,
  CONSTRAINT assessment_events_transition_ck CHECK (
    (action = 'assessment.created' AND version = 0 AND previous_version IS NULL)
    OR (action = 'assessment.updated' AND previous_version IS NOT NULL
        AND previous_version >= 0 AND version = previous_version + 1)
    OR (action = 'assessment.baseline' AND previous_version IS NULL)
  ),
  CONSTRAINT assessment_events_sections_ck CHECK (
    cardinality(changed_sections) BETWEEN 0 AND 7
    AND array_position(changed_sections, NULL) IS NULL
    AND changed_sections <@ ARRAY['status', 'application', 'audience', 'protocols',
      'provisioning', 'security', 'operations']::text[]
  )
);

-- Web authentication events will have their own write boundary in a later slice.
REVOKE ALL ON audit.assessment_events FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT, INSERT ON audit.assessment_events TO authweave_core_runtime;

-- Older states were never stored. Capture only the current state, without inventing history.
INSERT INTO core.assessment_revisions (
  workspace_id, assessment_id, version, status, profile_schema_version, profile, origin
)
SELECT workspace_id, id, lock_version, status, profile_schema_version, profile, 'BASELINE'
FROM core.assessments;

INSERT INTO audit.assessment_events (
  id, workspace_id, assessment_id, version, action, actor_type, actor_id,
  correlation_id, outcome, changed_sections, occurred_at
)
SELECT gen_random_uuid(), workspace_id, assessment_id, version, 'assessment.baseline',
  'MIGRATION', 'flyway-v3', gen_random_uuid(), 'SUCCEEDED', ARRAY[]::text[], recorded_at
FROM core.assessment_revisions;

COMMENT ON TABLE core.assessment_revisions IS
  'Immutable assessment snapshots. BASELINE captures an existing state, not its earlier history.';
COMMENT ON TABLE audit.assessment_events IS
  'One committed state-change event per assessment revision; runtime cannot update or delete events.';
COMMENT ON COLUMN audit.assessment_events.changed_sections IS
  'Names of changed sections only; no profile values, credentials or raw input.';
