package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapImpactReports.CATALOG_BOOTSTRAP_IMPACT_REPORTS;
import static io.authweave.core.generated.audit.tables.CatalogBootstrapImpactReportEvents.CATALOG_BOOTSTRAP_IMPACT_REPORT_EVENTS;

/** Bounded latest exact-review reads; never filters validity/digests, falls back, refreshes or writes. */
@Repository
@Transactional(readOnly = true)
public class CatalogBootstrapImpactReportRepository {
    public static final long MAX_JSON_BYTES = 32L * 1024 * 1024;
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public CatalogBootstrapImpactReportRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }
    public record Event(UUID id, UUID reportId, UUID reviewId, String candidateSha256, String reviewSha256, String reportSha256,
            String action, String actorType, String actorId, UUID correlationId, String outcome, Instant occurredAt) { }
    public record Row(UUID id, long number, UUID reviewId, String candidateSha256, String reviewSha256, int schemaVersion,
            String canonicalizationVersion, String reportSha256, Instant recordedAt, String report, long reportBytes, Event event) { }

    Row find(UUID id) { return read(CATALOG_BOOTSTRAP_IMPACT_REPORTS.ID.eq(id), MAX_JSON_BYTES); }
    public Row latest(UUID reviewId) { return latest(reviewId, MAX_JSON_BYTES); }
    public Row latest(UUID reviewId, long maxBytes) {
        if (maxBytes < 0 || maxBytes > MAX_JSON_BYTES) throw new IllegalArgumentException("Invalid read budget");
        return read(CATALOG_BOOTSTRAP_IMPACT_REPORTS.REVIEW_ID.eq(reviewId), maxBytes);
    }
    public CatalogBootstrapImpactReport get(UUID reviewId, String reviewSha256, UUID reportId) {
        var row = find(reportId);
        if (row == null || !reviewId.equals(row.reviewId()) || !reviewSha256.equals(row.reviewSha256()))
            throw new CatalogBootstrapImpactReportException(CatalogBootstrapImpactReportException.Reason.NOT_FOUND);
        return snapshot(row);
    }
    CatalogBootstrapImpactReport snapshot(Row row) {
        if (row.reportBytes() <= 0 || row.reportBytes() > MAX_JSON_BYTES || row.report() == null)
            throw new CatalogBootstrapImpactReportException(CatalogBootstrapImpactReportException.Reason.READ_BUDGET_EXCEEDED);
        return new CatalogBootstrapImpactReport(row.id(), row.number(), row.reviewId(), row.candidateSha256(), row.reviewSha256(),
                row.schemaVersion(), row.canonicalizationVersion(), row.reportSha256(), row.recordedAt(), mapper.readTree(row.report()));
    }
    private Row read(Condition where, long maxBytes) {
        var r = CATALOG_BOOTSTRAP_IMPACT_REPORTS; var e = CATALOG_BOOTSTRAP_IMPACT_REPORT_EVENTS;
        var length = DSL.octetLength(r.REPORT.cast(String.class)).cast(Long.class); var bytes = length.as("report_bytes");
        var body = DSL.when(length.le(maxBytes), r.REPORT).otherwise((JSONB) null).as("bounded_report");
        var row = dsl.select(r.ID, r.REPORT_NUMBER, r.REVIEW_ID, r.CANDIDATE_SHA256, r.REVIEW_SHA256, r.REPORT_SCHEMA_VERSION,
                r.CANONICALIZATION_VERSION, r.REPORT_SHA256, r.RECORDED_AT, body, bytes,
                e.ID, e.REPORT_ID, e.REVIEW_ID, e.CANDIDATE_SHA256, e.REVIEW_SHA256, e.REPORT_SHA256,
                e.ACTION, e.ACTOR_TYPE, e.ACTOR_ID, e.CORRELATION_ID, e.OUTCOME, e.OCCURRED_AT)
                .from(r).leftJoin(e).on(e.REPORT_ID.eq(r.ID)).where(where).orderBy(r.REPORT_NUMBER.desc()).limit(1).fetchOne();
        if (row == null) return null;
        var event = row.get(e.ID) == null ? null : new Event(row.get(e.ID), row.get(e.REPORT_ID), row.get(e.REVIEW_ID),
                row.get(e.CANDIDATE_SHA256), row.get(e.REVIEW_SHA256), row.get(e.REPORT_SHA256), row.get(e.ACTION), row.get(e.ACTOR_TYPE),
                row.get(e.ACTOR_ID), row.get(e.CORRELATION_ID), row.get(e.OUTCOME), row.get(e.OCCURRED_AT).toInstant());
        return new Row(row.get(r.ID), row.get(r.REPORT_NUMBER), row.get(r.REVIEW_ID), row.get(r.CANDIDATE_SHA256), row.get(r.REVIEW_SHA256),
                row.get(r.REPORT_SCHEMA_VERSION), row.get(r.CANONICALIZATION_VERSION), row.get(r.REPORT_SHA256), row.get(r.RECORDED_AT).toInstant(),
                row.get(body) == null ? null : row.get(body).data(), row.get(bytes), event);
    }
}
