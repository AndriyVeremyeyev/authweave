package io.authweave.core.assessment.persistence;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.domain.Assessment;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.generated.jooq.tables.records.AssessmentsRecord;

import static io.authweave.core.generated.jooq.tables.Assessments.ASSESSMENTS;
import static org.jooq.impl.DSL.currentOffsetDateTime;

@Repository
public class JooqAssessmentRepository implements AssessmentRepository {

    private final DSLContext dsl;
    private final AssessmentProfileJsonCodec profileJsonCodec;
    private final JooqAssessmentHistoryRepository history;

    public JooqAssessmentRepository(
            DSLContext dsl,
            AssessmentProfileJsonCodec profileJsonCodec,
            JooqAssessmentHistoryRepository history) {
        this.dsl = dsl;
        this.profileJsonCodec = profileJsonCodec;
        this.history = history;
    }

    @Override
    @Transactional
    public PersistedAssessment insert(Assessment assessment) {
        AssessmentsRecord record = Objects.requireNonNull(
                dsl.insertInto(ASSESSMENTS)
                        .set(ASSESSMENTS.WORKSPACE_ID, assessment.workspaceId().value())
                        .set(ASSESSMENTS.ID, assessment.id().value())
                        .set(ASSESSMENTS.STATUS, assessment.status().name())
                        .set(ASSESSMENTS.PROFILE_SCHEMA_VERSION, profileJsonCodec.schemaVersion(assessment.profile()))
                        .set(ASSESSMENTS.PROFILE, profileJsonCodec.encode(assessment.profile()))
                        .returning()
                        .fetchOne(),
                "insert did not return an assessment record");
        history.append(null, record);
        return toPersistedAssessment(record);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PersistedAssessment> findById(
            WorkspaceId workspaceId,
            AssessmentId assessmentId) {
        return dsl.selectFrom(ASSESSMENTS)
                .where(ASSESSMENTS.WORKSPACE_ID.eq(workspaceId.value()))
                .and(ASSESSMENTS.ID.eq(assessmentId.value()))
                .fetchOptional(this::toPersistedAssessment);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(WorkspaceId workspaceId, AssessmentId assessmentId) {
        return dsl.fetchExists(dsl.selectOne().from(ASSESSMENTS)
                .where(ASSESSMENTS.WORKSPACE_ID.eq(workspaceId.value()))
                .and(ASSESSMENTS.ID.eq(assessmentId.value())));
    }

    @Override
    @Transactional
    public PersistedAssessment update(Assessment assessment, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }

        AssessmentsRecord previous = dsl.selectFrom(ASSESSMENTS)
                .where(ASSESSMENTS.WORKSPACE_ID.eq(assessment.workspaceId().value()))
                .and(ASSESSMENTS.ID.eq(assessment.id().value()))
                .and(ASSESSMENTS.LOCK_VERSION.eq(expectedVersion))
                .fetchOne();
        if (previous == null) {
            throw conflictOrNotFound(assessment, expectedVersion);
        }

        AssessmentsRecord record = dsl.update(ASSESSMENTS)
                .set(ASSESSMENTS.STATUS, assessment.status().name())
                .set(ASSESSMENTS.PROFILE_SCHEMA_VERSION, profileJsonCodec.schemaVersion(assessment.profile()))
                .set(ASSESSMENTS.PROFILE, profileJsonCodec.encode(assessment.profile()))
                .set(ASSESSMENTS.LOCK_VERSION, ASSESSMENTS.LOCK_VERSION.plus(1L))
                .set(ASSESSMENTS.UPDATED_AT, currentOffsetDateTime())
                .where(ASSESSMENTS.WORKSPACE_ID.eq(assessment.workspaceId().value()))
                .and(ASSESSMENTS.ID.eq(assessment.id().value()))
                .and(ASSESSMENTS.LOCK_VERSION.eq(expectedVersion))
                .returning()
                .fetchOne();

        if (record != null) {
            history.append(previous, record);
            return toPersistedAssessment(record);
        }

        throw conflictOrNotFound(assessment, expectedVersion);
    }

    private AssessmentVersionConflictException conflictOrNotFound(Assessment assessment, long expectedVersion) {
        Long actualVersion = dsl.select(ASSESSMENTS.LOCK_VERSION)
                .from(ASSESSMENTS)
                .where(ASSESSMENTS.WORKSPACE_ID.eq(assessment.workspaceId().value()))
                .and(ASSESSMENTS.ID.eq(assessment.id().value()))
                .fetchOne(ASSESSMENTS.LOCK_VERSION);
        if (actualVersion == null) {
            throw new AssessmentNotFoundException(assessment.workspaceId(), assessment.id());
        }
        return new AssessmentVersionConflictException(
                assessment.workspaceId(),
                assessment.id(),
                expectedVersion,
                actualVersion);
    }

    private PersistedAssessment toPersistedAssessment(AssessmentsRecord record) {
        short profileSchemaVersion = Objects.requireNonNull(
                record.getProfileSchemaVersion(),
                "profile schema version must not be null");
        OffsetDateTime createdAt = Objects.requireNonNull(
                record.getCreatedAt(),
                "createdAt must not be null");
        OffsetDateTime updatedAt = Objects.requireNonNull(
                record.getUpdatedAt(),
                "updatedAt must not be null");
        Assessment assessment = Assessment.rehydrate(
                new AssessmentId(Objects.requireNonNull(record.getId(), "id must not be null")),
                new WorkspaceId(Objects.requireNonNull(
                        record.getWorkspaceId(),
                        "workspaceId must not be null")),
                AssessmentStatus.valueOf(Objects.requireNonNull(
                        record.getStatus(),
                        "status must not be null")),
                profileJsonCodec.decode(Objects.requireNonNull(
                        record.getProfile(),
                        "profile must not be null"), profileSchemaVersion));
        return new PersistedAssessment(
                assessment,
                Objects.requireNonNull(record.getLockVersion(), "lockVersion must not be null"),
                createdAt.toInstant(),
                updatedAt.toInstant());
    }
}
