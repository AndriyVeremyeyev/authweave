-- Immutable whole-candidate manual source reviews. Not approval or a published bootstrap.
CREATE TABLE core.catalog_bootstrap_reviews (
  id uuid PRIMARY KEY,
  candidate_sha256 varchar(64) NOT NULL CHECK (candidate_sha256 ~ '^[0-9a-f]{64}$'),
  review_sha256 varchar(64) NOT NULL CHECK (review_sha256 ~ '^[0-9a-f]{64}$'),
  request_schema_version smallint NOT NULL CHECK (request_schema_version = 1),
  policy_version varchar(80) NOT NULL CHECK (policy_version = 'catalog-bootstrap-source-review-1'),
  catalog_version varchar(100) NOT NULL CHECK (catalog_version ~ '^[a-z0-9][a-z0-9.-]{0,99}$'),
  fact_count integer NOT NULL CHECK (fact_count BETWEEN 1 AND 6800),
  request jsonb NOT NULL,
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  CONSTRAINT catalog_bootstrap_review_event_binding_uk UNIQUE (id, candidate_sha256, review_sha256),
  CONSTRAINT catalog_bootstrap_review_request_ck CHECK ((
    jsonb_typeof(request) = 'object'
    AND octet_length(request::text) BETWEEN 1 AND 33554432
    AND request ?& ARRAY['schemaVersion', 'reviewId', 'expectedCandidateSha256', 'candidate', 'observations', 'confirmation']
    AND (request - ARRAY['schemaVersion', 'reviewId', 'expectedCandidateSha256', 'candidate', 'observations', 'confirmation']) = '{}'::jsonb
    AND request->'schemaVersion' = '1'::jsonb
    AND request->>'reviewId' = id::text
    AND request->>'expectedCandidateSha256' = candidate_sha256
    AND request->>'confirmation' = 'MANUAL_BOOTSTRAP_SOURCE_REVIEW'
    AND jsonb_typeof(request->'candidate') = 'object'
    AND request->'candidate'->'schemaVersion' = '1'::jsonb
    AND request->'candidate'->>'kind' = 'PROVIDER_CATALOG_DRAFT'
    AND request->'candidate'->>'catalogVersion' = catalog_version
    AND CASE WHEN jsonb_typeof(request->'observations') = 'array'
      THEN jsonb_array_length(request->'observations') = fact_count ELSE false END
  ) IS TRUE)
);

CREATE TABLE audit.catalog_bootstrap_review_events (
  id uuid PRIMARY KEY,
  review_id uuid NOT NULL UNIQUE,
  candidate_sha256 varchar(64) NOT NULL,
  review_sha256 varchar(64) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'catalog-bootstrap.source-reviewed'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'CURATOR'),
  actor_issuer varchar(2048) NOT NULL CHECK (length(btrim(actor_issuer)) BETWEEN 1 AND 2048),
  actor_subject varchar(256) NOT NULL CHECK (length(btrim(actor_subject)) BETWEEN 1 AND 256),
  actor_project_id varchar(40) NOT NULL CHECK (actor_project_id ~ '^[0-9]{1,40}$'),
  actor_org_id varchar(40) NOT NULL CHECK (actor_org_id ~ '^[0-9]{1,40}$'),
  authenticated_at timestamp with time zone NOT NULL CHECK (isfinite(authenticated_at)),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
  CONSTRAINT catalog_bootstrap_review_event_review_fk FOREIGN KEY (review_id, candidate_sha256, review_sha256)
    REFERENCES core.catalog_bootstrap_reviews (id, candidate_sha256, review_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_bootstrap_review_event_freshness_ck CHECK (
    authenticated_at BETWEEN occurred_at - interval '15 minutes' AND occurred_at + interval '30 seconds'
  )
);
ALTER TABLE core.catalog_bootstrap_reviews ADD CONSTRAINT catalog_bootstrap_review_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_bootstrap_review_events (review_id) DEFERRABLE INITIALLY DEFERRED;

-- Same transaction lock for new reviews and reserved publication decisions: no check-then-insert race.
CREATE FUNCTION core.bind_catalog_bootstrap_review() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $body$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('authweave:catalog-bootstrap-boundary', 0));
  IF EXISTS (SELECT 1 FROM core.catalog_published_snapshots)
    OR EXISTS (SELECT 1 FROM core.catalog_publication_decisions)
    OR EXISTS (SELECT 1 FROM audit.catalog_publication_events) THEN
    RAISE EXCEPTION 'Bootstrap source review requires an empty publication registry' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$body$;
CREATE TRIGGER bind_catalog_bootstrap_review BEFORE INSERT ON core.catalog_bootstrap_reviews
  FOR EACH ROW EXECUTE FUNCTION core.bind_catalog_bootstrap_review();
CREATE FUNCTION core.lock_catalog_bootstrap_boundary() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $body$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('authweave:catalog-bootstrap-boundary', 0));
  RETURN NEW;
END;
$body$;
-- PostgreSQL runs same-event triggers alphabetically. Acquire the boundary before proposal head locks.
CREATE TRIGGER aa_lock_catalog_bootstrap_boundary BEFORE INSERT ON core.catalog_publication_decisions
  FOR EACH ROW EXECUTE FUNCTION core.lock_catalog_bootstrap_boundary();
REVOKE ALL ON FUNCTION core.bind_catalog_bootstrap_review(), core.lock_catalog_bootstrap_boundary() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.bind_catalog_bootstrap_review() TO authweave_core_runtime;

REVOKE ALL ON core.catalog_bootstrap_reviews, audit.catalog_bootstrap_review_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_bootstrap_reviews, audit.catalog_bootstrap_review_events TO authweave_core_runtime;
GRANT INSERT (id, candidate_sha256, review_sha256, request_schema_version, policy_version, catalog_version, fact_count, request)
  ON core.catalog_bootstrap_reviews TO authweave_core_runtime;
GRANT INSERT (id, review_id, candidate_sha256, review_sha256, action, actor_type, actor_issuer, actor_subject,
  actor_project_id, actor_org_id, authenticated_at, correlation_id, outcome)
  ON audit.catalog_bootstrap_review_events TO authweave_core_runtime;
COMMENT ON TABLE core.catalog_bootstrap_reviews IS
  'Immutable exact-candidate manual observations. No automatic truth, curator approval or catalog publication.';
COMMENT ON TABLE audit.catalog_bootstrap_review_events IS
  'Mandatory body-free curator assertion audit; DB constraints do not authenticate an OIDC principal.';
