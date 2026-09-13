package io.authweave.core.evaluation;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@Service
public class UsagePlanningPreflightService {
    private final AssessmentApplicationService assessments;
    private final Clock clock;
    public UsagePlanningPreflightService(AssessmentApplicationService assessments, Clock clock) {
        this.assessments = assessments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UsagePlanningPreflight preview(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        return UsagePlanningEvaluator.evaluate(workspaceId.value(), assessmentId.value(), assessment.version(),
                assessment.assessment().profile().operations().usagePlanning(), clock.instant());
    }
}
