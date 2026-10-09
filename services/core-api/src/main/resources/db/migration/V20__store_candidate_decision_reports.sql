-- Candidate-only calculation receipts. These do not replace assessment results or the publication registry.
CREATE TABLE core.candidate_decision_reports (
  id uuid PRIMARY KEY,
  input_sha256 varchar(64) NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
  report_sha256 varchar(64) NOT NULL CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
  before_review_id uuid NOT NULL REFERENCES core.catalog_bootstrap_reviews (id) ON DELETE RESTRICT,
  after_review_id uuid NOT NULL REFERENCES core.catalog_bootstrap_reviews (id) ON DELETE RESTRICT,
  before_audit_review_id uuid REFERENCES core.catalog_auditability_reviews (id) ON DELETE RESTRICT,
  after_audit_review_id uuid REFERENCES core.catalog_auditability_reviews (id) ON DELETE RESTRICT,
  report jsonb NOT NULL,
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  CONSTRAINT candidate_decision_event_binding_uk UNIQUE (id, input_sha256, report_sha256),
  CONSTRAINT candidate_decision_boundary_ck CHECK ((
    jsonb_typeof(report) = 'object' AND octet_length(report::text) BETWEEN 1 AND 33554432
    AND report->>'scope' = 'STORED_REVIEW_CANDIDATE_DECISION_IMPACT'
    AND report->'reportSchemaVersion' = '1'::jsonb AND report->>'reportVersion' = 'decision-stored-impact-report-1'
    AND report->>'canonicalization' = 'SHA256_UTF8_COMPACT_JSON_SORTED_OBJECT_KEYS_PRESERVED_ARRAY_ORDER'
    AND report->>'inputSha256' = input_sha256 AND jsonb_typeof(report->'evaluatedAt') = 'string'
    AND report->'request'->'profileSchemaVersion' = '6'::jsonb
    AND jsonb_typeof(report->'request'->'profile') = 'object' AND jsonb_typeof(report->'request'->'weights') = 'object'
    AND report->'request'->'before'->>'reviewId' = before_review_id::text
    AND report->'request'->'after'->>'reviewId' = after_review_id::text
    AND ((before_audit_review_id IS NULL AND report->'request'->'before'->'auditability' = 'null'::jsonb)
      OR report->'request'->'before'->'auditability'->>'reviewId' = before_audit_review_id::text)
    AND ((after_audit_review_id IS NULL AND report->'request'->'after'->'auditability' = 'null'::jsonb)
      OR report->'request'->'after'->'auditability'->>'reviewId' = after_audit_review_id::text)
    AND report->'storedReviewsVerified' = 'true'::jsonb
    AND report->'currentCuratorAuthorityVerified' = 'false'::jsonb AND report->'sourceVerificationPerformed' = 'false'::jsonb
    AND report->'configurationVerified' = 'false'::jsonb AND report->'complianceVerified' = 'false'::jsonb
    AND report->'approvalGranted' = 'false'::jsonb AND report->'publicationReady' = 'false'::jsonb AND report->'writesPerformed' = 'false'::jsonb
    AND report->'impact'->>'scope' = 'CANDIDATE_WHOLE_DECISION_IMPACT'
    AND report->'impact'->>'impactVersion' = 'decision-candidate-impact-1'
    AND report->'impact'->'evaluatedAt' = report->'evaluatedAt'
    AND report->'impact'->'coverageComplete' = 'false'::jsonb AND report->'impact'->'sourceVerificationPerformed' = 'false'::jsonb
    AND report->'impact'->'approvalGranted' = 'false'::jsonb AND report->'impact'->'publicationReady' = 'false'::jsonb
    AND report->'impact'->'writesPerformed' = 'false'::jsonb
    AND report->'impact'->'before'->'sourceAuthorityVerified' = 'false'::jsonb AND report->'impact'->'after'->'sourceAuthorityVerified' = 'false'::jsonb
    AND report->'impact'->'before'->'publicationReady' = 'false'::jsonb AND report->'impact'->'after'->'publicationReady' = 'false'::jsonb
    AND report->'impact'->'before'->'writesPerformed' = 'false'::jsonb AND report->'impact'->'after'->'writesPerformed' = 'false'::jsonb
    AND NOT (report ?| ARRAY['assessmentId', 'workspaceId', 'publishedSnapshotId'])
  ) IS TRUE)
);
CREATE TABLE audit.candidate_decision_report_events (
  id uuid PRIMARY KEY,
  report_id uuid NOT NULL UNIQUE,
  input_sha256 varchar(64) NOT NULL,
  report_sha256 varchar(64) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'candidate-decision.recorded'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'SERVICE'),
  actor_id varchar(64) NOT NULL CHECK (actor_id = 'core-api-local-catalog'),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
  CONSTRAINT candidate_decision_event_report_fk FOREIGN KEY (report_id, input_sha256, report_sha256)
    REFERENCES core.candidate_decision_reports (id, input_sha256, report_sha256) ON DELETE RESTRICT
);
ALTER TABLE core.candidate_decision_reports ADD CONSTRAINT candidate_decision_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.candidate_decision_report_events (report_id) DEFERRABLE INITIALLY DEFERRED;
REVOKE ALL ON core.candidate_decision_reports, audit.candidate_decision_report_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.candidate_decision_reports, audit.candidate_decision_report_events TO authweave_core_runtime;
GRANT INSERT (id, input_sha256, report_sha256, before_review_id, after_review_id, before_audit_review_id, after_audit_review_id, report)
  ON core.candidate_decision_reports TO authweave_core_runtime;
GRANT INSERT (id, report_id, input_sha256, report_sha256, action, actor_type, actor_id, correlation_id, outcome)
  ON audit.candidate_decision_report_events TO authweave_core_runtime;
COMMENT ON TABLE core.candidate_decision_reports IS
  'Immutable historical whole-decision candidate calculation; not a published catalog, assessment result or source approval.';
COMMENT ON TABLE audit.candidate_decision_report_events IS
  'Mandatory body-free local service receipt. No curator identity, source truth or publication authority.';
