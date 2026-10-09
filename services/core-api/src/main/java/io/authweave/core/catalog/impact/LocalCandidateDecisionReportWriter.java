package io.authweave.core.catalog.impact;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static io.authweave.core.generated.jooq.tables.CandidateDecisionReports.CANDIDATE_DECISION_REPORTS;
import static io.authweave.core.generated.audit.tables.CandidateDecisionReportEvents.CANDIDATE_DECISION_REPORT_EVENTS;
import static io.authweave.core.catalog.impact.CandidateDecisionReportException.Reason.*;

/** Opt-in local internal writer, no HTTP route, caller result, clock, role grant or publication mutation. */
@Service
@Profile("local-candidate-decision-write")
public class LocalCandidateDecisionReportWriter {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private final CandidateDecisionReportRepository repository;
    private final CandidateDecisionReportService reports;
    public LocalCandidateDecisionReportWriter(DSLContext dsl, ObjectMapper mapper, CandidateDecisionReportRepository repository,
            CandidateDecisionReportService reports) { this.dsl = dsl; this.mapper = mapper; this.repository = repository; this.reports = reports; }
    public record SaveResult(boolean created, CandidateDecisionReportService.Receipt receipt) { }
    @Transactional
    public SaveResult save(UUID reportId, String expectedInputSha256, CandidateDecisionReportService.Request request) {
        Objects.requireNonNull(reportId); Objects.requireNonNull(request); CandidateDecisionReportService.digest(expectedInputSha256);
        if (!expectedInputSha256.equals(CandidateDecisionReportService.inputSha256(request))) throw new CandidateDecisionReportException(CONFLICT);
        // READ COMMITTED after this lock observes the first writer's committed receipt on an idempotent race.
        dsl.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "authweave:candidate-decision-report:" + reportId);
        var existing = repository.find(reportId);
        if (existing != null) {
            if (!expectedInputSha256.equals(existing.inputSha256())) throw new CandidateDecisionReportException(CONFLICT);
            return new SaveResult(false, reports.verify(existing));
        }
        var at = dsl.select(org.jooq.impl.DSL.field("clock_timestamp()", OffsetDateTime.class)).fetchOne(0, OffsetDateTime.class).toInstant();
        var result = reports.compute(request, at); var tree = mapper.valueToTree(result);
        var body = mapper.writeValueAsString(tree); var hash = DecisionCanonicalizer.sha256(tree);
        if (body.getBytes(StandardCharsets.UTF_8).length > CandidateDecisionReportRepository.MAX_BYTES
                || dsl.fetchOne("select octet_length(?::jsonb::text)", body).get(0, Long.class) > CandidateDecisionReportRepository.MAX_BYTES)
            throw new CandidateDecisionReportException(REPORT_TOO_LARGE);
        var r = CANDIDATE_DECISION_REPORTS;
        dsl.insertInto(r).set(r.ID, reportId).set(r.INPUT_SHA256, expectedInputSha256).set(r.REPORT_SHA256, hash)
                .set(r.BEFORE_REVIEW_ID, request.before().reviewId()).set(r.AFTER_REVIEW_ID, request.after().reviewId())
                .set(r.BEFORE_AUDIT_REVIEW_ID, request.before().auditability() == null ? null : request.before().auditability().reviewId())
                .set(r.AFTER_AUDIT_REVIEW_ID, request.after().auditability() == null ? null : request.after().auditability().reviewId())
                .set(r.REPORT, JSONB.jsonb(body)).execute();
        var e = CANDIDATE_DECISION_REPORT_EVENTS;
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REPORT_ID, reportId).set(e.INPUT_SHA256, expectedInputSha256).set(e.REPORT_SHA256, hash)
                .set(e.ACTION, "candidate-decision.recorded").set(e.ACTOR_TYPE, "SERVICE").set(e.ACTOR_ID, "core-api-local-catalog")
                .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new SaveResult(true, reports.verify(repository.find(reportId)));
    }
}
