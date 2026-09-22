package io.authweave.core.assessment.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.assessment.domain.Assessment;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.authweave.core.generated.jooq.tables.Assessments.ASSESSMENTS;

@SpringBootTest
class JooqAssessmentRepositoryIntegrationTests extends PostgresIntegrationTest {

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DSLContext dsl;

    @Test
    void listsOnlyOneWorkspaceWithStableKeysetPaginationAcrossTimestampTies() {
        WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
        WorkspaceId other = new WorkspaceId(UUID.randomUUID());
        workspaceRepository.insertIfAbsent(workspace);
        workspaceRepository.insertIfAbsent(other);
        UUID first = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("20000000-0000-4000-8000-000000000001");
        UUID third = UUID.fromString("30000000-0000-4000-8000-000000000001");
        for (UUID id : List.of(first, second, third)) {
            assessmentRepository.insert(Assessment.createDraft(new AssessmentId(id), workspace));
        }
        assessmentRepository.insert(Assessment.createDraft(new AssessmentId(UUID.randomUUID()), other));
        OffsetDateTime sameTime = OffsetDateTime.parse("2026-09-22T12:00:00Z");
        dsl.update(ASSESSMENTS).set(ASSESSMENTS.CREATED_AT, sameTime)
                .set(ASSESSMENTS.UPDATED_AT, sameTime)
                .where(ASSESSMENTS.WORKSPACE_ID.eq(workspace.value())).execute();

        AssessmentListPage firstPage = assessmentRepository.list(workspace, null, 2);
        assertEquals(List.of(third, second), firstPage.items().stream().map(AssessmentListItem::id).toList());
        assertEquals(second, firstPage.nextBeforeId());
        AssessmentListPage secondPage = assessmentRepository.list(workspace, second, 2);
        assertEquals(List.of(first), secondPage.items().stream().map(AssessmentListItem::id).toList());
        assertNull(secondPage.nextBeforeId());
        assertEquals(List.of(), assessmentRepository.list(other, null, 1).items().stream()
                .filter(item -> List.of(first, second, third).contains(item.id())).toList());
        assertThrows(AssessmentNotFoundException.class,
                () -> assessmentRepository.list(other, second, 1));
        assertThrows(IllegalArgumentException.class,
                () -> assessmentRepository.list(workspace, null, 0));
    }

    @Test
    void persistsAndLoadsAnAssessmentInsideItsWorkspaceBoundary() {
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        WorkspaceId otherWorkspaceId = new WorkspaceId(UUID.randomUUID());
        workspaceRepository.insertIfAbsent(workspaceId);
        workspaceRepository.insertIfAbsent(otherWorkspaceId);
        Assessment assessment = Assessment.createDraft(
                new AssessmentId(UUID.randomUUID()),
                workspaceId);

        PersistedAssessment inserted = assessmentRepository.insert(assessment);
        PersistedAssessment loaded = assessmentRepository.findById(
                        workspaceId,
                        assessment.id())
                .orElseThrow();

        assertAll(
                () -> assertEquals(0, inserted.version()),
                () -> assertEquals(assessment.id(), loaded.assessment().id()),
                () -> assertEquals(workspaceId, loaded.assessment().workspaceId()),
                () -> assertEquals(AssessmentStatus.DRAFT, loaded.assessment().status()),
                () -> assertEquals(
                        ApplicationIdentityProfile.unknown(),
                        loaded.assessment().profile()),
                () -> assertFalse(loaded.updatedAt().isBefore(loaded.createdAt())),
                () -> assertTrue(assessmentRepository.findById(
                                otherWorkspaceId,
                                assessment.id())
                        .isEmpty()));
    }

    @Test
    void rejectsAStaleUpdateAndReportsTheCurrentVersion() {
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        workspaceRepository.insertIfAbsent(workspaceId);
        Assessment assessment = Assessment.createDraft(
                new AssessmentId(UUID.randomUUID()),
                workspaceId);
        assessmentRepository.insert(assessment);

        PersistedAssessment firstWriter = assessmentRepository.findById(
                        workspaceId,
                        assessment.id())
                .orElseThrow();
        PersistedAssessment staleWriter = assessmentRepository.findById(
                        workspaceId,
                        assessment.id())
                .orElseThrow();

        firstWriter.assessment().archive();
        PersistedAssessment updated = assessmentRepository.update(
                firstWriter.assessment(),
                firstWriter.version());

        staleWriter.assessment().archive();
        AssessmentVersionConflictException conflict = assertThrows(
                AssessmentVersionConflictException.class,
                () -> assessmentRepository.update(
                        staleWriter.assessment(),
                        staleWriter.version()));

        assertAll(
                () -> assertEquals(1, updated.version()),
                () -> assertEquals(AssessmentStatus.ARCHIVED, updated.assessment().status()),
                () -> assertFalse(updated.updatedAt().isBefore(updated.createdAt())),
                () -> assertEquals(workspaceId, conflict.workspaceId()),
                () -> assertEquals(assessment.id(), conflict.assessmentId()),
                () -> assertEquals(0, conflict.expectedVersion()),
                () -> assertEquals(1, conflict.actualVersion()));
    }

    @Test
    void treatsAnUpdateThroughAnotherWorkspaceAsNotFound() {
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        WorkspaceId otherWorkspaceId = new WorkspaceId(UUID.randomUUID());
        workspaceRepository.insertIfAbsent(workspaceId);
        workspaceRepository.insertIfAbsent(otherWorkspaceId);
        Assessment assessment = Assessment.createDraft(
                new AssessmentId(UUID.randomUUID()),
                workspaceId);
        assessmentRepository.insert(assessment);
        Assessment wrongWorkspaceAssessment = Assessment.rehydrate(
                assessment.id(),
                otherWorkspaceId,
                assessment.status(),
                assessment.profile());

        AssessmentNotFoundException exception = assertThrows(
                AssessmentNotFoundException.class,
                () -> assessmentRepository.update(wrongWorkspaceAssessment, 0));

        assertAll(
                () -> assertEquals(otherWorkspaceId, exception.workspaceId()),
                () -> assertEquals(assessment.id(), exception.assessmentId()));
    }
}
