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
import static io.authweave.core.generated.audit.tables.CatalogImpactReportEvents.CATALOG_IMPACT_REPORT_EVENTS;
import io.authweave.core.catalog.impact.CatalogImpactReportEvent;

/** Metadata and bounded request/report reads only. Receipt existence does not establish integrity or authority. */
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

    record Impact(UUID id, long number, UUID proposalId, long version, String proposalSha256, int schemaVersion,
            String canonicalizationVersion, String reportSha256, Instant recordedAt, String report, long reportBytes,
            CatalogImpactReportEvent event) { }

    Impact latestImpact(UUID id, long version) {
        return latestImpact(id, version, CatalogPublicationRepository.MAX_JSON_BYTES);
    }

    /** Latest exact revision, not latest valid report: an invalid newest row cannot fall back to an older receipt. */
    Impact latestImpact(UUID id, long version, long maxBytes) {
        if (maxBytes < 0 || maxBytes > CatalogPublicationRepository.MAX_JSON_BYTES) throw new IllegalArgumentException("Invalid read budget");
        var r = CATALOG_IMPACT_REPORTS; var e = CATALOG_IMPACT_REPORT_EVENTS;
        var length = DSL.octetLength(r.REPORT.cast(String.class)).cast(Long.class);
        var bytes = length.as("report_bytes");
        var body = DSL.when(length.le(maxBytes), r.REPORT).otherwise((JSONB) null).as("bounded_report");
        var row = dsl.select(r.ID, r.REPORT_NUMBER, r.PROPOSAL_ID, r.PROPOSAL_VERSION, r.PROPOSAL_SHA256,
                        r.REPORT_SCHEMA_VERSION, r.CANONICALIZATION_VERSION, r.REPORT_SHA256, r.RECORDED_AT, body, bytes,
                        e.ID, e.REPORT_ID, e.PROPOSAL_ID, e.PROPOSAL_VERSION, e.REPORT_SHA256, e.ACTION, e.ACTOR_TYPE,
                        e.ACTOR_ID, e.CORRELATION_ID, e.OUTCOME, e.OCCURRED_AT)
                .from(r).leftJoin(e).on(e.REPORT_ID.eq(r.ID))
                .where(r.PROPOSAL_ID.eq(id).and(r.PROPOSAL_VERSION.eq(version)))
                .orderBy(r.REPORT_NUMBER.desc()).limit(1).fetchOne();
        if (row == null) return null;
        var json = row.get(body);
        var event = row.get(e.ID) == null ? null : new CatalogImpactReportEvent(row.get(e.ID), row.get(e.REPORT_ID),
                row.get(e.PROPOSAL_ID), row.get(e.PROPOSAL_VERSION), row.get(e.REPORT_SHA256), row.get(e.ACTION),
                row.get(e.ACTOR_TYPE), row.get(e.ACTOR_ID), row.get(e.CORRELATION_ID), row.get(e.OUTCOME), row.get(e.OCCURRED_AT).toInstant());
        return new Impact(row.get(r.ID), row.get(r.REPORT_NUMBER), row.get(r.PROPOSAL_ID), row.get(r.PROPOSAL_VERSION),
                row.get(r.PROPOSAL_SHA256), row.get(r.REPORT_SCHEMA_VERSION), row.get(r.CANONICALIZATION_VERSION), row.get(r.REPORT_SHA256),
                row.get(r.RECORDED_AT).toInstant(), json == null ? null : json.data(), row.get(bytes), event);
    }
}
