-- Unreviewed proposal history only. These tables are not a published provider catalog.
CREATE TABLE core.catalog_proposals (
  id uuid PRIMARY KEY,
  version bigint NOT NULL CHECK (version BETWEEN 0 AND 9007199254740991),
  created_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamp with time zone NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE core.catalog_proposal_revisions (
  proposal_id uuid NOT NULL REFERENCES core.catalog_proposals(id) ON DELETE RESTRICT,
  version bigint NOT NULL CHECK (version BETWEEN 0 AND 9007199254740991),
  state varchar(16) NOT NULL CHECK (state = 'PROPOSED'),
  request_schema_version smallint NOT NULL CHECK (request_schema_version = 1),
  proposal_sha256 varchar(64) NOT NULL CHECK (proposal_sha256 ~ '^[0-9a-f]{64}$'),
  request jsonb NOT NULL CHECK (jsonb_typeof(request) = 'object'),
  preview jsonb NOT NULL CHECK (jsonb_typeof(preview) = 'object'),
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_proposal_revisions_pk PRIMARY KEY (proposal_id, version),
  CONSTRAINT catalog_proposal_request_binding_ck CHECK ((
    request->>'proposalId' = proposal_id::text AND request->>'schemaVersion' = '1'
  ) IS TRUE),
  CONSTRAINT catalog_proposal_preview_boundary_ck CHECK ((
    preview->>'proposalId' = proposal_id::text AND preview->>'proposalSha256' = proposal_sha256
    AND preview->>'scope' = 'CATALOG_CHANGE_PREVIEW' AND preview->>'proposalState' = 'PROPOSED'
    AND preview->>'status' = 'REVIEW_REQUIRED' AND preview->'diffComputed' = 'true'::jsonb
    AND preview->'baselineVerified' = 'false'::jsonb AND preview->'sourceVerificationPerformed' = 'false'::jsonb
    AND preview->'approvalGranted' = 'false'::jsonb AND preview->'writesPerformed' = 'false'::jsonb
    AND preview->'evaluationReady' = 'false'::jsonb AND preview->'impactAnalysisPerformed' = 'false'::jsonb
  ) IS TRUE)
);

-- A committed head must reference a real immutable revision, including on creation.
ALTER TABLE core.catalog_proposals ADD CONSTRAINT catalog_proposals_current_revision_fk
  FOREIGN KEY (id, version) REFERENCES core.catalog_proposal_revisions(proposal_id, version)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE audit.catalog_proposal_events (
  id uuid NOT NULL UNIQUE,
  proposal_id uuid NOT NULL,
  version bigint NOT NULL,
  previous_version bigint,
  action varchar(40) NOT NULL,
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'SERVICE'),
  actor_id varchar(64) NOT NULL CHECK (actor_id = 'core-api-local-catalog'),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  proposal_sha256 varchar(64) NOT NULL CHECK (proposal_sha256 ~ '^[0-9a-f]{64}$'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_proposal_events_pk PRIMARY KEY (proposal_id, version),
  CONSTRAINT catalog_proposal_events_revision_fk FOREIGN KEY (proposal_id, version)
    REFERENCES core.catalog_proposal_revisions(proposal_id, version) ON DELETE RESTRICT,
  CONSTRAINT catalog_proposal_events_transition_ck CHECK (
    (action = 'catalog-proposal.created' AND version = 0 AND previous_version IS NULL)
    OR (action = 'catalog-proposal.revised' AND previous_version IS NOT NULL
        AND previous_version >= 0 AND version = previous_version + 1)
  )
);

REVOKE ALL ON core.catalog_proposals, core.catalog_proposal_revisions,
  audit.catalog_proposal_events FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT, INSERT ON core.catalog_proposals, core.catalog_proposal_revisions,
  audit.catalog_proposal_events TO authweave_core_runtime;
GRANT UPDATE (version, updated_at) ON core.catalog_proposals TO authweave_core_runtime;

COMMENT ON TABLE core.catalog_proposals IS
  'Local unreviewed proposal heads, not catalog activation, approval or authenticated ownership.';
COMMENT ON TABLE core.catalog_proposal_revisions IS
  'Immutable request and preview snapshots. Freshness is historical, never recomputed when reading.';
COMMENT ON TABLE audit.catalog_proposal_events IS
  'Atomic proposal-write events attributed to the local service, not a verified human curator. No raw proposal text.';
