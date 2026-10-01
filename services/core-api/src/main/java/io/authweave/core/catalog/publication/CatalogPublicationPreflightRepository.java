package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
import static io.authweave.core.generated.jooq.tables.CatalogImpactReports.CATALOG_IMPACT_REPORTS;
import static io.authweave.core.generated.audit.tables.CatalogPublicationEvents.CATALOG_PUBLICATION_EVENTS;

/** Metadata and bounded request reads only. Receipt existence does not establish integrity or authority. */
@Repository
class CatalogPublicationPreflightRepository {
    private final DSLContext dsl;
    CatalogPublicationPreflightRepository(DSLContext dsl) { this.dsl = dsl; }

    record Proposal(UUID id, long version, Long currentVersion, String state, int schemaVersion,
            String sha256, Instant recordedAt, String request, long requestBytes, boolean rejected, boolean published) { }

    Proposal proposal(UUID id, long version) {
        return proposal(id, version, CatalogPublicationRepository.MAX_JSON_BYTES);
    }

    Proposal proposal(UUID id, long version, long maxBytes) {
        if (maxBytes < 0 || maxBytes > CatalogPublicationRepository.MAX_JSON_BYTES) throw new IllegalArgumentException("Invalid read budget");
        var r = CATALOG_PROPOSAL_REVISIONS; var p = CATALOG_PROPOSALS;
        var rejected = CATALOG_PROPOSAL_DECISIONS; var published = CATALOG_PUBLICATION_DECISIONS;
        var length = DSL.octetLength(r.REQUEST.cast(String.class)).cast(Long.class);
        var bytes = length.as("request_bytes");
        var body = DSL.when(length.le(maxBytes), r.REQUEST)
                .otherwise((JSONB) null).as("bounded_request");
        var row = dsl.select(r.PROPOSAL_ID, r.VERSION, p.VERSION, r.STATE, r.REQUEST_SCHEMA_VERSION,
                        r.PROPOSAL_SHA256, r.RECORDED_AT, body, bytes, rejected.ID, published.ID)
                .from(r).leftJoin(p).on(p.ID.eq(r.PROPOSAL_ID))
                .leftJoin(rejected).on(rejected.PROPOSAL_ID.eq(r.PROPOSAL_ID).and(rejected.PROPOSAL_VERSION.eq(r.VERSION)))
                .leftJoin(published).on(published.PROPOSAL_ID.eq(r.PROPOSAL_ID).and(published.PROPOSAL_VERSION.eq(r.VERSION)))
                .where(r.PROPOSAL_ID.eq(id).and(r.VERSION.eq(version))).fetchOne();
        if (row == null) return null;
        var json = row.get(body);
        return new Proposal(row.get(r.PROPOSAL_ID), row.get(r.VERSION), row.get(p.VERSION), row.get(r.STATE),
                row.get(r.REQUEST_SCHEMA_VERSION), row.get(r.PROPOSAL_SHA256), row.get(r.RECORDED_AT).toInstant(),
                json == null ? null : json.data(), row.get(bytes), row.get(rejected.ID) != null, row.get(published.ID) != null);
    }

    boolean registryEmpty() {
        return !dsl.fetchExists(CATALOG_PUBLISHED_SNAPSHOTS) && !dsl.fetchExists(CATALOG_PUBLICATION_DECISIONS)
                && !dsl.fetchExists(CATALOG_PUBLICATION_EVENTS);
    }

    boolean labelUsed(String label) {
        return dsl.fetchExists(CATALOG_PUBLISHED_SNAPSHOTS, CATALOG_PUBLISHED_SNAPSHOTS.CATALOG_VERSION.eq(label));
    }

    boolean hasImpactReceipt(UUID id, long version, String sha256) {
        var r = CATALOG_IMPACT_REPORTS;
        return dsl.fetchExists(r, r.PROPOSAL_ID.eq(id).and(r.PROPOSAL_VERSION.eq(version)).and(r.PROPOSAL_SHA256.eq(sha256)));
    }
}
