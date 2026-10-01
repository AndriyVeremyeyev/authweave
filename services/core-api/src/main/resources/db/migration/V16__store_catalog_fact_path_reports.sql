-- Separate immutable required-rule regressions. Existing three-profile history is not migrated.
CREATE TABLE core.catalog_fact_path_reports (
  id uuid PRIMARY KEY,
  report_number bigint GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991) NOT NULL UNIQUE,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL,
  proposal_sha256 varchar(64) NOT NULL,
  report_schema_version smallint NOT NULL CHECK (report_schema_version = 1),
  canonicalization_version varchar(64) NOT NULL CHECK (canonicalization_version = 'catalog-draft-canonical-json-1'),
  report_sha256 varchar(64) NOT NULL CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
  report jsonb NOT NULL,
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  CONSTRAINT catalog_fact_path_revision_fk FOREIGN KEY (proposal_id, proposal_version, proposal_sha256)
    REFERENCES core.catalog_proposal_revisions(proposal_id, version, proposal_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_fact_path_event_binding_uk UNIQUE (id, proposal_id, proposal_version, report_sha256),
  CONSTRAINT catalog_fact_path_report_boundary_ck CHECK ((
    jsonb_typeof(report) = 'object' AND octet_length(report::text) BETWEEN 1 AND 33554432
    AND report->>'scope' = 'CATALOG_FACT_PATH_REGRESSION_IMPACT'
    AND report->>'analysisBasis' = 'ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS'
    AND report->>'proposalId' = proposal_id::text AND report->>'proposalSha256' = proposal_sha256
    AND report->'storedProposalVersion' = to_jsonb(proposal_version)
    AND report->'storedRequestDigestVerified' = 'true'::jsonb
    AND report->'coverageComplete' = 'false'::jsonb AND report->'baselineVerified' = 'false'::jsonb
    AND report->'sourceVerificationPerformed' = 'false'::jsonb AND report->'approvalGranted' = 'false'::jsonb
    AND report->'writesPerformed' = 'false'::jsonb AND report->'evaluationReady' = 'false'::jsonb
    AND report->'recommendationReady' = 'false'::jsonb
    AND jsonb_typeof(report->'policyVersion') = 'string' AND length(report->>'policyVersion') BETWEEN 1 AND 100
    AND jsonb_typeof(report->'ruleVersion') = 'string' AND length(report->>'ruleVersion') BETWEEN 1 AND 100
    AND jsonb_typeof(report->'caseSetVersion') = 'string' AND length(report->>'caseSetVersion') BETWEEN 1 AND 100
    AND report->>'caseSetSha256' ~ '^[0-9a-f]{64}$'
    AND jsonb_typeof(report->'evaluatedAt') = 'string'
    AND CASE WHEN jsonb_typeof(report->'caseDefinitions') = 'array' THEN jsonb_array_length(report->'caseDefinitions') = 68 ELSE false END
    AND CASE WHEN jsonb_typeof(report->'cases') = 'array' THEN jsonb_array_length(report->'cases') <= 13600 ELSE false END
    AND CASE WHEN jsonb_typeof(report->'uncoveredChanges') = 'array' THEN jsonb_array_length(report->'uncoveredChanges') <= 13600 ELSE false END
    AND ((report->>'status' = 'ANALYZED' AND report->'impactAnalysisPerformed' = 'true'::jsonb
          AND report->'hypotheticalEvaluationPerformed' = 'true'::jsonb)
      OR (report->>'status' = 'BLOCKED' AND report->'impactAnalysisPerformed' = 'false'::jsonb
          AND report->'hypotheticalEvaluationPerformed' = 'false'::jsonb
          AND report->'cases' = '[]'::jsonb AND report->'uncoveredChanges' = '[]'::jsonb))
    AND report #>> '{changePreview,proposalId}' = proposal_id::text
    AND report #>> '{changePreview,proposalSha256}' = proposal_sha256
    AND report #>> '{changePreview,proposalState}' = 'PROPOSED'
    AND report #> '{changePreview,approvalGranted}' = 'false'::jsonb
    AND report #> '{changePreview,baselineVerified}' = 'false'::jsonb
    AND report #> '{changePreview,sourceVerificationPerformed}' = 'false'::jsonb
    AND report #> '{changePreview,writesPerformed}' = 'false'::jsonb
    AND report #> '{changePreview,evaluationReady}' = 'false'::jsonb
    AND report #> '{changePreview,impactAnalysisPerformed}' = 'false'::jsonb
  ) IS TRUE)
);
CREATE INDEX catalog_fact_path_revision_latest_idx ON core.catalog_fact_path_reports (proposal_id, proposal_version, report_number DESC);

CREATE TABLE audit.catalog_fact_path_report_events (
  id uuid PRIMARY KEY,
  report_id uuid NOT NULL UNIQUE,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL,
  report_sha256 varchar(64) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'catalog-fact-path.recorded'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'SERVICE'),
  actor_id varchar(64) NOT NULL CHECK (actor_id = 'core-api-local-catalog'),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
  CONSTRAINT catalog_fact_path_event_report_fk FOREIGN KEY (report_id, proposal_id, proposal_version, report_sha256)
    REFERENCES core.catalog_fact_path_reports (id, proposal_id, proposal_version, report_sha256) ON DELETE RESTRICT
);
ALTER TABLE core.catalog_fact_path_reports ADD CONSTRAINT catalog_fact_path_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_fact_path_report_events (report_id) DEFERRABLE INITIALLY DEFERRED;

REVOKE ALL ON core.catalog_fact_path_reports, audit.catalog_fact_path_report_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
REVOKE ALL ON SEQUENCE core.catalog_fact_path_reports_report_number_seq FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_fact_path_reports, audit.catalog_fact_path_report_events TO authweave_core_runtime;
GRANT INSERT (id, proposal_id, proposal_version, proposal_sha256, report_schema_version, canonicalization_version, report_sha256, report)
  ON core.catalog_fact_path_reports TO authweave_core_runtime;
GRANT INSERT (id, report_id, proposal_id, proposal_version, report_sha256, action, actor_type, actor_id, correlation_id, outcome)
  ON audit.catalog_fact_path_report_events TO authweave_core_runtime;
GRANT USAGE ON SEQUENCE core.catalog_fact_path_reports_report_number_seq TO authweave_core_runtime;
COMMENT ON TABLE core.catalog_fact_path_reports IS
  'Exact-revision immutable conditional regressions of draft v1 fact addresses. Not full-profile evaluation, approval or publication.';
COMMENT ON TABLE audit.catalog_fact_path_report_events IS
  'Mandatory body-free local service receipt. No human/OIDC/source-authenticity assertion.';
