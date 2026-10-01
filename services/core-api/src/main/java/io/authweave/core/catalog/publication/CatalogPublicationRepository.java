package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import static io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static io.authweave.core.generated.audit.tables.CatalogPublicationEvents.CATALOG_PUBLICATION_EVENTS;

/** SELECT-only internal access; validation and the repeatable-read transaction belong to the lookup boundary. */
@Repository
class CatalogPublicationRepository {
    static final long MAX_JSON_BYTES = 32L * 1024 * 1024;
    private final DSLContext dsl;
    CatalogPublicationRepository(DSLContext dsl) { this.dsl = dsl; }

    CatalogPublicationRecord find(UUID id, long remainingBytes) {
        var s = CATALOG_PUBLISHED_SNAPSHOTS; var d = CATALOG_PUBLICATION_DECISIONS;
        var e = CATALOG_PUBLICATION_EVENTS; var p = CATALOG_PROPOSAL_REVISIONS; var rejected = CATALOG_PROPOSAL_DECISIONS;
        var manifestLength = DSL.octetLength(s.MANIFEST.cast(String.class)).cast(Long.class);
        var requestLength = DSL.coalesce(DSL.octetLength(p.REQUEST.cast(String.class)).cast(Long.class), 0L);
        var manifestBytes = manifestLength.as("manifest_bytes");
        var requestBytes = requestLength.as("request_bytes");
        // Oversized bodies stay on the server. Metadata still lets the lookup distinguish a budget failure.
        var fits = manifestLength.le(MAX_JSON_BYTES).and(requestLength.le(MAX_JSON_BYTES))
                .and(manifestLength.add(requestLength).le(remainingBytes));
        var manifest = DSL.when(fits, s.MANIFEST).otherwise((JSONB) null).as("bounded_manifest");
        var request = DSL.when(fits, p.REQUEST).otherwise((JSONB) null).as("bounded_request");
        var row = dsl.select(s.ID, s.DECISION_ID, s.DECISION_KIND, s.CATALOG_VERSION, s.CONTENT_SHA256, s.SNAPSHOT_SHA256,
                        s.PUBLISHED_AT, s.PREVIOUS_SNAPSHOT_ID, s.PREVIOUS_CATALOG_VERSION, s.PREVIOUS_SNAPSHOT_SHA256,
                        manifest, manifestBytes, request, requestBytes)
                .select(d.fields()).select(e.fields())
                .select(p.PROPOSAL_ID, p.VERSION, p.STATE, p.REQUEST_SCHEMA_VERSION, p.PROPOSAL_SHA256, rejected.ID)
                .from(s).leftJoin(d).on(d.ID.eq(s.DECISION_ID))
                .leftJoin(e).on(e.DECISION_ID.eq(d.ID))
                .leftJoin(p).on(p.PROPOSAL_ID.eq(d.PROPOSAL_ID).and(p.VERSION.eq(d.PROPOSAL_VERSION)))
                .leftJoin(rejected).on(rejected.PROPOSAL_ID.eq(d.PROPOSAL_ID).and(rejected.PROPOSAL_VERSION.eq(d.PROPOSAL_VERSION)))
                .where(s.ID.eq(id)).fetchOne();
        if (row == null) return null;
        var snapshot = new CatalogPublicationRecord.Snapshot(row.get(s.ID), row.get(s.DECISION_ID), row.get(s.DECISION_KIND),
                row.get(s.CATALOG_VERSION), row.get(s.CONTENT_SHA256), row.get(s.SNAPSHOT_SHA256), instant(row.get(s.PUBLISHED_AT)),
                row.get(s.PREVIOUS_SNAPSHOT_ID), row.get(s.PREVIOUS_CATALOG_VERSION), row.get(s.PREVIOUS_SNAPSHOT_SHA256),
                json(row.get(manifest)), row.get(manifestBytes));
        var decision = row.get(d.ID) == null ? null : new CatalogPublicationRecord.Decision(row.get(d.ID), row.get(d.SNAPSHOT_ID),
                row.get(d.DECISION_KIND), row.get(d.PROPOSAL_ID), row.get(d.PROPOSAL_VERSION), row.get(d.PROPOSAL_SHA256),
                row.get(d.CATALOG_VERSION), row.get(d.CONTENT_SHA256), row.get(d.SNAPSHOT_SHA256), instant(row.get(d.PUBLISHED_AT)), instant(row.get(d.RECORDED_AT)));
        var event = row.get(e.ID) == null ? null : new CatalogPublicationRecord.Event(row.get(e.ID), row.get(e.DECISION_ID),
                row.get(e.SNAPSHOT_ID), row.get(e.SNAPSHOT_SHA256), row.get(e.DECISION_KIND), row.get(e.ACTION), row.get(e.ACTOR_TYPE),
                row.get(e.ACTOR_ISSUER), row.get(e.ACTOR_SUBJECT), row.get(e.ACTOR_PROJECT_ID), row.get(e.ACTOR_ORG_ID),
                instant(row.get(e.AUTHENTICATED_AT)), row.get(e.CORRELATION_ID), row.get(e.OUTCOME), instant(row.get(e.OCCURRED_AT)));
        var proposal = row.get(p.PROPOSAL_ID) == null ? null : new CatalogPublicationRecord.Proposal(row.get(p.PROPOSAL_ID),
                row.get(p.VERSION), row.get(p.STATE), row.get(p.REQUEST_SCHEMA_VERSION), row.get(p.PROPOSAL_SHA256),
                json(row.get(request)), row.get(requestBytes), row.get(rejected.ID) != null);
        return new CatalogPublicationRecord(snapshot, decision, event, proposal);
    }

    List<UUID> roots() {
        var s = CATALOG_PUBLISHED_SNAPSHOTS;
        return dsl.select(s.ID).from(s).where(s.PREVIOUS_SNAPSHOT_ID.isNull()).limit(2).fetch(s.ID);
    }

    int successors(UUID id) {
        var s = CATALOG_PUBLISHED_SNAPSHOTS;
        return dsl.select(s.ID).from(s).where(s.PREVIOUS_SNAPSHOT_ID.eq(id)).limit(2).fetch(s.ID).size();
    }

    private static Instant instant(OffsetDateTime at) { return at == null ? null : at.toInstant(); }
    private static String json(JSONB value) { return value == null ? null : value.data(); }
}
