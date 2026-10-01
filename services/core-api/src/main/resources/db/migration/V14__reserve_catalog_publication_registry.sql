-- Storage reservation only. Runtime publication writes and catalog activation remain unavailable.
CREATE TABLE core.catalog_publication_decisions (
  id uuid PRIMARY KEY,
  snapshot_id uuid NOT NULL UNIQUE,
  decision_kind varchar(24) NOT NULL CHECK (decision_kind IN ('CURATED_BOOTSTRAP', 'PROPOSAL_APPROVAL')),
  proposal_id uuid,
  proposal_version bigint CHECK (proposal_version BETWEEN 0 AND 9007199254740991),
  proposal_sha256 varchar(64) CHECK (proposal_sha256 ~ '^[0-9a-f]{64}$'),
  catalog_version varchar(100) NOT NULL CHECK (catalog_version ~ '^[a-z0-9][a-z0-9.-]{0,99}$'),
  content_sha256 varchar(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  snapshot_sha256 varchar(64) NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
  published_at timestamp with time zone NOT NULL CHECK (isfinite(published_at)),
  recorded_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_publication_decision_origin_ck CHECK (
    (decision_kind = 'CURATED_BOOTSTRAP' AND proposal_id IS NULL
      AND proposal_version IS NULL AND proposal_sha256 IS NULL)
    OR (decision_kind = 'PROPOSAL_APPROVAL' AND proposal_id IS NOT NULL
      AND proposal_version IS NOT NULL AND proposal_sha256 IS NOT NULL)
  ),
  CONSTRAINT catalog_publication_decision_once_uk UNIQUE (proposal_id, proposal_version),
  CONSTRAINT catalog_publication_decision_revision_fk
    FOREIGN KEY (proposal_id, proposal_version, proposal_sha256)
    REFERENCES core.catalog_proposal_revisions (proposal_id, version, proposal_sha256) ON DELETE RESTRICT,
  CONSTRAINT catalog_publication_decision_snapshot_uk
    UNIQUE (id, snapshot_id, decision_kind, catalog_version, content_sha256, snapshot_sha256, published_at),
  CONSTRAINT catalog_publication_decision_event_uk UNIQUE (id, snapshot_id, snapshot_sha256, decision_kind),
  -- The manifest timestamp is supplied once for hashing; DB receipt time is separate and bounded.
  CONSTRAINT catalog_publication_decision_time_ck CHECK (
    isfinite(recorded_at) AND published_at BETWEEN recorded_at - interval '30 seconds'
      AND recorded_at + interval '30 seconds'
  )
);

CREATE TABLE core.catalog_published_snapshots (
  id uuid PRIMARY KEY,
  decision_id uuid NOT NULL UNIQUE,
  decision_kind varchar(24) NOT NULL,
  catalog_version varchar(100) NOT NULL UNIQUE,
  content_sha256 varchar(64) NOT NULL,
  snapshot_sha256 varchar(64) NOT NULL,
  published_at timestamp with time zone NOT NULL,
  previous_snapshot_id uuid,
  previous_catalog_version varchar(100),
  previous_snapshot_sha256 varchar(64),
  manifest jsonb NOT NULL,
  CONSTRAINT catalog_published_snapshot_decision_fk
    FOREIGN KEY (decision_id, id, decision_kind, catalog_version, content_sha256, snapshot_sha256, published_at)
    REFERENCES core.catalog_publication_decisions
      (id, snapshot_id, decision_kind, catalog_version, content_sha256, snapshot_sha256, published_at) ON DELETE RESTRICT,
  CONSTRAINT catalog_published_snapshot_decision_uk UNIQUE (id, decision_id),
  CONSTRAINT catalog_published_snapshot_reference_uk UNIQUE (id, catalog_version, snapshot_sha256),
  CONSTRAINT catalog_published_snapshot_parent_ck CHECK (
    (decision_kind = 'CURATED_BOOTSTRAP' AND previous_snapshot_id IS NULL
      AND previous_catalog_version IS NULL AND previous_snapshot_sha256 IS NULL)
    OR (decision_kind = 'PROPOSAL_APPROVAL' AND previous_snapshot_id IS NOT NULL
      AND previous_catalog_version IS NOT NULL AND previous_snapshot_sha256 IS NOT NULL
      AND previous_snapshot_id <> id AND previous_catalog_version <> catalog_version)
  ),
  CONSTRAINT catalog_published_snapshot_parent_fk
    FOREIGN KEY (previous_snapshot_id, previous_catalog_version, previous_snapshot_sha256)
    REFERENCES core.catalog_published_snapshots (id, catalog_version, snapshot_sha256) ON DELETE RESTRICT,
  -- Only one committed successor per parent. Concurrent competing inserts cannot fork the registry.
  CONSTRAINT catalog_published_snapshot_successor_uk UNIQUE (previous_snapshot_id),
  CONSTRAINT catalog_published_snapshot_manifest_ck CHECK ((
    jsonb_typeof(manifest) = 'object'
    AND manifest ?& ARRAY['schemaVersion', 'kind', 'snapshotId', 'canonicalizationVersion', 'catalog',
      'contentSha256', 'snapshotSha256', 'previousSnapshot', 'publication', 'factEvidenceStatuses']
    AND (manifest - ARRAY['schemaVersion', 'kind', 'snapshotId', 'canonicalizationVersion', 'catalog',
      'contentSha256', 'snapshotSha256', 'previousSnapshot', 'publication', 'factEvidenceStatuses']) = '{}'::jsonb
    AND manifest->'schemaVersion' = '1'::jsonb
    AND manifest->>'kind' = 'PUBLISHED_PROVIDER_CATALOG_SNAPSHOT'
    AND manifest->>'snapshotId' = id::text
    AND manifest->>'canonicalizationVersion' = 'catalog-draft-canonical-json-1'
    AND manifest->>'contentSha256' = content_sha256
    AND manifest->>'snapshotSha256' = snapshot_sha256
    AND jsonb_typeof(manifest->'catalog') = 'object'
    AND ((manifest->'catalog') - ARRAY['schemaVersion', 'catalogVersion', 'options']) = '{}'::jsonb
    AND manifest->'catalog'->'schemaVersion' = '1'::jsonb
    AND manifest->'catalog'->>'catalogVersion' = catalog_version
    AND CASE WHEN jsonb_typeof(manifest->'catalog'->'options') = 'array'
      THEN jsonb_array_length(manifest->'catalog'->'options') BETWEEN 1 AND 100 ELSE false END
    AND jsonb_typeof(manifest->'publication') = 'object'
    AND ((manifest->'publication') - ARRAY['decisionId', 'publishedAt']) = '{}'::jsonb
    AND manifest->'publication'->>'decisionId' = decision_id::text
    AND (manifest->'publication'->>'publishedAt')::timestamp with time zone = published_at
    AND CASE WHEN jsonb_typeof(manifest->'factEvidenceStatuses') = 'array'
      THEN jsonb_array_length(manifest->'factEvidenceStatuses') BETWEEN 1 AND 6800 ELSE false END
    AND ((previous_snapshot_id IS NULL AND manifest->'previousSnapshot' = 'null'::jsonb)
      OR (previous_snapshot_id IS NOT NULL AND jsonb_typeof(manifest->'previousSnapshot') = 'object'
        AND ((manifest->'previousSnapshot') - ARRAY['snapshotId', 'catalogVersion', 'snapshotSha256']) = '{}'::jsonb
        AND manifest->'previousSnapshot'->>'snapshotId' = previous_snapshot_id::text
        AND manifest->'previousSnapshot'->>'catalogVersion' = previous_catalog_version
        AND manifest->'previousSnapshot'->>'snapshotSha256' = previous_snapshot_sha256))
  ) IS TRUE)
);
-- There is no active pointer. A unique root and parent tuple reserve a single immutable history.
CREATE UNIQUE INDEX catalog_published_snapshot_single_root_uk ON core.catalog_published_snapshots ((true))
  WHERE previous_snapshot_id IS NULL;

CREATE TABLE audit.catalog_publication_events (
  id uuid PRIMARY KEY,
  decision_id uuid NOT NULL UNIQUE,
  snapshot_id uuid NOT NULL UNIQUE,
  snapshot_sha256 varchar(64) NOT NULL,
  decision_kind varchar(24) NOT NULL,
  action varchar(40) NOT NULL CHECK (action = 'catalog.published'),
  actor_type varchar(16) NOT NULL CHECK (actor_type = 'CURATOR'),
  actor_issuer varchar(2048) NOT NULL CHECK (length(btrim(actor_issuer)) BETWEEN 1 AND 2048),
  actor_subject varchar(256) NOT NULL CHECK (length(btrim(actor_subject)) BETWEEN 1 AND 256),
  actor_project_id varchar(40) NOT NULL CHECK (actor_project_id ~ '^[0-9]{1,40}$'),
  actor_org_id varchar(40) NOT NULL CHECK (actor_org_id ~ '^[0-9]{1,40}$'),
  authenticated_at timestamp with time zone NOT NULL CHECK (isfinite(authenticated_at)),
  correlation_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome = 'SUCCEEDED'),
  occurred_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT catalog_publication_event_decision_fk
    FOREIGN KEY (decision_id, snapshot_id, snapshot_sha256, decision_kind)
    REFERENCES core.catalog_publication_decisions (id, snapshot_id, snapshot_sha256, decision_kind) ON DELETE RESTRICT,
  CONSTRAINT catalog_publication_event_freshness_ck CHECK (
    isfinite(occurred_at) AND authenticated_at BETWEEN occurred_at - interval '15 minutes'
      AND occurred_at + interval '30 seconds'
  )
);

-- A decision must commit with both its manifest and event; either may be inserted next.
ALTER TABLE core.catalog_publication_decisions ADD CONSTRAINT catalog_publication_requires_snapshot_fk
  FOREIGN KEY (snapshot_id, id) REFERENCES core.catalog_published_snapshots (id, decision_id)
  DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE core.catalog_publication_decisions ADD CONSTRAINT catalog_publication_requires_event_fk
  FOREIGN KEY (id) REFERENCES audit.catalog_publication_events (decision_id)
  DEFERRABLE INITIALLY DEFERRED;

-- Serialize a reserved proposal decision with revision changes and rejection. Not an approval policy.
CREATE FUNCTION core.bind_catalog_publication_decision() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $body$
DECLARE head_version bigint;
BEGIN
  IF NEW.decision_kind = 'PROPOSAL_APPROVAL' THEN
    SELECT version INTO head_version FROM core.catalog_proposals WHERE id = NEW.proposal_id FOR UPDATE;
    IF head_version IS NULL OR head_version <> NEW.proposal_version THEN
      RAISE EXCEPTION 'Publication decision requires the current proposal revision' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM core.catalog_proposal_decisions
        WHERE proposal_id = NEW.proposal_id AND proposal_version = NEW.proposal_version) THEN
      RAISE EXCEPTION 'A rejected revision cannot receive a publication decision' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$body$;
CREATE TRIGGER bind_catalog_publication_decision BEFORE INSERT ON core.catalog_publication_decisions
  FOR EACH ROW EXECUTE FUNCTION core.bind_catalog_publication_decision();

CREATE FUNCTION core.exclude_published_proposal_rejection() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $body$
BEGIN
  PERFORM 1 FROM core.catalog_proposals WHERE id = NEW.proposal_id FOR UPDATE;
  IF EXISTS (SELECT 1 FROM core.catalog_publication_decisions
      WHERE proposal_id = NEW.proposal_id AND proposal_version = NEW.proposal_version) THEN
    RAISE EXCEPTION 'A publication decision and rejection cannot coexist' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$body$;
CREATE TRIGGER exclude_published_proposal_rejection BEFORE INSERT ON core.catalog_proposal_decisions
  FOR EACH ROW EXECUTE FUNCTION core.exclude_published_proposal_rejection();
REVOKE ALL ON FUNCTION core.bind_catalog_publication_decision(), core.exclude_published_proposal_rejection() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.exclude_published_proposal_rejection() TO authweave_core_runtime;

REVOKE ALL ON core.catalog_publication_decisions, core.catalog_published_snapshots,
  audit.catalog_publication_events FROM PUBLIC, authweave_core_runtime, authweave_web_runtime;
GRANT SELECT ON core.catalog_publication_decisions, core.catalog_published_snapshots,
  audit.catalog_publication_events TO authweave_core_runtime;

COMMENT ON TABLE core.catalog_publication_decisions IS
  'Reserved exact-revision publication decision binding. No runtime INSERT until verified curator workflow exists.';
COMMENT ON TABLE core.catalog_published_snapshots IS
  'Reserved immutable manifest registry, not active catalog. SQL binds metadata, not canonical hashes or source truth.';
COMMENT ON TABLE audit.catalog_publication_events IS
  'Reserved atomic curator assertion event, without raw source or credentials. SQL does not authenticate an OIDC actor.';
