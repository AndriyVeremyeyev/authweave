package io.authweave.core.assessment.persistence;

import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

public interface AssessmentHistoryRepository {

    HistoryPage<AssessmentRevision> findRevisions(
            WorkspaceId workspaceId, AssessmentId assessmentId, Long afterVersion, int limit);

    HistoryPage<AssessmentEvent> findEvents(
            WorkspaceId workspaceId, AssessmentId assessmentId, Long afterVersion, int limit);
}
