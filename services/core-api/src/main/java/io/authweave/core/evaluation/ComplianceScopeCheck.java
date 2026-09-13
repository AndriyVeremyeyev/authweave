package io.authweave.core.evaluation;

import java.util.List;
import io.authweave.core.assessment.domain.profile.ComplianceScopeStatus;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;

/** A shared input-scope check, not evidence about any candidate or legal applicability. */
public record ComplianceScopeCheck(String profilePath, ComplianceScopeStatus scopeStatus,
        List<ComplianceTarget> recordedTargets, CapabilityPreflight.Outcome outcome, Reason reasonCode,
        String explanation, boolean verificationPerformed) {
    public ComplianceScopeCheck {
        recordedTargets = List.copyOf(recordedTargets);
    }
    public enum Reason { COMPLIANCE_SCOPE_UNKNOWN, NO_COMPLIANCE_TARGETS_IDENTIFIED,
        COMPLIANCE_TARGETS_NOT_EVALUATED, COMPLIANCE_SCOPE_INCONSISTENT }
}
