package io.authweave.core.evaluation;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.catalog.ProviderCatalog;

@Service
public class CapabilityPreflightService {
    private final AssessmentApplicationService assessments;
    private final ProviderCatalog catalog;
    private final Clock clock;

    public CapabilityPreflightService(AssessmentApplicationService assessments, ProviderCatalog catalog, Clock clock) {
        this.assessments = assessments;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CapabilityPreflight preview(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        var at = clock.instant();
        return new CapabilityPreflight(workspaceId.value(), assessmentId.value(), assessment.version(),
                catalog.catalogVersion(), catalog.kind(), CapabilityEvaluator.POLICY_VERSION, at,
                "CAPABILITY_PREFLIGHT", false, CapabilityEvaluator.DEFERRED_PATHS,
                CapabilityEvaluator.evaluate(assessment.assessment().profile(), catalog, at));
    }
}
