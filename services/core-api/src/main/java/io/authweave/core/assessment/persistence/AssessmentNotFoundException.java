package io.authweave.core.assessment.persistence;

import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

public final class AssessmentNotFoundException extends RuntimeException {

    private final WorkspaceId workspaceId;
    private final AssessmentId assessmentId;

    public AssessmentNotFoundException(WorkspaceId workspaceId, AssessmentId assessmentId) {
        super("Assessment %s was not found in workspace %s"
                .formatted(assessmentId.value(), workspaceId.value()));
        this.workspaceId = workspaceId;
        this.assessmentId = assessmentId;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public AssessmentId assessmentId() {
        return assessmentId;
    }
}
