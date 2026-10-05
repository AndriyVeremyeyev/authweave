package io.authweave.core.evaluation;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.persistence.AssessmentVersionConflictException;

@Service
public class ProvisioningLifecyclePreviewService {
    private final AssessmentApplicationService assessments;
    private final Clock clock;
    public ProvisioningLifecyclePreviewService(AssessmentApplicationService assessments, Clock clock) {
        this.assessments = assessments; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public ProvisioningLifecyclePreview preview(WorkspaceId workspaceId, AssessmentId assessmentId, ProvisioningLifecycleRequest request) {
        var saved = assessments.getAssessment(workspaceId, assessmentId);
        if (saved.version() != request.expectedVersion()) throw new AssessmentVersionConflictException(workspaceId, assessmentId, request.expectedVersion(), saved.version());
        var analysis = ProvisioningLifecycleEvaluator.evaluate(saved.assessment().profile().provisioning(), request.patternId(), request.declarations());
        return new ProvisioningLifecyclePreview(workspaceId.value(), assessmentId.value(), saved.version(), clock.instant(), analysis);
    }
}
