-- Preserve legacy manual receipts; distinguish owner-endorsed assistant conclusions.
-- Neither origin verifies source truth, refreshes evidence or approves publication.
ALTER TABLE core.catalog_fact_reviews
  ADD COLUMN review_origin varchar(64) NOT NULL DEFAULT 'MANUAL_SOURCE_REVIEW',
  ADD CONSTRAINT catalog_fact_review_origin_ck CHECK (review_origin IN (
    'MANUAL_SOURCE_REVIEW', 'OWNER_APPROVES_ASSISTANT_SOURCE_ASSESSMENT'
  )),
  ADD CONSTRAINT catalog_fact_review_origin_binding_uk UNIQUE (id, review_origin);

ALTER TABLE audit.catalog_fact_review_events
  ADD COLUMN review_origin varchar(64) NOT NULL DEFAULT 'MANUAL_SOURCE_REVIEW',
  ADD CONSTRAINT catalog_fact_review_event_origin_ck CHECK (review_origin IN (
    'MANUAL_SOURCE_REVIEW', 'OWNER_APPROVES_ASSISTANT_SOURCE_ASSESSMENT'
  )),
  ADD CONSTRAINT catalog_fact_review_event_origin_fk FOREIGN KEY (review_id, review_origin)
    REFERENCES core.catalog_fact_reviews (id, review_origin) ON DELETE RESTRICT;

GRANT INSERT (review_origin) ON core.catalog_fact_reviews, audit.catalog_fact_review_events
  TO authweave_core_runtime;
COMMENT ON COLUMN core.catalog_fact_reviews.review_origin IS
  'Explicit curator confirmation mode. Owner endorsement does not claim personal source reading.';
COMMENT ON COLUMN audit.catalog_fact_review_events.review_origin IS
  'Atomically bound to receipt origin; no source truth or publication authority is inferred.';
