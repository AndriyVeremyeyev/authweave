-- Runtime keeps SELECT-only registry privileges. One narrowly scoped function writes the atomic bootstrap bundle.
CREATE TABLE core.catalog_bootstrap_publications (
  id uuid PRIMARY KEY REFERENCES core.catalog_published_snapshots(id) ON DELETE RESTRICT,
  decision_id uuid NOT NULL UNIQUE REFERENCES core.catalog_publication_decisions(id) ON DELETE RESTRICT,
  request_sha256 varchar(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  review_id uuid NOT NULL,
  candidate_sha256 varchar(64) NOT NULL,
  review_sha256 varchar(64) NOT NULL,
  proof_sha256 varchar(64) NOT NULL CHECK (proof_sha256 ~ '^[0-9a-f]{64}$'),
  proof jsonb NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  CONSTRAINT catalog_bootstrap_publication_review_fk FOREIGN KEY (review_id, candidate_sha256, review_sha256)
    REFERENCES core.catalog_bootstrap_reviews(id, candidate_sha256, review_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_bootstrap_publication_proof_ck CHECK ((
    jsonb_typeof(proof) = 'object' AND octet_length(proof::text) BETWEEN 1 AND 33554432
    AND proof ?& ARRAY['policyVersion', 'request', 'requestSha256', 'snapshot', 'decisionId', 'publishedAt', 'coverage']
    AND (proof - ARRAY['policyVersion', 'request', 'requestSha256', 'snapshot', 'decisionId', 'publishedAt', 'coverage']) = '{}'::jsonb
    AND proof->>'policyVersion' = 'catalog-bootstrap-publication-1'
    AND proof->>'requestSha256' = request_sha256
    AND proof->>'decisionId' = decision_id::text
    AND proof->'snapshot'->>'snapshotId' = id::text
    AND proof->'request'->>'publicationId' = id::text
    AND proof->'request'->'schemaVersion' = '1'::jsonb
    AND proof->'request'->>'confirmation' = 'PUBLISH_REVIEWED_BOOTSTRAP'
    AND proof->'request'->'source'->>'reviewId' = review_id::text
    AND proof->'request'->'source'->>'reviewSha256' = review_sha256
    AND proof->'coverage'->>'scope' = 'DECLARED_DECISION_RULES_ONLY'
    AND proof->'coverage'->>'policyVersion' = 'publication-decision-coverage-1'
    AND proof->'coverage'->>'status' = 'COMPLETE_DECLARED_SCOPE'
    AND proof->'coverage'->'decisionScopeCoverageComplete' = 'true'::jsonb
    AND proof->'coverage'->'storedSourceReviewsVerified' = 'true'::jsonb
    AND proof->'coverage'->'approvalGranted' = 'false'::jsonb
    AND proof->'coverage'->'before' = proof->'request'->'source'
    AND proof->'coverage'->'after' = proof->'request'->'source'
    AND (proof->'coverage'->>'evaluatedAt')::timestamptz = (proof->>'publishedAt')::timestamptz
  ) IS TRUE)
);
REVOKE ALL ON core.catalog_bootstrap_publications FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_bootstrap_publications TO authweave_core_runtime;

-- Core owns canonical hashes, full replay and source eligibility; SQL binds identities, time and all-or-nothing storage.
-- This function cannot authenticate OIDC. Its only caller is the protected, opt-in Core curator publisher.
CREATE FUNCTION core.publish_catalog_bootstrap(manifest jsonb, publication_proof jsonb, proof_digest text, actor jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $body$
DECLARE
  snapshot_id uuid := (manifest->>'snapshotId')::uuid;
  decision_id uuid := (manifest->'publication'->>'decisionId')::uuid;
  published_at timestamptz := (manifest->'publication'->>'publishedAt')::timestamptz;
  review core.catalog_bootstrap_reviews%ROWTYPE;
  at timestamptz;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('authweave:catalog-bootstrap-boundary', 0));
  at := clock_timestamp();
  IF EXISTS (SELECT 1 FROM core.catalog_published_snapshots)
    OR EXISTS (SELECT 1 FROM core.catalog_publication_decisions)
    OR EXISTS (SELECT 1 FROM audit.catalog_publication_events)
    OR EXISTS (SELECT 1 FROM core.catalog_bootstrap_publications) THEN
    RAISE EXCEPTION 'Bootstrap publication requires an empty registry' USING ERRCODE = '23505';
  END IF;
  SELECT * INTO review FROM core.catalog_bootstrap_reviews
    WHERE id = (publication_proof->'request'->'source'->>'reviewId')::uuid;
  IF review.id IS NULL OR review.review_sha256 <> publication_proof->'request'->'source'->>'reviewSha256'
    OR review.catalog_version <> manifest->'catalog'->>'catalogVersion'
    OR review.recorded_at > at + interval '30 seconds'
    OR EXISTS (SELECT 1 FROM jsonb_array_elements(review.request->'observations') o
      WHERE o->>'verdict' IS DISTINCT FROM 'SOURCE_SUPPORTS_CLAIM')
    OR NOT EXISTS (SELECT 1 FROM audit.catalog_bootstrap_review_events WHERE review_id = review.id)
    OR manifest->'previousSnapshot' IS DISTINCT FROM 'null'::jsonb
    OR publication_proof->'snapshot'->>'snapshotId' IS DISTINCT FROM snapshot_id::text
    OR publication_proof->'snapshot'->>'catalogVersion' IS DISTINCT FROM manifest->'catalog'->>'catalogVersion'
    OR publication_proof->'snapshot'->>'snapshotSha256' IS DISTINCT FROM manifest->>'snapshotSha256'
    OR publication_proof->>'decisionId' IS DISTINCT FROM decision_id::text
    OR (publication_proof->>'publishedAt')::timestamptz IS DISTINCT FROM published_at
    OR NOT (published_at BETWEEN at - interval '30 seconds' AND at + interval '30 seconds') THEN
    RAISE EXCEPTION 'Invalid bootstrap publication binding' USING ERRCODE = '23514';
  END IF;
  IF NOT ((jsonb_typeof(actor) = 'object'
    AND actor ?& ARRAY['issuer', 'subject', 'projectId', 'organizationId', 'authenticatedAt']
    AND (actor - ARRAY['issuer', 'subject', 'projectId', 'organizationId', 'authenticatedAt']) = '{}'::jsonb
    AND (actor->>'authenticatedAt')::timestamptz BETWEEN at - interval '15 minutes' AND at + interval '30 seconds') IS TRUE) THEN
    RAISE EXCEPTION 'Fresh curator assertion required' USING ERRCODE = '23514';
  END IF;
  INSERT INTO core.catalog_publication_decisions(id, snapshot_id, decision_kind, catalog_version,
    content_sha256, snapshot_sha256, published_at)
    VALUES(decision_id, snapshot_id, 'CURATED_BOOTSTRAP', manifest->'catalog'->>'catalogVersion',
      manifest->>'contentSha256', manifest->>'snapshotSha256', published_at);
  INSERT INTO core.catalog_published_snapshots(id, decision_id, decision_kind, catalog_version,
    content_sha256, snapshot_sha256, published_at, manifest)
    VALUES(snapshot_id, decision_id, 'CURATED_BOOTSTRAP', manifest->'catalog'->>'catalogVersion',
      manifest->>'contentSha256', manifest->>'snapshotSha256', published_at, manifest);
  INSERT INTO audit.catalog_publication_events(id, decision_id, snapshot_id, snapshot_sha256, decision_kind,
    action, actor_type, actor_issuer, actor_subject, actor_project_id, actor_org_id, authenticated_at, correlation_id, outcome)
    VALUES(gen_random_uuid(), decision_id, snapshot_id, manifest->>'snapshotSha256', 'CURATED_BOOTSTRAP',
      'catalog.published', 'CURATOR', actor->>'issuer', actor->>'subject', actor->>'projectId', actor->>'organizationId',
      (actor->>'authenticatedAt')::timestamptz, gen_random_uuid(), 'SUCCEEDED');
  INSERT INTO core.catalog_bootstrap_publications(id, decision_id, request_sha256, review_id,
    candidate_sha256, review_sha256, proof_sha256, proof)
    VALUES(snapshot_id, decision_id, publication_proof->>'requestSha256', review.id,
      review.candidate_sha256, review.review_sha256, proof_digest, publication_proof);
END;
$body$;
REVOKE ALL ON FUNCTION core.publish_catalog_bootstrap(jsonb, jsonb, text, jsonb) FROM PUBLIC, authweave_web_runtime;
GRANT EXECUTE ON FUNCTION core.publish_catalog_bootstrap(jsonb, jsonb, text, jsonb) TO authweave_core_runtime;
COMMENT ON TABLE core.catalog_bootstrap_publications IS
  'Immutable Core-replayed bootstrap provenance, separate from legacy incomplete impact receipts. Not evaluator activation.';
