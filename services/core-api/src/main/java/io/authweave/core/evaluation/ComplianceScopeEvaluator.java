package io.authweave.core.evaluation;

import java.util.Comparator;
import io.authweave.core.assessment.domain.profile.SecurityRequirements;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.ComplianceScopeCheck.Reason.*;

/** This slice records uncertainty; no framework label is treated as provider evidence. */
public final class ComplianceScopeEvaluator {
    public static final String POLICY_VERSION = "compliance-scope-preflight-1";
    private ComplianceScopeEvaluator() { }

    public static ComplianceScopeCheck evaluate(SecurityRequirements security) {
        var status = security.complianceScopeStatus();
        var targets = security.complianceTargets().stream().sorted(Comparator.comparing(Enum::name)).toList();
        if ((status == io.authweave.core.assessment.domain.profile.ComplianceScopeStatus.NONE_IDENTIFIED && !targets.isEmpty())
                || (status == io.authweave.core.assessment.domain.profile.ComplianceScopeStatus.TARGETS_IDENTIFIED && targets.isEmpty())) {
            return new ComplianceScopeCheck("security.complianceScopeStatus", status, targets, UNKNOWN,
                    COMPLIANCE_SCOPE_INCONSISTENT, "Resolve the inconsistency between scope status and recorded targets before evaluating requirements.", false);
        }
        var reason = switch (status) {
            case UNKNOWN -> COMPLIANCE_SCOPE_UNKNOWN;
            case NONE_IDENTIFIED -> NO_COMPLIANCE_TARGETS_IDENTIFIED;
            case TARGETS_IDENTIFIED -> COMPLIANCE_TARGETS_NOT_EVALUATED;
        };
        var explanation = switch (status) {
            case UNKNOWN -> "The requirements scope has not been recorded. Existing target labels are preserved but do not establish scope or compliance. Clarify the requirements with the assessment owner.";
            case NONE_IDENTIFIED -> "The owner recorded no identified compliance requirements for this assessment. No target check is applied; this is not a finding of legal exemption or compliance.";
            case TARGETS_IDENTIFIED -> "Target labels are recorded, but applicable obligations, product/service scope and supporting evidence have not been evaluated. OTHER needs a concrete definition. No candidate is verified or rejected from labels alone.";
        };
        return new ComplianceScopeCheck("security.complianceScopeStatus", status, targets,
                status == io.authweave.core.assessment.domain.profile.ComplianceScopeStatus.NONE_IDENTIFIED ? NOT_APPLIED : UNKNOWN,
                reason, explanation, false);
    }
}
