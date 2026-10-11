-- Preserve all v1 historical rows; v2 records an owner's endorsement of assistant findings,
-- not a claim that the owner personally read sources, authenticated AI provenance or publication.
ALTER TABLE core.catalog_bootstrap_reviews
  DROP CONSTRAINT catalog_bootstrap_reviews_request_schema_version_check,
  DROP CONSTRAINT catalog_bootstrap_reviews_policy_version_check,
  DROP CONSTRAINT catalog_bootstrap_review_request_ck;

ALTER TABLE core.catalog_bootstrap_reviews
  ADD CONSTRAINT catalog_bootstrap_reviews_request_schema_version_check CHECK (request_schema_version IN (1, 2)),
  ADD CONSTRAINT catalog_bootstrap_reviews_policy_version_check CHECK (
    (request_schema_version = 1 AND policy_version = 'catalog-bootstrap-source-review-1') OR
    (request_schema_version = 2 AND policy_version = 'catalog-bootstrap-assistant-endorsement-1')),
  ADD CONSTRAINT catalog_bootstrap_review_request_ck CHECK ((
    jsonb_typeof(request) = 'object'
    AND octet_length(request::text) BETWEEN 1 AND 33554432
    AND request ?& ARRAY['schemaVersion', 'reviewId', 'expectedCandidateSha256', 'candidate', 'observations', 'confirmation']
    AND (request - ARRAY['schemaVersion', 'reviewId', 'expectedCandidateSha256', 'candidate', 'observations', 'confirmation']) = '{}'::jsonb
    AND request->'schemaVersion' = to_jsonb(request_schema_version)
    AND ((request_schema_version = 1 AND request->>'confirmation' = 'MANUAL_BOOTSTRAP_SOURCE_REVIEW') OR
         (request_schema_version = 2 AND request->>'confirmation' = 'OWNER_APPROVES_ASSISTANT_SOURCE_ASSESSMENT'))
    AND request->>'reviewId' = id::text
    AND request->>'expectedCandidateSha256' = candidate_sha256
    AND jsonb_typeof(request->'candidate') = 'object'
    AND request->'candidate'->'schemaVersion' = '1'::jsonb
    AND request->'candidate'->>'kind' = 'PROVIDER_CATALOG_DRAFT'
    AND request->'candidate'->>'catalogVersion' = catalog_version
    AND CASE WHEN jsonb_typeof(request->'observations') = 'array'
      THEN jsonb_array_length(request->'observations') = fact_count ELSE false END
  ) IS TRUE);

ALTER TABLE audit.catalog_bootstrap_review_events
  DROP CONSTRAINT catalog_bootstrap_review_events_action_check,
  ADD CONSTRAINT catalog_bootstrap_review_events_action_check CHECK (
    action IN ('catalog-bootstrap.source-reviewed', 'catalog-bootstrap.assessment-approved'));

-- No new privileges, mutable rows, actor bypass or publication trigger changes.
COMMENT ON TABLE core.catalog_bootstrap_reviews IS
  'Immutable exact findings. V1 is personal manual review; v2 is owner-endorsed assistant assessment. Neither is source truth or publication.';
