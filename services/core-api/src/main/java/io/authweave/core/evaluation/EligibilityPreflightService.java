package io.authweave.core.evaluation;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.catalog.ProviderCatalog;

@Service
public class EligibilityPreflightService {
    private final AssessmentApplicationService assessments;
    private final ProviderCatalog catalog;
    private final Clock clock;

    public EligibilityPreflightService(AssessmentApplicationService assessments, ProviderCatalog catalog, Clock clock) {
        this.assessments = assessments;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EligibilityPreflight preview(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        var at = clock.instant();
        return new EligibilityPreflight(workspaceId.value(), assessmentId.value(), assessment.version(),
                catalog.catalogVersion(), catalog.kind(), EligibilityEvaluator.POLICY_VERSION,
                CapabilityEvaluator.POLICY_VERSION, at, "SYNTHETIC_ELIGIBILITY_PREFLIGHT", false,
                EligibilityEvaluator.DEFERRED_PATHS,
                EligibilityEvaluator.evaluate(assessment.assessment().profile(), catalog, at));
    }

    @Transactional(readOnly = true)
    public EligibilityPreflightV4 previewWithComplianceScope(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        var at = clock.instant();
        var profile = assessment.assessment().profile();
        return new EligibilityPreflightV4(workspaceId.value(), assessmentId.value(), assessment.version(),
                catalog.catalogVersion(), catalog.kind(), EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION,
                CapabilityEvaluator.POLICY_VERSION, EligibilityEvaluator.POLICY_VERSION, ResidencyEvaluator.POLICY_VERSION,
                AuthenticationControlEvaluator.POLICY_VERSION, ComplianceScopeEvaluator.POLICY_VERSION, profile.security().assurance(),
                at, "SYNTHETIC_ELIGIBILITY_PREFLIGHT", false, EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS,
                ComplianceScopeEvaluator.evaluate(profile.security()), EligibilityEvaluator.evaluateWithComplianceScope(profile, catalog, at));
    }

    @Transactional(readOnly = true)
    public EligibilityPreflightV3 previewWithAuthenticationControls(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        var at = clock.instant();
        var profile = assessment.assessment().profile();
        return new EligibilityPreflightV3(workspaceId.value(), assessmentId.value(), assessment.version(),
                catalog.catalogVersion(), catalog.kind(), EligibilityEvaluator.AUTHENTICATION_POLICY_VERSION,
                CapabilityEvaluator.POLICY_VERSION, EligibilityEvaluator.POLICY_VERSION, ResidencyEvaluator.POLICY_VERSION,
                AuthenticationControlEvaluator.POLICY_VERSION, profile.security().assurance(),
                at, "SYNTHETIC_ELIGIBILITY_PREFLIGHT", false, EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS,
                EligibilityEvaluator.evaluateWithAuthenticationControls(profile, catalog, at));
    }

    @Transactional(readOnly = true)
    public EligibilityPreflightV2 previewWithResidency(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        var at = clock.instant();
        return new EligibilityPreflightV2(workspaceId.value(), assessmentId.value(), assessment.version(),
                catalog.catalogVersion(), catalog.kind(), EligibilityEvaluator.RESIDENCY_POLICY_VERSION,
                CapabilityEvaluator.POLICY_VERSION, EligibilityEvaluator.POLICY_VERSION, ResidencyEvaluator.POLICY_VERSION,
                at, "SYNTHETIC_ELIGIBILITY_PREFLIGHT", false, EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS,
                EligibilityEvaluator.evaluateWithResidency(assessment.assessment().profile(), catalog, at));
    }
}
