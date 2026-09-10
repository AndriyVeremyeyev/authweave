package io.authweave.core.assessment.persistence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.generated.jooq.tables.records.AssessmentsRecord;

import static io.authweave.core.generated.jooq.tables.AssessmentRevisions.ASSESSMENT_REVISIONS;
import static io.authweave.core.generated.audit.tables.AssessmentEvents.ASSESSMENT_EVENTS;

@Repository
public class JooqAssessmentHistoryRepository implements AssessmentHistoryRepository {

    private final DSLContext dsl;
    private final AssessmentProfileJsonCodec codec;

    public JooqAssessmentHistoryRepository(DSLContext dsl, AssessmentProfileJsonCodec codec) {
        this.dsl = dsl;
        this.codec = codec;
    }

    /** Called only after a successful insert/CAS, in the same transaction as that write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AssessmentsRecord previous, AssessmentsRecord current) {
        var origin = previous == null ? AssessmentRevision.Origin.CREATED : AssessmentRevision.Origin.UPDATED;
        dsl.insertInto(ASSESSMENT_REVISIONS)
                .set(ASSESSMENT_REVISIONS.WORKSPACE_ID, current.getWorkspaceId())
                .set(ASSESSMENT_REVISIONS.ASSESSMENT_ID, current.getId())
                .set(ASSESSMENT_REVISIONS.VERSION, current.getLockVersion())
                .set(ASSESSMENT_REVISIONS.STATUS, current.getStatus())
                .set(ASSESSMENT_REVISIONS.PROFILE_SCHEMA_VERSION, current.getProfileSchemaVersion())
                .set(ASSESSMENT_REVISIONS.PROFILE, current.getProfile())
                .set(ASSESSMENT_REVISIONS.ORIGIN, origin.name())
                .execute();

        // Until authentication is implemented, attribute writes to the service, not an invented user.
        dsl.insertInto(ASSESSMENT_EVENTS)
                .set(ASSESSMENT_EVENTS.ID, UUID.randomUUID())
                .set(ASSESSMENT_EVENTS.WORKSPACE_ID, current.getWorkspaceId())
                .set(ASSESSMENT_EVENTS.ASSESSMENT_ID, current.getId())
                .set(ASSESSMENT_EVENTS.VERSION, current.getLockVersion())
                .set(ASSESSMENT_EVENTS.PREVIOUS_VERSION, previous == null ? null : previous.getLockVersion())
                .set(ASSESSMENT_EVENTS.ACTION, previous == null ? "assessment.created" : "assessment.updated")
                .set(ASSESSMENT_EVENTS.ACTOR_TYPE, "SERVICE")
                .set(ASSESSMENT_EVENTS.ACTOR_ID, "core-api")
                .set(ASSESSMENT_EVENTS.CORRELATION_ID, UUID.randomUUID())
                .set(ASSESSMENT_EVENTS.OUTCOME, "SUCCEEDED")
                .set(ASSESSMENT_EVENTS.CHANGED_SECTIONS, changedSections(previous, current))
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public HistoryPage<AssessmentRevision> findRevisions(
            WorkspaceId workspaceId, AssessmentId assessmentId, Long afterVersion, int limit) {
        validatePage(afterVersion, limit);
        var rows = dsl.selectFrom(ASSESSMENT_REVISIONS)
                .where(ASSESSMENT_REVISIONS.WORKSPACE_ID.eq(workspaceId.value()))
                .and(ASSESSMENT_REVISIONS.ASSESSMENT_ID.eq(assessmentId.value()))
                .and(ASSESSMENT_REVISIONS.VERSION.gt(afterVersion == null ? -1L : afterVersion))
                .orderBy(ASSESSMENT_REVISIONS.VERSION.asc())
                .limit(limit + 1)
                .fetch(record -> {
                    if (record.getProfileSchemaVersion() != JooqAssessmentRepository.PROFILE_SCHEMA_VERSION) {
                        throw new UnsupportedAssessmentProfileVersionException(record.getProfileSchemaVersion());
                    }
                    return new AssessmentRevision(record.getWorkspaceId(), record.getAssessmentId(),
                            record.getVersion(), AssessmentStatus.valueOf(record.getStatus()),
                            record.getProfileSchemaVersion(), codec.decode(record.getProfile()),
                            AssessmentRevision.Origin.valueOf(record.getOrigin()), record.getRecordedAt().toInstant());
                });
        return HistoryPage.from(rows, limit, AssessmentRevision::version);
    }

    @Override
    @Transactional(readOnly = true)
    public HistoryPage<AssessmentEvent> findEvents(
            WorkspaceId workspaceId, AssessmentId assessmentId, Long afterVersion, int limit) {
        validatePage(afterVersion, limit);
        var rows = dsl.selectFrom(ASSESSMENT_EVENTS)
                .where(ASSESSMENT_EVENTS.WORKSPACE_ID.eq(workspaceId.value()))
                .and(ASSESSMENT_EVENTS.ASSESSMENT_ID.eq(assessmentId.value()))
                .and(ASSESSMENT_EVENTS.VERSION.gt(afterVersion == null ? -1L : afterVersion))
                .orderBy(ASSESSMENT_EVENTS.VERSION.asc())
                .limit(limit + 1)
                .fetch(record -> new AssessmentEvent(record.getId(), record.getWorkspaceId(),
                        record.getAssessmentId(), record.getVersion(), record.getPreviousVersion(),
                        record.getAction(), record.getActorType(), record.getActorId(), record.getCorrelationId(),
                        record.getOutcome(), Arrays.asList(record.getChangedSections()), record.getOccurredAt().toInstant()));
        return HistoryPage.from(rows, limit, AssessmentEvent::version);
    }

    private static void validatePage(Long afterVersion, int limit) {
        if (limit < 1 || limit > 100 || (afterVersion != null
                && (afterVersion < 0 || afterVersion > 9007199254740991L))) {
            throw new IllegalArgumentException("Invalid history page bounds");
        }
    }

    private String[] changedSections(AssessmentsRecord previous, AssessmentsRecord current) {
        if (previous == null) {
            return new String[] {"status", "application", "audience", "protocols", "provisioning", "security", "operations"};
        }
        List<String> changed = new ArrayList<>();
        if (!previous.getStatus().equals(current.getStatus())) changed.add("status");
        var before = codec.decode(previous.getProfile());
        var after = codec.decode(current.getProfile());
        if (!before.application().equals(after.application())) changed.add("application");
        if (!before.audience().equals(after.audience())) changed.add("audience");
        if (!before.protocols().equals(after.protocols())) changed.add("protocols");
        if (!before.provisioning().equals(after.provisioning())) changed.add("provisioning");
        if (!before.security().equals(after.security())) changed.add("security");
        if (!before.operations().equals(after.operations())) changed.add("operations");
        return changed.toArray(String[]::new);
    }
}
