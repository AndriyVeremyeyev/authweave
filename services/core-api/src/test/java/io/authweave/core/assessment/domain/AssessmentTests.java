package io.authweave.core.assessment.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssessmentTests {

    private static final WorkspaceId WORKSPACE_ID =
            new WorkspaceId(UUID.fromString("ce9616c3-2b51-4897-a520-0df3c4d321ce"));

    @Test
    void createsDraftInsideItsWorkspace() {
        AssessmentId assessmentId =
                new AssessmentId(UUID.fromString("2d4ef269-c82c-457e-a6c8-d13f5204a04b"));

        Assessment assessment = Assessment.createDraft(assessmentId, WORKSPACE_ID);

        assertEquals(assessmentId, assessment.id());
        assertEquals(WORKSPACE_ID, assessment.workspaceId());
        assertEquals(AssessmentStatus.DRAFT, assessment.status());
    }

    @Test
    void followsTheDecisionLifecycle() {
        Assessment assessment = newDraft();

        assessment.markReadyForEvaluation();
        assessment.recordEvaluation();
        assessment.recordDecision();

        assertEquals(AssessmentStatus.DECIDED, assessment.status());
    }

    @Test
    void revisionInvalidatesAnExistingEvaluation() {
        Assessment assessment = newDraft();
        assessment.markReadyForEvaluation();
        assessment.recordEvaluation();

        assessment.revise();

        assertEquals(AssessmentStatus.DRAFT, assessment.status());
    }

    @Test
    void cannotSkipRequiredLifecycleSteps() {
        Assessment assessment = newDraft();

        InvalidAssessmentTransitionException exception = assertThrows(
                InvalidAssessmentTransitionException.class,
                assessment::recordDecision);

        assertEquals(AssessmentStatus.DRAFT, exception.currentStatus());
        assertEquals(AssessmentStatus.DECIDED, exception.targetStatus());
        assertEquals(AssessmentStatus.DRAFT, assessment.status());
    }

    @Test
    void archivedAssessmentIsTerminal() {
        Assessment assessment = newDraft();
        assessment.archive();

        InvalidAssessmentTransitionException exception = assertThrows(
                InvalidAssessmentTransitionException.class,
                assessment::revise);

        assertEquals(AssessmentStatus.ARCHIVED, exception.currentStatus());
        assertEquals(AssessmentStatus.DRAFT, exception.targetStatus());
    }

    private static Assessment newDraft() {
        return Assessment.createDraft(AssessmentId.generate(), WORKSPACE_ID);
    }
}
