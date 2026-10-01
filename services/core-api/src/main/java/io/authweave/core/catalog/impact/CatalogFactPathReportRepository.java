package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static io.authweave.core.generated.jooq.tables.CatalogFactPathReports.CATALOG_FACT_PATH_REPORTS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.audit.tables.CatalogFactPathReportEvents.CATALOG_FACT_PATH_REPORT_EVENTS;

/** Exact bounded reads only; no source fetch, historical reinterpretation, fallback or implicit report creation. */
@Repository
@Transactional(readOnly = true)
public class CatalogFactPathReportRepository {
    public static final long MAX_JSON_BYTES = 32L * 1024 * 1024;
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public CatalogFactPathReportRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }
    public record Request(UUID proposalId, long version, String state, int schemaVersion, String sha256, Instant recordedAt,
            String body, long bytes) { }
    public record Row(UUID id, long number, UUID proposalId, long version, String proposalSha256, int schemaVersion,
            String canonicalizationVersion, String reportSha256, Instant recordedAt, String report, long reportBytes,
            CatalogImpactReportEvent event) { }

    Request request(UUID id, long version) { return request(id, version, MAX_JSON_BYTES); }
    Request request(UUID id, long version, long maxBytes) {
        CatalogImpactReportRepository.version(version); budget(maxBytes);
        var r = CATALOG_PROPOSAL_REVISIONS; var length = DSL.octetLength(r.REQUEST.cast(String.class)).cast(Long.class);
        var bytes = length.as("request_bytes"); var body = DSL.when(length.le(maxBytes), r.REQUEST).otherwise((JSONB) null).as("bounded_request");
        var row = dsl.select(r.PROPOSAL_ID, r.VERSION, r.STATE, r.REQUEST_SCHEMA_VERSION, r.PROPOSAL_SHA256, r.RECORDED_AT, body, bytes)
                .from(r).where(r.PROPOSAL_ID.eq(id).and(r.VERSION.eq(version))).fetchOne();
        if (row == null) return null;
        return new Request(row.get(r.PROPOSAL_ID), row.get(r.VERSION), row.get(r.STATE), row.get(r.REQUEST_SCHEMA_VERSION),
                row.get(r.PROPOSAL_SHA256), row.get(r.RECORDED_AT).toInstant(), row.get(body) == null ? null : row.get(body).data(), row.get(bytes));
    }
    Row find(UUID id) { return read(CATALOG_FACT_PATH_REPORTS.ID.eq(id), MAX_JSON_BYTES); }
    public Row latest(UUID proposalId, long version) { return latest(proposalId, version, MAX_JSON_BYTES); }
    public Row latest(UUID proposalId, long version, long maxBytes) {
        CatalogImpactReportRepository.version(version); budget(maxBytes);
        var r = CATALOG_FACT_PATH_REPORTS;
        return read(r.PROPOSAL_ID.eq(proposalId).and(r.PROPOSAL_VERSION.eq(version)), maxBytes);
    }
    public CatalogImpactReport get(UUID proposalId, long version, UUID reportId) {
        CatalogImpactReportRepository.version(version); var row = find(reportId);
        if (row == null || !proposalId.equals(row.proposalId()) || version != row.version()) throw new CatalogFactPathReportException(CatalogFactPathReportException.Reason.NOT_FOUND);
        return snapshot(row);
    }
    CatalogImpactReport snapshot(Row row) {
        if (row.reportBytes() <= 0 || row.reportBytes() > MAX_JSON_BYTES || row.report() == null)
            throw new CatalogFactPathReportException(CatalogFactPathReportException.Reason.READ_BUDGET_EXCEEDED);
        return new CatalogImpactReport(row.id(), row.number(), row.proposalId(), row.version(), row.schemaVersion(), row.canonicalizationVersion(),
                row.proposalSha256(), row.reportSha256(), row.recordedAt(), mapper.readTree(row.report()));
    }
    private Row read(Condition where, long maxBytes) {
        var r = CATALOG_FACT_PATH_REPORTS; var e = CATALOG_FACT_PATH_REPORT_EVENTS;
        var length = DSL.octetLength(r.REPORT.cast(String.class)).cast(Long.class); var bytes = length.as("report_bytes");
        var body = DSL.when(length.le(maxBytes), r.REPORT).otherwise((JSONB) null).as("bounded_report");
        var row = dsl.select(r.ID, r.REPORT_NUMBER, r.PROPOSAL_ID, r.PROPOSAL_VERSION, r.PROPOSAL_SHA256, r.REPORT_SCHEMA_VERSION,
                r.CANONICALIZATION_VERSION, r.REPORT_SHA256, r.RECORDED_AT, body, bytes, e.ID, e.REPORT_ID, e.PROPOSAL_ID,
                e.PROPOSAL_VERSION, e.REPORT_SHA256, e.ACTION, e.ACTOR_TYPE, e.ACTOR_ID, e.CORRELATION_ID, e.OUTCOME, e.OCCURRED_AT)
                .from(r).leftJoin(e).on(e.REPORT_ID.eq(r.ID)).where(where).orderBy(r.REPORT_NUMBER.desc()).limit(1).fetchOne();
        if (row == null) return null;
        var event = row.get(e.ID) == null ? null : new CatalogImpactReportEvent(row.get(e.ID), row.get(e.REPORT_ID), row.get(e.PROPOSAL_ID),
                row.get(e.PROPOSAL_VERSION), row.get(e.REPORT_SHA256), row.get(e.ACTION), row.get(e.ACTOR_TYPE), row.get(e.ACTOR_ID),
                row.get(e.CORRELATION_ID), row.get(e.OUTCOME), row.get(e.OCCURRED_AT).toInstant());
        return new Row(row.get(r.ID), row.get(r.REPORT_NUMBER), row.get(r.PROPOSAL_ID), row.get(r.PROPOSAL_VERSION), row.get(r.PROPOSAL_SHA256),
                row.get(r.REPORT_SCHEMA_VERSION), row.get(r.CANONICALIZATION_VERSION), row.get(r.REPORT_SHA256), row.get(r.RECORDED_AT).toInstant(),
                row.get(body) == null ? null : row.get(body).data(), row.get(bytes), event);
    }
    private static void budget(long value) { if (value < 0 || value > MAX_JSON_BYTES) throw new IllegalArgumentException("Invalid read budget"); }
}
