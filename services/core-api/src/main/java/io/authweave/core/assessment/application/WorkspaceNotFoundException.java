package io.authweave.core.assessment.application;

import io.authweave.core.assessment.domain.WorkspaceId;

public final class WorkspaceNotFoundException extends RuntimeException {

    private final WorkspaceId workspaceId;

    public WorkspaceNotFoundException(WorkspaceId workspaceId) {
        super("Workspace %s was not found".formatted(workspaceId.value()));
        this.workspaceId = workspaceId;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }
}
