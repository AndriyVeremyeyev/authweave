package io.authweave.core.evaluation;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.catalog.AuditabilityCatalog;
import io.authweave.core.catalog.ProviderCatalog;

@Service
public class AuditabilityCapabilityPreflightService {
    private final AssessmentApplicationService assessments;
    private final ProviderCatalog base;
    private final AuditabilityCatalog evidence;
    private final Clock clock;
    public AuditabilityCapabilityPreflightService(AssessmentApplicationService assessments, ProviderCatalog base,
            AuditabilityCatalog evidence, Clock clock) {
        evidence.validateBase(base);
        this.assessments = assessments; this.base = base; this.evidence = evidence; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public AuditabilityCapabilityPreflight preview(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        var security = assessment.assessment().profile().security(); var at = clock.instant();
        var candidates = evidence.options().stream().map(option -> {
            var displayName = base.options().stream().filter(candidate -> candidate.id().equals(option.scope().optionId())).findFirst().orElseThrow().displayName();
            var analysis = AuditabilityEvaluator.evaluate(security.auditability(), security.auditabilityRequirements(), option.scope(), option.facts(), at);
            return new AuditabilityCapabilityPreflight.Candidate(displayName, analysis, option.facts());
        }).toList();
        return new AuditabilityCapabilityPreflight(workspaceId.value(), assessmentId.value(), assessment.version(),
                base.catalogVersion(), evidence.evidenceVersion(), base.kind(), at, security.auditability(),
                security.auditabilityRequirements(), candidates);
    }
}
