-- First-candidate receipts bind a real source review, never a fabricated proposal/baseline.
CREATE TABLE core.catalog_bootstrap_impact_reports (
  id uuid PRIMARY KEY,
  report_number bigint GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991) NOT NULL UNIQUE,
  review_id uuid NOT NULL,
  candidate_sha256 varchar(64) NOT NULL,
  review_sha256 varchar(64) NOT NULL,
  report_schema_version smallint NOT NULL CHECK (report_schema_version = 1),
  canonicalization_version varchar(64) NOT NULL CHECK (canonicalization_version = 'catalog-draft-canonical-json-1'),
  report_sha256 varchar(64) NOT NULL CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
  report jsonb NOT NULL,
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  CONSTRAINT catalog_bootstrap_impact_review_fk FOREIGN KEY (review_id, candidate_sha256, review_sha256)
    REFERENCES core.catalog_bootstrap_reviews (id, candidate_sha256, review_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_bootstrap_impact_event_binding_uk UNIQUE (id, review_id, candidate_sha256, review_sha256, report_sha256),
  CONSTRAINT catalog_bootstrap_impact_boundary_ck CHECK ((
    jsonb_typeof(report) = 'object' AND octet_length(report::text) BETWEEN 1 AND 33554432
    AND report->>'scope' = 'CATALOG_BOOTSTRAP_IMPACT'
    AND report->>'analysisBasis' = 'ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS'
    AND report->>'reviewId' = review_id::text AND report->>'reviewSha256' = review_sha256
    AND report->>'candidateSha256' = candidate_sha256
    AND report->'reportSchemaVersion' = to_jsonb(report_schema_version)
    AND report->>'canonicalizationVersion' = canonicalization_version
    AND NOT (report ?| ARRAY['proposalId', 'storedProposalVersion', 'base', 'changePreview'])
    AND report->'storedReportVerified' = 'false'::jsonb AND report->'coverageComplete' = 'false'::jsonb
    AND report->'baselineVerified' = 'false'::jsonb AND report->'sourceVerificationPerformed' = 'false'::jsonb
    AND report->'approvalGranted' = 'false'::jsonb AND report->'writesPerformed' = 'false'::jsonb
    AND report->'evaluationReady' = 'false'::jsonb AND report->'recommendationReady' = 'false'::jsonb
    AND jsonb_typeof(report->'policyVersion') = 'string' AND length(report->>'policyVersion') BETWEEN 1 AND 100
    AND jsonb_typeof(report->'ruleVersion') = 'string' AND length(report->>'ruleVersion') BETWEEN 1 AND 100
    AND jsonb_typeof(report->'profilePolicyVersion') = 'string' AND length(report->>'profilePolicyVersion') BETWEEN 1 AND 100
    AND jsonb_typeof(report->'caseSetVersion') = 'string' AND length(report->>'caseSetVersion') BETWEEN 1 AND 100
    AND jsonb_typeof(report->'scenarioSetVersion') = 'string' AND length(report->>'scenarioSetVersion') BETWEEN 1 AND 100
    AND report->>'caseSetSha256' ~ '^[0-9a-f]{64}$' AND report->>'scenarioSetSha256' ~ '^[0-9a-f]{64}$'
    AND jsonb_typeof(report->'evaluatedAt') = 'string'
    AND CASE WHEN jsonb_typeof(report->'caseDefinitions') = 'array' THEN jsonb_array_length(report->'caseDefinitions') = 68 ELSE false END
    AND CASE WHEN jsonb_typeof(report->'scenarioDefinitions') = 'array' THEN jsonb_array_length(report->'scenarioDefinitions') = 3 ELSE false END
    AND CASE WHEN jsonb_typeof(report->'cases') = 'array' THEN jsonb_array_length(report->'cases') <= 6800 ELSE false END
    AND CASE WHEN jsonb_typeof(report->'scenarios') = 'array' THEN jsonb_array_length(report->'scenarios') <= 300 ELSE false END
    AND CASE WHEN jsonb_typeof(report->'uncoveredFacts') = 'array' THEN jsonb_array_length(report->'uncoveredFacts') <= 6800 ELSE false END
    AND CASE WHEN jsonb_typeof(report->'scenarioUncoveredFacts') = 'array' THEN jsonb_array_length(report->'scenarioUncoveredFacts') <= 6800 ELSE false END
    AND ((report->>'status' = 'ANALYZED' AND report->'impactAnalysisPerformed' = 'true'::jsonb
          AND report->'hypotheticalEvaluationPerformed' = 'true'::jsonb)
      OR (report->>'status' = 'BLOCKED' AND report->'impactAnalysisPerformed' = 'false'::jsonb
          AND report->'hypotheticalEvaluationPerformed' = 'false'::jsonb
          AND report->'cases' = '[]'::jsonb AND report->'scenarios' = '[]'::jsonb
          AND report->'uncoveredFacts' = '[]'::jsonb AND report->'scenarioUncoveredFacts' = '[]'::jsonb))
  ) IS TRUE)
);
CREATE INDEX catalog_bootstrap_impact_review_latest_idx ON core.catalog_bootstrap_impact_reports (review_id, report_number DESC);

CREATE TABLE audit.catalog_bootstrap_impact_report_events (
  id uuid PRIMARY KEY,
  report_id uuid NOT NULL UNIQUE,
  review_id uuid NOT NULL,
  candidate_sha256 varchar(64) NOT NULL,
  review_sha256 varchar(64) NOT NULL,
  report_sha256 varchar(64) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'catalog-bootstrap-impact.recorded'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'SERVICE'),
  actor_id varchar(64) NOT NULL CHECK (actor_id = 'core-api-local-catalog'),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
  CONSTRAINT catalog_bootstrap_impact_event_report_fk FOREIGN KEY (report_id, review_id, candidate_sha256, review_sha256, report_sha256)
    REFERENCES core.catalog_bootstrap_impact_reports (id, review_id, candidate_sha256, review_sha256, report_sha256) ON DELETE RESTRICT
);
ALTER TABLE core.catalog_bootstrap_impact_reports ADD CONSTRAINT catalog_bootstrap_impact_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_bootstrap_impact_report_events (report_id) DEFERRABLE INITIALLY DEFERRED;

-- Share the empty-registry boundary even for direct runtime SQL and reserved publication writers.
CREATE FUNCTION core.bind_catalog_bootstrap_impact() RETURNS trigger
  LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $function$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('authweave:catalog-bootstrap-boundary', 0));
  IF EXISTS (SELECT 1 FROM core.catalog_published_snapshots)
    OR EXISTS (SELECT 1 FROM core.catalog_publication_decisions)
    OR EXISTS (SELECT 1 FROM audit.catalog_publication_events) THEN
    RAISE EXCEPTION 'Bootstrap registry is not empty' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER bind_catalog_bootstrap_impact BEFORE INSERT ON core.catalog_bootstrap_impact_reports
  FOR EACH ROW EXECUTE FUNCTION core.bind_catalog_bootstrap_impact();
REVOKE ALL ON FUNCTION core.bind_catalog_bootstrap_impact() FROM PUBLIC, authweave_web_runtime;
GRANT EXECUTE ON FUNCTION core.bind_catalog_bootstrap_impact() TO authweave_core_runtime;

REVOKE ALL ON core.catalog_bootstrap_impact_reports, audit.catalog_bootstrap_impact_report_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
REVOKE ALL ON SEQUENCE core.catalog_bootstrap_impact_reports_report_number_seq FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_bootstrap_impact_reports, audit.catalog_bootstrap_impact_report_events TO authweave_core_runtime;
GRANT INSERT (id, review_id, candidate_sha256, review_sha256, report_schema_version, canonicalization_version, report_sha256, report)
  ON core.catalog_bootstrap_impact_reports TO authweave_core_runtime;
GRANT INSERT (id, report_id, review_id, candidate_sha256, review_sha256, report_sha256, action, actor_type, actor_id, correlation_id, outcome)
  ON audit.catalog_bootstrap_impact_report_events TO authweave_core_runtime;
GRANT USAGE ON SEQUENCE core.catalog_bootstrap_impact_reports_report_number_seq TO authweave_core_runtime;
COMMENT ON TABLE core.catalog_bootstrap_impact_reports IS
  'Immutable exact-review candidate-only conditional analysis. No source, profile coverage, approval or publication authority.';
COMMENT ON TABLE audit.catalog_bootstrap_impact_report_events IS
  'Mandatory body-free local service receipt; separate from the human bootstrap source-review assertion.';
