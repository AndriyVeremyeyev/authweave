package io.authweave.core.assessment.persistence;

import io.authweave.core.assessment.domain.WorkspaceId;

public interface WorkspaceRepository {

    void insert(WorkspaceId workspaceId);

    boolean exists(WorkspaceId workspaceId);
}
