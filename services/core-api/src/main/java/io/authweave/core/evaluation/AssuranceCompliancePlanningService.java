package io.authweave.core.evaluation;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@Service
public class AssuranceCompliancePlanningService {
    private final AssessmentApplicationService assessments;
    private final Clock clock;
    public AssuranceCompliancePlanningService(AssessmentApplicationService assessments, Clock clock) { this.assessments = assessments; this.clock = clock; }
    @Transactional(readOnly = true)
    public AssuranceCompliancePlanningPreflight preview(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var saved = assessments.getAssessment(workspaceId, assessmentId);
        return AssuranceCompliancePlanningEvaluator.evaluate(workspaceId.value(), assessmentId.value(), saved.version(), saved.assessment().profile(), clock.instant());
    }
}
