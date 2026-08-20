package io.authweave.core.assessment.persistence;

import java.util.UUID;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class JooqAssessmentRepositoryIntegrationTests extends PostgresIntegrationTest {

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

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
