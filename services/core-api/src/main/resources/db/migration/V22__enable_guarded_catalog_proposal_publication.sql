-- First proposal successor of a verified bootstrap. No active catalog pointer or assessment write privilege.
CREATE TABLE core.catalog_proposal_publications (
  id uuid PRIMARY KEY REFERENCES core.catalog_published_snapshots(id) ON DELETE RESTRICT,
  decision_id uuid NOT NULL UNIQUE REFERENCES core.catalog_publication_decisions(id) ON DELETE RESTRICT,
  request_sha256 varchar(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL,
  proposal_sha256 varchar(64) NOT NULL,
  proof_sha256 varchar(64) NOT NULL CHECK (proof_sha256 ~ '^[0-9a-f]{64}$'),
  proof jsonb NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  UNIQUE (proposal_id, proposal_version),
  FOREIGN KEY (proposal_id, proposal_version, proposal_sha256)
    REFERENCES core.catalog_proposal_revisions(proposal_id, version, proposal_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_proposal_publication_proof_ck CHECK ((
    jsonb_typeof(proof) = 'object' AND octet_length(proof::text) BETWEEN 1 AND 33554432
    AND proof ?& ARRAY['policyVersion', 'request', 'requestSha256', 'snapshot', 'decisionId', 'publishedAt', 'coverage']
    AND (proof - ARRAY['policyVersion', 'request', 'requestSha256', 'snapshot', 'decisionId', 'publishedAt', 'coverage']) = '{}'::jsonb
    AND proof->>'policyVersion' = 'catalog-proposal-publication-1'
    AND proof->>'requestSha256' = request_sha256 AND proof->>'decisionId' = decision_id::text
    AND proof->'snapshot'->>'snapshotId' = id::text AND proof->'request'->>'publicationId' = id::text
    AND proof->'request'->'schemaVersion' = '1'::jsonb
    AND proof->'request'->>'confirmation' = 'PUBLISH_REVIEWED_PROPOSAL'
    AND proof->'request'->'proposal'->'revision'->>'proposalId' = proposal_id::text
    AND proof->'request'->'proposal'->'revision'->'version' = to_jsonb(proposal_version)
    AND proof->'request'->'proposal'->'revision'->>'proposalSha256' = proposal_sha256
    AND proof->'coverage'->>'scope' = 'DECLARED_DECISION_RULES_ONLY'
    AND proof->'coverage'->>'policyVersion' = 'publication-proposal-decision-coverage-1'
    AND proof->'coverage'->>'status' = 'COMPLETE_DECLARED_SCOPE'
    AND proof->'coverage'->'decisionScopeCoverageComplete' = 'true'::jsonb
    AND proof->'coverage'->'storedSourceReviewsVerified' = 'true'::jsonb
    AND proof->'coverage'->'historicalPublicationWorkflowVerified' = 'true'::jsonb
    AND proof->'coverage'->'candidateClaims'->'allRecordedClaimsSupportedAndCurrent' = 'true'::jsonb
    AND proof->'coverage'->'approvalGranted' = 'false'::jsonb
    AND proof->'coverage'->'before' = proof->'request'->'before'
    AND proof->'coverage'->'after' = proof->'request'->'proposal'
    AND (proof->'coverage'->>'evaluatedAt')::timestamptz = (proof->>'publishedAt')::timestamptz
  ) IS TRUE)
);
REVOKE ALL ON core.catalog_proposal_publications FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_proposal_publications TO authweave_core_runtime;

-- Review and rejection paths use the same head lock. A committed approval seals that revision's review ledger.
CREATE FUNCTION core.exclude_published_proposal_review() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $body$
BEGIN
  PERFORM 1 FROM core.catalog_proposals WHERE id = NEW.proposal_id FOR UPDATE;
  IF EXISTS (SELECT 1 FROM core.catalog_publication_decisions
      WHERE proposal_id = NEW.proposal_id AND proposal_version = NEW.proposal_version) THEN
    RAISE EXCEPTION 'Published proposal reviews are immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$body$;
REVOKE ALL ON FUNCTION core.exclude_published_proposal_review() FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
CREATE TRIGGER catalog_fact_review_publication_guard BEFORE INSERT ON core.catalog_fact_reviews
  FOR EACH ROW EXECUTE FUNCTION core.exclude_published_proposal_review();

-- Core owns canonical hashes, exact ordered source loading and full replay. SQL binds current state and atomic storage.
CREATE FUNCTION core.publish_catalog_proposal(manifest jsonb, publication_proof jsonb, proof_digest text, actor jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $body$
DECLARE
  snapshot_id uuid := (manifest->>'snapshotId')::uuid;
  decision_id uuid := (manifest->'publication'->>'decisionId')::uuid;
  published_at timestamptz := (manifest->'publication'->>'publishedAt')::timestamptz;
  pin jsonb := publication_proof->'request'->'proposal';
  parent_pin jsonb := publication_proof->'request'->'before';
  revision core.catalog_proposal_revisions%ROWTYPE;
  parent core.catalog_published_snapshots%ROWTYPE;
  head_version bigint;
  through_number bigint;
  at timestamptz;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('authweave:catalog-bootstrap-boundary', 0));
  SELECT version INTO head_version FROM core.catalog_proposals
    WHERE id = (pin->'revision'->>'proposalId')::uuid FOR UPDATE;
  at := clock_timestamp();
  SELECT * INTO revision FROM core.catalog_proposal_revisions
    WHERE proposal_id = (pin->'revision'->>'proposalId')::uuid AND version = (pin->'revision'->>'version')::bigint;
  SELECT * INTO parent FROM core.catalog_published_snapshots WHERE id = (parent_pin->>'snapshotId')::uuid;
  SELECT coalesce(max(review_number), 0) INTO through_number FROM core.catalog_fact_reviews
    WHERE proposal_id = revision.proposal_id AND proposal_version = revision.version;
  IF revision.proposal_id IS NULL OR head_version IS DISTINCT FROM revision.version OR revision.state <> 'PROPOSED'
    OR revision.proposal_sha256 IS DISTINCT FROM pin->'revision'->>'proposalSha256'
    OR revision.recorded_at > at + interval '30 seconds'
    OR EXISTS (SELECT 1 FROM core.catalog_proposal_decisions WHERE proposal_id = revision.proposal_id AND proposal_version = revision.version)
    OR EXISTS (SELECT 1 FROM core.catalog_publication_decisions WHERE proposal_id = revision.proposal_id AND proposal_version = revision.version)
    OR parent.id IS NULL OR parent.decision_kind <> 'CURATED_BOOTSTRAP'
    OR parent.catalog_version IS DISTINCT FROM parent_pin->>'catalogVersion'
    OR parent.snapshot_sha256 IS DISTINCT FROM parent_pin->>'snapshotSha256'
    OR NOT EXISTS (SELECT 1 FROM core.catalog_bootstrap_publications WHERE id = parent.id)
    OR EXISTS (SELECT 1 FROM core.catalog_published_snapshots WHERE previous_snapshot_id = parent.id)
    OR through_number IS DISTINCT FROM (pin->>'reviewThroughNumber')::bigint OR through_number = 0
    OR EXISTS (SELECT 1 FROM (
        SELECT DISTINCT ON (option_id, fact_path) verdict FROM core.catalog_fact_reviews
        WHERE proposal_id = revision.proposal_id AND proposal_version = revision.version
        ORDER BY option_id, fact_path, review_number DESC
      ) latest WHERE latest.verdict <> 'SOURCE_SUPPORTS_CLAIM')
    OR manifest->'catalog' IS DISTINCT FROM ((revision.request->'candidate') - 'kind')
    OR manifest->'previousSnapshot' IS DISTINCT FROM parent_pin
    OR publication_proof->'snapshot'->>'snapshotId' IS DISTINCT FROM snapshot_id::text
    OR publication_proof->'snapshot'->>'catalogVersion' IS DISTINCT FROM manifest->'catalog'->>'catalogVersion'
    OR publication_proof->'snapshot'->>'snapshotSha256' IS DISTINCT FROM manifest->>'snapshotSha256'
    OR publication_proof->>'decisionId' IS DISTINCT FROM decision_id::text
    OR (publication_proof->>'publishedAt')::timestamptz IS DISTINCT FROM published_at
    OR NOT (published_at BETWEEN at - interval '30 seconds' AND at + interval '30 seconds') THEN
    RAISE EXCEPTION 'Invalid current proposal publication binding' USING ERRCODE = '23514';
  END IF;
  IF NOT ((jsonb_typeof(actor) = 'object'
    AND actor ?& ARRAY['issuer', 'subject', 'projectId', 'organizationId', 'authenticatedAt']
    AND (actor - ARRAY['issuer', 'subject', 'projectId', 'organizationId', 'authenticatedAt']) = '{}'::jsonb
    AND (actor->>'authenticatedAt')::timestamptz BETWEEN at - interval '15 minutes' AND at + interval '30 seconds') IS TRUE) THEN
    RAISE EXCEPTION 'Fresh curator assertion required' USING ERRCODE = '23514';
  END IF;
  INSERT INTO core.catalog_publication_decisions(id, snapshot_id, decision_kind, proposal_id, proposal_version,
    proposal_sha256, catalog_version, content_sha256, snapshot_sha256, published_at)
    VALUES(decision_id, snapshot_id, 'PROPOSAL_APPROVAL', revision.proposal_id, revision.version, revision.proposal_sha256,
      manifest->'catalog'->>'catalogVersion', manifest->>'contentSha256', manifest->>'snapshotSha256', published_at);
  INSERT INTO core.catalog_published_snapshots(id, decision_id, decision_kind, catalog_version, content_sha256,
    snapshot_sha256, published_at, previous_snapshot_id, previous_catalog_version, previous_snapshot_sha256, manifest)
    VALUES(snapshot_id, decision_id, 'PROPOSAL_APPROVAL', manifest->'catalog'->>'catalogVersion', manifest->>'contentSha256',
      manifest->>'snapshotSha256', published_at, parent.id, parent.catalog_version, parent.snapshot_sha256, manifest);
  INSERT INTO audit.catalog_publication_events(id, decision_id, snapshot_id, snapshot_sha256, decision_kind, action,
    actor_type, actor_issuer, actor_subject, actor_project_id, actor_org_id, authenticated_at, correlation_id, outcome)
    VALUES(gen_random_uuid(), decision_id, snapshot_id, manifest->>'snapshotSha256', 'PROPOSAL_APPROVAL', 'catalog.published',
      'CURATOR', actor->>'issuer', actor->>'subject', actor->>'projectId', actor->>'organizationId',
      (actor->>'authenticatedAt')::timestamptz, gen_random_uuid(), 'SUCCEEDED');
  INSERT INTO core.catalog_proposal_publications(id, decision_id, request_sha256, proposal_id, proposal_version,
    proposal_sha256, proof_sha256, proof)
    VALUES(snapshot_id, decision_id, publication_proof->>'requestSha256', revision.proposal_id, revision.version,
      revision.proposal_sha256, proof_digest, publication_proof);
END;
$body$;
REVOKE ALL ON FUNCTION core.publish_catalog_proposal(jsonb, jsonb, text, jsonb) FROM PUBLIC, authweave_web_runtime;
GRANT EXECUTE ON FUNCTION core.publish_catalog_proposal(jsonb, jsonb, text, jsonb) TO authweave_core_runtime;
COMMENT ON TABLE core.catalog_proposal_publications IS
  'Immutable first-successor Core replay provenance. No external source certification, active head or assessment result.';
