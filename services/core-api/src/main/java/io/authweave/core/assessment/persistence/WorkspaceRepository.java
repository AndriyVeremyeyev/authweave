package io.authweave.core.assessment.persistence;

import io.authweave.core.assessment.domain.WorkspaceId;

public interface WorkspaceRepository {

    boolean insertIfAbsent(WorkspaceId workspaceId);

    boolean exists(WorkspaceId workspaceId);
}
