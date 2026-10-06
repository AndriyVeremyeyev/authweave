package io.authweave.core.evaluation;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@Service
public class OperationsPlanningPreflightService {
    private final AssessmentApplicationService assessments;
    private final Clock clock;
    public OperationsPlanningPreflightService(AssessmentApplicationService assessments, Clock clock) { this.assessments = assessments; this.clock = clock; }
    @Transactional(readOnly = true)
    public OperationsPlanningPreflight preview(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var saved = assessments.getAssessment(workspaceId, assessmentId);
        return OperationsPlanningEvaluator.evaluate(workspaceId.value(), assessmentId.value(), saved.version(), saved.assessment().profile().operations(), clock.instant());
    }
}
