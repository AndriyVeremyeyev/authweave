-- Reserve an immutable human rejection record. Approval and catalog activation remain unavailable.
CREATE TABLE core.catalog_proposal_decisions (
  id uuid PRIMARY KEY,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL CHECK (proposal_version BETWEEN 0 AND 9007199254740991),
  proposal_sha256 varchar(64) NOT NULL CHECK (proposal_sha256 ~ '^[0-9a-f]{64}$'),
  decision varchar(16) NOT NULL CHECK (decision = 'REJECTED'),
  reason_code varchar(40) NOT NULL CHECK (reason_code IN (
    'INSUFFICIENT_EVIDENCE', 'INACCURATE_FACTS', 'OUT_OF_SCOPE', 'OTHER'
  )),
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_proposal_decision_once_uk UNIQUE (proposal_id, proposal_version),
  CONSTRAINT catalog_proposal_decision_revision_fk
    FOREIGN KEY (proposal_id, proposal_version, proposal_sha256)
    REFERENCES core.catalog_proposal_revisions (proposal_id, version, proposal_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_proposal_decision_event_binding_uk
    UNIQUE (id, proposal_id, proposal_version, proposal_sha256, decision)
);

CREATE TABLE audit.catalog_proposal_decision_events (
  id uuid PRIMARY KEY,
  decision_id uuid NOT NULL UNIQUE,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL,
  proposal_sha256 varchar(64) NOT NULL,
  decision varchar(16) NOT NULL CHECK (decision = 'REJECTED'),
  action varchar(40) NOT NULL CHECK (action = 'catalog-proposal.rejected'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'CURATOR'),
  actor_issuer varchar(2048) NOT NULL CHECK (length(btrim(actor_issuer)) BETWEEN 1 AND 2048),
  actor_subject varchar(256) NOT NULL CHECK (length(btrim(actor_subject)) BETWEEN 1 AND 256),
  actor_project_id varchar(40) NOT NULL CHECK (actor_project_id ~ '^[0-9]{1,40}$'),
  actor_org_id varchar(40) NOT NULL CHECK (actor_org_id ~ '^[0-9]{1,40}$'),
  authenticated_at timestamp with time zone NOT NULL,
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_proposal_decision_event_decision_fk
    FOREIGN KEY (decision_id, proposal_id, proposal_version, proposal_sha256, decision)
    REFERENCES core.catalog_proposal_decisions (id, proposal_id, proposal_version, proposal_sha256, decision)
    ON DELETE RESTRICT,
  CONSTRAINT catalog_proposal_decision_event_freshness_ck CHECK (
    authenticated_at >= occurred_at - interval '15 minutes'
    AND authenticated_at <= occurred_at + interval '30 seconds'
  )
);

-- A rejection cannot commit without its matching audit event, including through direct runtime SQL.
ALTER TABLE core.catalog_proposal_decisions ADD CONSTRAINT catalog_proposal_decision_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_proposal_decision_events (decision_id)
  DEFERRABLE INITIALLY DEFERRED;

REVOKE ALL ON core.catalog_proposal_decisions, audit.catalog_proposal_decision_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_proposal_decisions, audit.catalog_proposal_decision_events
  TO authweave_core_runtime;
GRANT INSERT (id, proposal_id, proposal_version, proposal_sha256, decision, reason_code)
  ON core.catalog_proposal_decisions TO authweave_core_runtime;
GRANT INSERT (id, decision_id, proposal_id, proposal_version, proposal_sha256, decision,
  action, actor_type, actor_issuer, actor_subject, actor_project_id, actor_org_id,
  authenticated_at, correlation_id, outcome)
  ON audit.catalog_proposal_decision_events TO authweave_core_runtime;

COMMENT ON TABLE core.catalog_proposal_decisions IS
  'Immutable rejection of one exact proposal revision; not approval or active catalog state.';
COMMENT ON TABLE audit.catalog_proposal_decision_events IS
  'Atomic verified-curator rejection event. No rationale, source text, token or cookie.';
