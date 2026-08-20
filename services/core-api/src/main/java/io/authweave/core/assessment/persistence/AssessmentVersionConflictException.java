package io.authweave.core.assessment.persistence;

import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

public final class AssessmentVersionConflictException extends RuntimeException {

    private final WorkspaceId workspaceId;
    private final AssessmentId assessmentId;
    private final long expectedVersion;
    private final long actualVersion;

    public AssessmentVersionConflictException(
            WorkspaceId workspaceId,
            AssessmentId assessmentId,
            long expectedVersion,
            long actualVersion) {
        super("Assessment %s in workspace %s has version %d; expected %d"
                .formatted(
                        assessmentId.value(),
                        workspaceId.value(),
                        actualVersion,
                        expectedVersion));
        this.workspaceId = workspaceId;
        this.assessmentId = assessmentId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public AssessmentId assessmentId() {
        return assessmentId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
