package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import static io.authweave.core.generated.jooq.tables.CandidateDecisionReports.CANDIDATE_DECISION_REPORTS;
import static io.authweave.core.generated.audit.tables.CandidateDecisionReportEvents.CANDIDATE_DECISION_REPORT_EVENTS;

/** Exact-ID bounded reads. Never selects another valid receipt when this one is invalid. */
@Repository
public class CandidateDecisionReportRepository {
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    private final DSLContext dsl;
    public CandidateDecisionReportRepository(DSLContext dsl) { this.dsl = dsl; }
    record Event(UUID id, UUID reportId, String inputSha256, String reportSha256, String action, String actorType,
            String actorId, UUID correlationId, String outcome, Instant occurredAt) { }
    record Row(UUID id, String inputSha256, String reportSha256, UUID beforeReviewId, UUID afterReviewId, UUID beforeAuditReviewId, UUID afterAuditReviewId,
            String body, long bytes, Instant recordedAt, Event event) { }
    Row find(UUID id) { return find(id, MAX_BYTES); }
    Row find(UUID id, long maxBytes) {
        if (maxBytes < 0 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid read budget");
        var r = CANDIDATE_DECISION_REPORTS; var e = CANDIDATE_DECISION_REPORT_EVENTS;
        var length = DSL.octetLength(r.REPORT.cast(String.class)).cast(Long.class); var bytes = length.as("report_bytes");
        var body = DSL.when(length.le(maxBytes), r.REPORT).otherwise((JSONB) null).as("bounded_report");
        var row = dsl.select(r.ID, r.INPUT_SHA256, r.REPORT_SHA256, r.BEFORE_REVIEW_ID, r.AFTER_REVIEW_ID, r.BEFORE_AUDIT_REVIEW_ID, r.AFTER_AUDIT_REVIEW_ID, r.RECORDED_AT, bytes, body)
                .select(e.fields()).from(r).leftJoin(e).on(e.REPORT_ID.eq(r.ID)).where(r.ID.eq(id)).fetchOne();
        if (row == null) return null;
        var event = row.get(e.ID) == null ? null : new Event(row.get(e.ID), row.get(e.REPORT_ID), row.get(e.INPUT_SHA256), row.get(e.REPORT_SHA256),
                row.get(e.ACTION), row.get(e.ACTOR_TYPE), row.get(e.ACTOR_ID), row.get(e.CORRELATION_ID), row.get(e.OUTCOME), row.get(e.OCCURRED_AT).toInstant());
        return new Row(row.get(r.ID), row.get(r.INPUT_SHA256), row.get(r.REPORT_SHA256), row.get(r.BEFORE_REVIEW_ID), row.get(r.AFTER_REVIEW_ID),
                row.get(r.BEFORE_AUDIT_REVIEW_ID), row.get(r.AFTER_AUDIT_REVIEW_ID),
                row.get(body) == null ? null : row.get(body).data(), row.get(bytes), row.get(r.RECORDED_AT).toInstant(), event);
    }
}
