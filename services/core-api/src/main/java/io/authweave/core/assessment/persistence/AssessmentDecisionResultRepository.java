package io.authweave.core.assessment.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import io.authweave.core.assessment.result.AssessmentDecisionResultRequest.Reference;
import static io.authweave.core.generated.jooq.tables.Assessments.ASSESSMENTS;
import static io.authweave.core.generated.jooq.tables.AssessmentRevisions.ASSESSMENT_REVISIONS;
import static io.authweave.core.generated.jooq.tables.AssessmentDecisionResults.ASSESSMENT_DECISION_RESULTS;
import static io.authweave.core.generated.audit.tables.AssessmentDecisionResultEvents.ASSESSMENT_DECISION_RESULT_EVENTS;
import static io.authweave.core.assessment.result.AssessmentDecisionResultException.Reason.*;
import io.authweave.core.assessment.result.AssessmentDecisionResultException;

/** Bounded exact reads; the profile lock serializes appends with both result and profile writers. */
@Repository
public class AssessmentDecisionResultRepository {
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    private final DSLContext dsl;
    public AssessmentDecisionResultRepository(DSLContext dsl) { this.dsl = dsl; }
    record Profile(short schemaVersion, JSONB json, String status, long version) { }
    record Event(UUID id, UUID resultId, UUID workspaceId, UUID assessmentId, long version, String requestSha256,
            String resultSha256, String issuer, String subject, String action, UUID correlationId, String outcome, Instant occurredAt) { }
    record Row(UUID id, UUID workspaceId, UUID assessmentId, long version, long assessmentVersion, String requestSha256,
            String resultSha256, Reference previous, UUID snapshotId, String catalogVersion, String snapshotSha256,
            String body, long bytes, Instant recordedAt, Event event) { }

    Profile lockProfile(UUID workspace, UUID assessment) {
        var a = ASSESSMENTS;
        var size = DSL.octetLength(a.PROFILE.cast(String.class)).cast(Long.class);
        var body = DSL.when(size.le(MAX_BYTES), a.PROFILE).otherwise((JSONB) null).as("bounded_profile");
        var row = dsl.select(a.PROFILE_SCHEMA_VERSION, a.STATUS, a.LOCK_VERSION, body).from(a)
                .where(a.WORKSPACE_ID.eq(workspace).and(a.ID.eq(assessment))).forUpdate().fetchOne();
        if (row == null) throw new AssessmentDecisionResultException(NOT_FOUND);
        if (row.get(body) == null) throw new AssessmentDecisionResultException(TOO_LARGE);
        return new Profile(row.get(a.PROFILE_SCHEMA_VERSION), row.get(body), row.get(a.STATUS), row.get(a.LOCK_VERSION));
    }
    Profile revision(UUID workspace, UUID assessment, long version) {
        var r = ASSESSMENT_REVISIONS;
        var size = DSL.octetLength(r.PROFILE.cast(String.class)).cast(Long.class);
        var body = DSL.when(size.le(MAX_BYTES), r.PROFILE).otherwise((JSONB) null).as("bounded_profile");
        var row = dsl.select(r.PROFILE_SCHEMA_VERSION, r.STATUS, r.VERSION, body).from(r)
                .where(r.WORKSPACE_ID.eq(workspace).and(r.ASSESSMENT_ID.eq(assessment)).and(r.VERSION.eq(version))).fetchOne();
        if (row == null || row.get(body) == null) throw new AssessmentDecisionResultException(READ_UNAVAILABLE);
        return new Profile(row.get(r.PROFILE_SCHEMA_VERSION), row.get(body), row.get(r.STATUS), row.get(r.VERSION));
    }
    Reference latest(UUID workspace, UUID assessment) {
        var r = ASSESSMENT_DECISION_RESULTS;
        var row = dsl.select(r.ID, r.VERSION, r.RESULT_SHA256).from(r)
                .where(r.WORKSPACE_ID.eq(workspace).and(r.ASSESSMENT_ID.eq(assessment))).orderBy(r.VERSION.desc()).limit(1).fetchOne();
        return row == null ? null : new Reference(row.get(r.ID), row.get(r.VERSION), row.get(r.RESULT_SHA256));
    }
    boolean keyUsedElsewhere(UUID id, UUID workspace, UUID assessment) {
        var r = ASSESSMENT_DECISION_RESULTS;
        return dsl.fetchExists(r, r.ID.eq(id).and(r.WORKSPACE_ID.ne(workspace).or(r.ASSESSMENT_ID.ne(assessment))));
    }
    Row find(UUID workspace, UUID assessment, UUID id) {
        var r = ASSESSMENT_DECISION_RESULTS;
        return find(r.ID.eq(id).and(r.WORKSPACE_ID.eq(workspace)).and(r.ASSESSMENT_ID.eq(assessment)), MAX_BYTES);
    }
    Row find(UUID id) { return find(id, MAX_BYTES); }
    Row find(UUID id, long maxBytes) {
        return find(ASSESSMENT_DECISION_RESULTS.ID.eq(id), maxBytes);
    }
    private Row find(Condition condition, long maxBytes) {
        if (maxBytes < 0 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid read budget");
        var r = ASSESSMENT_DECISION_RESULTS; var e = ASSESSMENT_DECISION_RESULT_EVENTS;
        var length = DSL.octetLength(r.RESULT.cast(String.class)).cast(Long.class); var bytes = length.as("result_bytes");
        var body = DSL.when(length.le(maxBytes), r.RESULT).otherwise((JSONB) null).as("bounded_result");
        var row = dsl.select(Arrays.stream(r.fields()).filter(f -> !f.equals(r.RESULT)).toList()).select(bytes, body).select(e.fields())
                .from(r).leftJoin(e).on(e.RESULT_ID.eq(r.ID)).where(condition).fetchOne();
        if (row == null) return null;
        var event = row.get(e.ID) == null ? null : new Event(row.get(e.ID), row.get(e.RESULT_ID), row.get(e.WORKSPACE_ID),
                row.get(e.ASSESSMENT_ID), row.get(e.VERSION), row.get(e.REQUEST_SHA256), row.get(e.RESULT_SHA256), row.get(e.ISSUER),
                row.get(e.SUBJECT), row.get(e.ACTION), row.get(e.CORRELATION_ID), row.get(e.OUTCOME), row.get(e.OCCURRED_AT).toInstant());
        var previous = row.get(r.PREVIOUS_RESULT_ID) == null ? null : new Reference(row.get(r.PREVIOUS_RESULT_ID),
                row.get(r.PREVIOUS_RESULT_VERSION), row.get(r.PREVIOUS_RESULT_SHA256));
        return new Row(row.get(r.ID), row.get(r.WORKSPACE_ID), row.get(r.ASSESSMENT_ID), row.get(r.VERSION), row.get(r.ASSESSMENT_VERSION),
                row.get(r.REQUEST_SHA256), row.get(r.RESULT_SHA256), previous, row.get(r.SNAPSHOT_ID), row.get(r.CATALOG_VERSION), row.get(r.SNAPSHOT_SHA256),
                row.get(body) == null ? null : row.get(body).data(), row.get(bytes), row.get(r.RECORDED_AT).toInstant(), event);
    }
    Instant now() { return dsl.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class).toInstant(); }
    void lockKey(UUID id) { dsl.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "authweave:assessment-result:" + id); }
    void size(String body) {
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES
                || dsl.fetchOne("select octet_length(?::jsonb::text)", body).get(0, Long.class) > MAX_BYTES)
            throw new AssessmentDecisionResultException(TOO_LARGE);
    }
    void insert(UUID workspace, UUID assessment, long version, io.authweave.core.assessment.result.AssessmentDecisionResultRequest request,
            String requestSha, String resultSha, String body, AssessmentDecisionResultService.Actor actor) {
        var r = ASSESSMENT_DECISION_RESULTS; var previous = request.previousResult(); var catalog = request.catalog();
        dsl.insertInto(r).set(r.ID, request.resultId()).set(r.WORKSPACE_ID, workspace).set(r.ASSESSMENT_ID, assessment)
                .set(r.VERSION, version).set(r.ASSESSMENT_VERSION, request.expectedAssessmentVersion()).set(r.SNAPSHOT_ID, catalog.snapshotId())
                .set(r.CATALOG_VERSION, catalog.catalogVersion()).set(r.SNAPSHOT_SHA256, catalog.snapshotSha256())
                .set(r.REQUEST_SHA256, requestSha).set(r.RESULT_SHA256, resultSha).set(r.RESULT, JSONB.jsonb(body))
                .set(r.PREVIOUS_RESULT_ID, previous == null ? null : previous.resultId())
                .set(r.PREVIOUS_RESULT_VERSION, previous == null ? null : previous.version())
                .set(r.PREVIOUS_RESULT_SHA256, previous == null ? null : previous.resultSha256()).execute();
        var e = ASSESSMENT_DECISION_RESULT_EVENTS;
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.RESULT_ID, request.resultId()).set(e.WORKSPACE_ID, workspace)
                .set(e.ASSESSMENT_ID, assessment).set(e.VERSION, version).set(e.REQUEST_SHA256, requestSha).set(e.RESULT_SHA256, resultSha)
                .set(e.ISSUER, actor.issuer()).set(e.SUBJECT, actor.subject()).set(e.ACTION, action(version))
                .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
    }
    static String action(long version) { return version == 1 ? "assessment-decision.recorded" : "assessment-decision.reevaluated"; }
}
