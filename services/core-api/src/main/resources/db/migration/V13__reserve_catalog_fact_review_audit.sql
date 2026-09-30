-- Human source-review observations only. No approval, verified fact or catalog publication.
CREATE TABLE core.catalog_fact_reviews (
  id uuid PRIMARY KEY,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL CHECK (proposal_version BETWEEN 0 AND 9007199254740991),
  proposal_sha256 varchar(64) NOT NULL CHECK (proposal_sha256 ~ '^[0-9a-f]{64}$'),
  review_number bigint NOT NULL CHECK (review_number BETWEEN 1 AND 9007199254740991),
  option_id varchar(100) NOT NULL CHECK (option_id ~ '^[a-z0-9][a-z0-9.-]{0,99}$'),
  fact_path varchar(200) NOT NULL CHECK (fact_path ~
    '^(facts\.[A-Z0-9_]+|compatibility\.(applications|clients|populations|tenancy|membership)\.[A-Z0-9_]+|residency\.[A-Z0-9_]+|authenticationControls\.[A-Z0-9_]+\.[A-Z0-9_]+\.[A-Z0-9_]+)$'),
  verdict varchar(40) NOT NULL CHECK (verdict IN (
    'SOURCE_SUPPORTS_CLAIM', 'SOURCE_DOES_NOT_SUPPORT_CLAIM', 'INSUFFICIENT_EVIDENCE'
  )),
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_fact_review_revision_fk FOREIGN KEY (proposal_id, proposal_version, proposal_sha256)
    REFERENCES core.catalog_proposal_revisions (proposal_id, version, proposal_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_fact_review_number_uk UNIQUE (proposal_id, proposal_version, review_number),
  CONSTRAINT catalog_fact_review_event_binding_uk
    UNIQUE (id, proposal_id, proposal_version, proposal_sha256, option_id, fact_path, verdict)
);

CREATE TABLE audit.catalog_fact_review_events (
  id uuid PRIMARY KEY,
  review_id uuid NOT NULL UNIQUE,
  proposal_id uuid NOT NULL,
  proposal_version bigint NOT NULL,
  proposal_sha256 varchar(64) NOT NULL,
  option_id varchar(100) NOT NULL,
  fact_path varchar(200) NOT NULL,
  verdict varchar(40) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'catalog-fact.review-recorded'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'CURATOR'),
  actor_issuer varchar(2048) NOT NULL CHECK (length(btrim(actor_issuer)) BETWEEN 1 AND 2048),
  actor_subject varchar(256) NOT NULL CHECK (length(btrim(actor_subject)) BETWEEN 1 AND 256),
  actor_project_id varchar(40) NOT NULL CHECK (actor_project_id ~ '^[0-9]{1,40}$'),
  actor_org_id varchar(40) NOT NULL CHECK (actor_org_id ~ '^[0-9]{1,40}$'),
  authenticated_at timestamp with time zone NOT NULL,
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_fact_review_event_review_fk
    FOREIGN KEY (review_id, proposal_id, proposal_version, proposal_sha256, option_id, fact_path, verdict)
    REFERENCES core.catalog_fact_reviews (id, proposal_id, proposal_version, proposal_sha256, option_id, fact_path, verdict)
    ON DELETE RESTRICT,
  CONSTRAINT catalog_fact_review_event_freshness_ck CHECK (
    authenticated_at >= occurred_at - interval '15 minutes'
    AND authenticated_at <= occurred_at + interval '30 seconds'
  )
);

ALTER TABLE core.catalog_fact_reviews ADD CONSTRAINT catalog_fact_review_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_fact_review_events (review_id)
  DEFERRABLE INITIALLY DEFERRED;

-- Serialize reviews with revisions and rejection. Number assignment and fact membership are DB-owned.
CREATE FUNCTION core.bind_catalog_fact_review() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $body$
DECLARE
  head_version bigint;
  request_body jsonb;
  candidate_option jsonb;
  candidate_fact jsonb;
BEGIN
  SELECT version INTO head_version FROM core.catalog_proposals
    WHERE id = NEW.proposal_id FOR UPDATE;
  IF head_version IS NULL OR head_version <> NEW.proposal_version THEN
    RAISE EXCEPTION 'Fact review requires the current proposal revision' USING ERRCODE = '23514';
  END IF;
  IF EXISTS (SELECT 1 FROM core.catalog_proposal_decisions
      WHERE proposal_id = NEW.proposal_id AND proposal_version = NEW.proposal_version) THEN
    RAISE EXCEPTION 'A rejected revision cannot receive new fact reviews' USING ERRCODE = '23514';
  END IF;
  SELECT request INTO request_body FROM core.catalog_proposal_revisions
    WHERE proposal_id = NEW.proposal_id AND version = NEW.proposal_version
      AND proposal_sha256 = NEW.proposal_sha256 AND request_schema_version = 1;
  IF request_body IS NULL THEN
    RAISE EXCEPTION 'Fact review revision digest does not match' USING ERRCODE = '23503';
  END IF;
  SELECT value INTO candidate_option FROM jsonb_array_elements(request_body->'candidate'->'options')
    WHERE value->>'id' = NEW.option_id;
  candidate_fact := candidate_option #> string_to_array(NEW.fact_path, '.');
  IF jsonb_typeof(candidate_fact) IS DISTINCT FROM 'object' OR NOT (candidate_fact ? 'evidence') THEN
    RAISE EXCEPTION 'Fact review must address a recorded candidate fact' USING ERRCODE = '23514';
  END IF;
  SELECT coalesce(max(review_number), 0) + 1 INTO NEW.review_number FROM core.catalog_fact_reviews
    WHERE proposal_id = NEW.proposal_id AND proposal_version = NEW.proposal_version;
  RETURN NEW;
END;
$body$;
CREATE TRIGGER bind_catalog_fact_review BEFORE INSERT ON core.catalog_fact_reviews
  FOR EACH ROW EXECUTE FUNCTION core.bind_catalog_fact_review();
REVOKE ALL ON FUNCTION core.bind_catalog_fact_review() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.bind_catalog_fact_review() TO authweave_core_runtime;

REVOKE ALL ON core.catalog_fact_reviews, audit.catalog_fact_review_events
  FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_fact_reviews, audit.catalog_fact_review_events TO authweave_core_runtime;
GRANT INSERT (id, proposal_id, proposal_version, proposal_sha256, option_id, fact_path, verdict)
  ON core.catalog_fact_reviews TO authweave_core_runtime;
GRANT INSERT (id, review_id, proposal_id, proposal_version, proposal_sha256, option_id, fact_path, verdict,
  action, actor_type, actor_issuer, actor_subject, actor_project_id, actor_org_id,
  authenticated_at, correlation_id, outcome)
  ON audit.catalog_fact_review_events TO authweave_core_runtime;

CREATE INDEX catalog_fact_review_history_idx ON core.catalog_fact_reviews
  (proposal_id, proposal_version, option_id, fact_path, review_number DESC);
COMMENT ON TABLE core.catalog_fact_reviews IS
  'Append-only human source verdict for a revision-bound candidate fact. Not approval, freshness or publication.';
COMMENT ON TABLE audit.catalog_fact_review_events IS
  'Atomic curator assertion audit. No source content or credentials; database constraints do not verify an OIDC login.';
