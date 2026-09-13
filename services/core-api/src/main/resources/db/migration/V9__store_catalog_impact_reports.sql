-- Historical conditional scenario reports only; no curator decision or catalog activation.
ALTER TABLE core.catalog_proposal_revisions ADD CONSTRAINT catalog_proposal_revision_digest_uk
  UNIQUE (proposal_id, version, proposal_sha256);

CREATE TABLE core.catalog_impact_reports (
  id uuid PRIMARY KEY,
  report_number bigint GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991) NOT NULL UNIQUE,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL,
  proposal_sha256 varchar(64) NOT NULL,
  report_schema_version smallint NOT NULL CHECK (report_schema_version = 1),
  canonicalization_version varchar(64) NOT NULL CHECK (canonicalization_version = 'catalog-draft-canonical-json-1'),
  report_sha256 varchar(64) NOT NULL CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
  report jsonb NOT NULL CHECK (jsonb_typeof(report) = 'object'),
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_impact_revision_fk FOREIGN KEY (proposal_id, proposal_version, proposal_sha256)
    REFERENCES core.catalog_proposal_revisions(proposal_id, version, proposal_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_impact_event_binding_uk UNIQUE (id, proposal_id, proposal_version, report_sha256),
  CONSTRAINT catalog_impact_report_boundary_ck CHECK ((
    report->>'scope' = 'CATALOG_PROFILE_SCENARIO_IMPACT'
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
    AND jsonb_typeof(report->'profilePolicyVersion') = 'string' AND length(report->>'profilePolicyVersion') BETWEEN 1 AND 100
    AND jsonb_typeof(report->'caseSetVersion') = 'string' AND length(report->>'caseSetVersion') BETWEEN 1 AND 100
    AND report->>'caseSetSha256' ~ '^[0-9a-f]{64}$'
    AND jsonb_typeof(report->'evaluatedAt') = 'string'
    AND jsonb_typeof(report->'scenarioDefinitions') = 'array'
    AND jsonb_typeof(report->'scenarios') = 'array' AND jsonb_typeof(report->'uncoveredChanges') = 'array'
    AND ((report->>'status' = 'ANALYZED' AND report->'impactAnalysisPerformed' = 'true'::jsonb
          AND report->'hypotheticalEvaluationPerformed' = 'true'::jsonb)
      OR (report->>'status' = 'BLOCKED' AND report->'impactAnalysisPerformed' = 'false'::jsonb
          AND report->'hypotheticalEvaluationPerformed' = 'false'::jsonb
          AND report->'scenarios' = '[]'::jsonb AND report->'uncoveredChanges' = '[]'::jsonb))
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
CREATE INDEX catalog_impact_revision_page_idx ON core.catalog_impact_reports (proposal_id, proposal_version, report_number);

CREATE TABLE audit.catalog_impact_report_events (
  id uuid PRIMARY KEY,
  report_id uuid NOT NULL UNIQUE,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL,
  report_sha256 varchar(64) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'catalog-impact.recorded'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'SERVICE'),
  actor_id varchar(64) NOT NULL CHECK (actor_id = 'core-api-local-catalog'),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_impact_event_report_fk FOREIGN KEY (report_id, proposal_id, proposal_version, report_sha256)
    REFERENCES core.catalog_impact_reports (id, proposal_id, proposal_version, report_sha256) ON DELETE RESTRICT
);
-- A report without its matching event cannot commit, including through direct runtime SQL.
ALTER TABLE core.catalog_impact_reports ADD CONSTRAINT catalog_impact_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_impact_report_events (report_id) DEFERRABLE INITIALLY DEFERRED;

REVOKE ALL ON core.catalog_impact_reports, audit.catalog_impact_report_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
REVOKE ALL ON SEQUENCE core.catalog_impact_reports_report_number_seq FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT, INSERT ON core.catalog_impact_reports, audit.catalog_impact_report_events TO authweave_core_runtime;
GRANT USAGE ON SEQUENCE core.catalog_impact_reports_report_number_seq TO authweave_core_runtime;

COMMENT ON TABLE core.catalog_impact_reports IS
  'Immutable conditional scenario snapshots bound to an exact proposal revision. Not approval or active catalog evidence.';
COMMENT ON TABLE audit.catalog_impact_report_events IS
  'Atomic report-save events from a local service, not an authenticated human. No raw source or profile text.';
