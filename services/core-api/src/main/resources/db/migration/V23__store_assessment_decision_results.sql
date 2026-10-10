-- Result versions are independent of the assessment's profile optimistic-lock version.
ALTER TABLE core.personal_workspaces ADD CONSTRAINT personal_workspace_actor_uk UNIQUE (workspace_id, issuer, subject);

CREATE TABLE core.assessment_decision_results (
  id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL,
  assessment_id uuid NOT NULL,
  version bigint NOT NULL CHECK (version BETWEEN 1 AND 9007199254740991),
  assessment_version bigint NOT NULL CHECK (assessment_version BETWEEN 0 AND 9007199254740991),
  snapshot_id uuid NOT NULL,
  catalog_version varchar(100) NOT NULL,
  snapshot_sha256 varchar(64) NOT NULL,
  request_sha256 varchar(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  result_sha256 varchar(64) NOT NULL CHECK (result_sha256 ~ '^[0-9a-f]{64}$'),
  previous_result_id uuid,
  previous_result_version bigint,
  previous_result_sha256 varchar(64),
  result jsonb NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  CONSTRAINT assessment_result_version_uk UNIQUE (workspace_id, assessment_id, version),
  CONSTRAINT assessment_result_reference_uk UNIQUE (workspace_id, assessment_id, id, version, result_sha256),
  CONSTRAINT assessment_result_event_uk UNIQUE (id, workspace_id, assessment_id, version, request_sha256, result_sha256),
  CONSTRAINT assessment_result_profile_fk FOREIGN KEY (workspace_id, assessment_id, assessment_version)
    REFERENCES core.assessment_revisions (workspace_id, assessment_id, version) ON DELETE RESTRICT,
  CONSTRAINT assessment_result_catalog_fk FOREIGN KEY (snapshot_id, catalog_version, snapshot_sha256)
    REFERENCES core.catalog_published_snapshots (id, catalog_version, snapshot_sha256) ON DELETE RESTRICT,
  CONSTRAINT assessment_result_previous_fk FOREIGN KEY (workspace_id, assessment_id, previous_result_id, previous_result_version, previous_result_sha256)
    REFERENCES core.assessment_decision_results (workspace_id, assessment_id, id, version, result_sha256) ON DELETE RESTRICT,
  CONSTRAINT assessment_result_chain_ck CHECK (
    (version = 1 AND previous_result_id IS NULL AND previous_result_version IS NULL AND previous_result_sha256 IS NULL)
    OR (version > 1 AND previous_result_id IS NOT NULL AND previous_result_version IS NOT NULL AND previous_result_version = version - 1
      AND previous_result_sha256 IS NOT NULL)
  ),
  CONSTRAINT assessment_result_boundary_ck CHECK ((
    jsonb_typeof(result) = 'object' AND octet_length(result::text) BETWEEN 1 AND 33554432
    AND result->>'scope' = 'PINNED_ASSESSMENT_DECISION_ADVICE' AND result->'schemaVersion' = '1'::jsonb
    AND result->>'resultVersion' = 'assessment-decision-result-1'
    AND result->>'canonicalization' = 'SHA256_UTF8_COMPACT_JSON_SORTED_OBJECT_KEYS_PRESERVED_ARRAY_ORDER'
    AND result->>'workspaceId' = workspace_id::text AND result->>'assessmentId' = assessment_id::text
    AND result->'version' = to_jsonb(version) AND result->>'requestSha256' = request_sha256
    AND result->'request'->>'resultId' = id::text AND result->'request'->'expectedAssessmentVersion' = to_jsonb(assessment_version)
    AND result->'request'->'schemaVersion' = '1'::jsonb
    AND result->'request'->'catalog'->>'snapshotId' = snapshot_id::text
    AND result->'request'->'catalog'->>'catalogVersion' = catalog_version
    AND result->'request'->'catalog'->>'snapshotSha256' = snapshot_sha256
    AND ((version = 1 AND result->'request'->'previousResult' = 'null'::jsonb
      AND result->'request'->>'confirmation' = 'RECORD_DECISION_RESULT')
      OR (version > 1 AND result->'request'->'previousResult'->>'resultId' = previous_result_id::text
        AND result->'request'->'previousResult'->'version' = to_jsonb(previous_result_version)
        AND result->'request'->'previousResult'->>'resultSha256' = previous_result_sha256
        AND result->'request'->>'confirmation' = 'REEVALUATE_DECISION_RESULT'))
    AND jsonb_typeof(result->'evaluatedAt') = 'string' AND jsonb_typeof(result->'profile') = 'object'
    AND result->'profileSchemaVersion' IN ('1'::jsonb, '2'::jsonb, '3'::jsonb, '4'::jsonb, '5'::jsonb, '6'::jsonb)
    AND jsonb_typeof(result->'evaluationProfile') = 'object' AND jsonb_typeof(result->'request'->'weights') = 'object'
    AND result->'historicalPublicationWorkflowVerified' = 'true'::jsonb
    AND result->'externalSourceVerificationPerformed' = 'false'::jsonb
    AND result->'configurationVerified' = 'false'::jsonb AND result->'complianceVerified' = 'false'::jsonb
    AND result->'decisionApproved' = 'false'::jsonb
    AND result->'decision'->'sourceAuthorityVerified' = 'false'::jsonb
    AND result->'decision'->'publicationReady' = 'false'::jsonb AND result->'decision'->'writesPerformed' = 'false'::jsonb
  ) IS TRUE)
);

CREATE TABLE audit.assessment_decision_result_events (
  id uuid PRIMARY KEY,
  result_id uuid NOT NULL UNIQUE,
  workspace_id uuid NOT NULL,
  assessment_id uuid NOT NULL,
  version bigint NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  result_sha256 varchar(64) NOT NULL,
  issuer varchar(2048) NOT NULL,
  subject varchar(256) NOT NULL,
  action varchar(48) NOT NULL,
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
  CONSTRAINT assessment_result_action_ck CHECK (
    (version = 1 AND action = 'assessment-decision.recorded')
    OR (version > 1 AND action = 'assessment-decision.reevaluated')
  ),
  CONSTRAINT assessment_result_event_result_fk FOREIGN KEY (result_id, workspace_id, assessment_id, version, request_sha256, result_sha256)
    REFERENCES core.assessment_decision_results (id, workspace_id, assessment_id, version, request_sha256, result_sha256) ON DELETE RESTRICT,
  CONSTRAINT assessment_result_actor_fk FOREIGN KEY (workspace_id, issuer, subject)
    REFERENCES core.personal_workspaces (workspace_id, issuer, subject) ON DELETE RESTRICT
);
ALTER TABLE core.assessment_decision_results ADD CONSTRAINT assessment_result_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.assessment_decision_result_events (result_id) DEFERRABLE INITIALLY DEFERRED;

-- Serialize result appends with profile edits, including callers bypassing the Java service.
CREATE FUNCTION core.check_assessment_decision_result() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $guard$
DECLARE head core.assessments%ROWTYPE; last_version bigint;
BEGIN
  SELECT * INTO head FROM core.assessments
    WHERE workspace_id = NEW.workspace_id AND id = NEW.assessment_id FOR UPDATE;
  IF NOT FOUND OR head.status = 'ARCHIVED' OR head.lock_version <> NEW.assessment_version
    OR head.profile IS DISTINCT FROM NEW.result->'profile'
    OR to_jsonb(head.profile_schema_version) IS DISTINCT FROM NEW.result->'profileSchemaVersion'
    OR NOT EXISTS (SELECT 1 FROM core.assessment_revisions r
      WHERE r.workspace_id = head.workspace_id AND r.assessment_id = head.id AND r.version = head.lock_version
        AND r.profile = head.profile AND r.profile_schema_version = head.profile_schema_version AND r.status = head.status) THEN
    RAISE EXCEPTION 'Assessment result requires the exact current recorded profile';
  END IF;
  SELECT coalesce(max(version), 0) INTO last_version FROM core.assessment_decision_results
    WHERE workspace_id = NEW.workspace_id AND assessment_id = NEW.assessment_id;
  IF NEW.version <> last_version + 1 THEN RAISE EXCEPTION 'Assessment result requires the exact latest predecessor'; END IF;
  RETURN NEW;
END
$guard$;
CREATE TRIGGER assessment_result_current_profile BEFORE INSERT ON core.assessment_decision_results
  FOR EACH ROW EXECUTE FUNCTION core.check_assessment_decision_result();
REVOKE ALL ON FUNCTION core.check_assessment_decision_result() FROM PUBLIC;

REVOKE ALL ON core.assessment_decision_results, audit.assessment_decision_result_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.assessment_decision_results, audit.assessment_decision_result_events TO authweave_core_runtime;
GRANT INSERT (id, workspace_id, assessment_id, version, assessment_version, snapshot_id, catalog_version, snapshot_sha256,
  request_sha256, result_sha256, previous_result_id, previous_result_version, previous_result_sha256, result)
  ON core.assessment_decision_results TO authweave_core_runtime;
GRANT INSERT (id, result_id, workspace_id, assessment_id, version, request_sha256, result_sha256, issuer, subject, action, correlation_id, outcome)
  ON audit.assessment_decision_result_events TO authweave_core_runtime;
COMMENT ON TABLE core.assessment_decision_results IS
  'Immutable owned assessment decision advice. Explicit exact catalog, profile revision, weights, policy and clock; no decision approval or automatic head activation.';
COMMENT ON TABLE audit.assessment_decision_result_events IS
  'Mandatory body-free authenticated-owner result append receipt. Runtime cannot rewrite results, events or timestamps.';
