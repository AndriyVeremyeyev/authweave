package io.authweave.core.assessment.persistence;

import java.util.Optional;

import io.authweave.core.assessment.domain.Assessment;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

public interface AssessmentRepository {

    PersistedAssessment insert(Assessment assessment);

    Optional<PersistedAssessment> findById(WorkspaceId workspaceId, AssessmentId assessmentId);

    PersistedAssessment update(Assessment assessment, long expectedVersion);
}
