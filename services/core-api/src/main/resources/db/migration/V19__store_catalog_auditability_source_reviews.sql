-- Separate immutable manual assertions for exact auditability supplements. Never publication authority.
CREATE TABLE core.catalog_auditability_reviews (
  id uuid PRIMARY KEY,
  base_content_sha256 varchar(64) NOT NULL CHECK (base_content_sha256 ~ '^[0-9a-f]{64}$'),
  auditability_content_sha256 varchar(64) NOT NULL CHECK (auditability_content_sha256 ~ '^[0-9a-f]{64}$'),
  target_set_sha256 varchar(64) NOT NULL CHECK (target_set_sha256 ~ '^[0-9a-f]{64}$'),
  review_sha256 varchar(64) NOT NULL CHECK (review_sha256 ~ '^[0-9a-f]{64}$'),
  request_schema_version smallint NOT NULL CHECK (request_schema_version = 1),
  policy_version varchar(80) NOT NULL CHECK (policy_version = 'catalog-auditability-source-review-1'),
  catalog_version varchar(100) NOT NULL CHECK (catalog_version ~ '^[a-z0-9][a-z0-9.-]{0,99}$'),
  evidence_version varchar(100) NOT NULL CHECK (evidence_version ~ '^[a-z0-9][a-z0-9.-]{0,99}$'),
  option_count integer NOT NULL CHECK (option_count BETWEEN 1 AND 100),
  fact_count integer NOT NULL CHECK (fact_count BETWEEN 1 AND 600 AND fact_count <= option_count * 6),
  request jsonb NOT NULL,
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(recorded_at)),
  CONSTRAINT catalog_auditability_review_event_binding_uk UNIQUE
    (id, base_content_sha256, auditability_content_sha256, target_set_sha256, review_sha256),
  CONSTRAINT catalog_auditability_review_request_ck CHECK ((
    jsonb_typeof(request) = 'object' AND octet_length(request::text) BETWEEN 1 AND 33554432
    AND request ?& ARRAY['schemaVersion', 'reviewId', 'expectedBaseContentSha256', 'expectedAuditabilityContentSha256',
      'expectedTargetSetSha256', 'candidate', 'observations', 'confirmation']
    AND (request - ARRAY['schemaVersion', 'reviewId', 'expectedBaseContentSha256', 'expectedAuditabilityContentSha256',
      'expectedTargetSetSha256', 'candidate', 'observations', 'confirmation']) = '{}'::jsonb
    AND request->'schemaVersion' = '1'::jsonb AND request->>'reviewId' = id::text
    AND request->>'expectedBaseContentSha256' = base_content_sha256
    AND request->>'expectedAuditabilityContentSha256' = auditability_content_sha256
    AND request->>'expectedTargetSetSha256' = target_set_sha256
    AND request->>'confirmation' = 'MANUAL_AUDITABILITY_SOURCE_REVIEW'
    AND jsonb_typeof(request->'candidate') = 'object'
    AND ((request->'candidate') - ARRAY['baseDraft', 'auditabilityDraft']) = '{}'::jsonb
    AND request->'candidate'->'baseDraft'->'schemaVersion' = '1'::jsonb
    AND request->'candidate'->'baseDraft'->>'kind' = 'PROVIDER_CATALOG_DRAFT'
    AND request->'candidate'->'baseDraft'->>'catalogVersion' = catalog_version
    AND request->'candidate'->'auditabilityDraft'->'schemaVersion' = '1'::jsonb
    AND request->'candidate'->'auditabilityDraft'->>'kind' = 'AUDITABILITY_CATALOG_DRAFT'
    AND request->'candidate'->'auditabilityDraft'->>'evidenceVersion' = evidence_version
    AND request->'candidate'->'auditabilityDraft'->>'baseCatalogVersion' = catalog_version
    AND request->'candidate'->'auditabilityDraft'->>'baseContentSha256' = base_content_sha256
    AND CASE WHEN jsonb_typeof(request->'candidate'->'auditabilityDraft'->'options') = 'array'
      THEN jsonb_array_length(request->'candidate'->'auditabilityDraft'->'options') = option_count ELSE false END
    AND CASE WHEN jsonb_typeof(request->'observations') = 'array'
      THEN jsonb_array_length(request->'observations') = fact_count ELSE false END
  ) IS TRUE)
);

CREATE TABLE audit.catalog_auditability_review_events (
  id uuid PRIMARY KEY,
  review_id uuid NOT NULL UNIQUE,
  base_content_sha256 varchar(64) NOT NULL,
  auditability_content_sha256 varchar(64) NOT NULL,
  target_set_sha256 varchar(64) NOT NULL,
  review_sha256 varchar(64) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'catalog-auditability.source-reviewed'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'CURATOR'),
  actor_issuer varchar(2048) NOT NULL CHECK (length(btrim(actor_issuer)) BETWEEN 1 AND 2048),
  actor_subject varchar(256) NOT NULL CHECK (length(btrim(actor_subject)) BETWEEN 1 AND 256),
  actor_project_id varchar(40) NOT NULL CHECK (actor_project_id ~ '^[0-9]{1,40}$'),
  actor_org_id varchar(40) NOT NULL CHECK (actor_org_id ~ '^[0-9]{1,40}$'),
  authenticated_at timestamp with time zone NOT NULL CHECK (isfinite(authenticated_at)),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
  CONSTRAINT catalog_auditability_review_event_review_fk FOREIGN KEY
    (review_id, base_content_sha256, auditability_content_sha256, target_set_sha256, review_sha256)
    REFERENCES core.catalog_auditability_reviews
      (id, base_content_sha256, auditability_content_sha256, target_set_sha256, review_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_auditability_review_event_freshness_ck CHECK (
    authenticated_at BETWEEN occurred_at - interval '15 minutes' AND occurred_at + interval '30 seconds'
  )
);
ALTER TABLE core.catalog_auditability_reviews ADD CONSTRAINT catalog_auditability_review_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_auditability_review_events (review_id) DEFERRABLE INITIALLY DEFERRED;

-- Same per-key lock used by the service; unrelated reviews do not share the bootstrap/publication boundary.
CREATE FUNCTION core.lock_catalog_auditability_review_key() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $body$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('authweave:catalog-auditability-review:' || NEW.id::text, 0));
  RETURN NEW;
END;
$body$;
CREATE TRIGGER lock_catalog_auditability_review_key BEFORE INSERT ON core.catalog_auditability_reviews
  FOR EACH ROW EXECUTE FUNCTION core.lock_catalog_auditability_review_key();
REVOKE ALL ON FUNCTION core.lock_catalog_auditability_review_key() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lock_catalog_auditability_review_key() TO authweave_core_runtime;

REVOKE ALL ON core.catalog_auditability_reviews, audit.catalog_auditability_review_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_auditability_reviews, audit.catalog_auditability_review_events TO authweave_core_runtime;
GRANT INSERT (id, base_content_sha256, auditability_content_sha256, target_set_sha256, review_sha256,
  request_schema_version, policy_version, catalog_version, evidence_version, option_count, fact_count, request)
  ON core.catalog_auditability_reviews TO authweave_core_runtime;
GRANT INSERT (id, review_id, base_content_sha256, auditability_content_sha256, target_set_sha256, review_sha256,
  action, actor_type, actor_issuer, actor_subject, actor_project_id, actor_org_id, authenticated_at, correlation_id, outcome)
  ON audit.catalog_auditability_review_events TO authweave_core_runtime;
COMMENT ON TABLE core.catalog_auditability_reviews IS
  'Immutable exact-supplement manual observations; no reviewed provider facts, approval, impact or publication.';
COMMENT ON TABLE audit.catalog_auditability_review_events IS
  'Mandatory body-free scoped curator audit. The protected HTTP boundary, not DB metadata, authenticates the actor.';
