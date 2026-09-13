package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;

/** Adds a shared scope-uncertainty gate; no provider compliance verification is performed. */
public record EligibilityPreflightV4(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, String capabilityPolicyVersion,
        String contextPolicyVersion, String residencyPolicyVersion, String authenticationControlPolicyVersion,
        String complianceScopePolicyVersion, AssuranceLevel assuranceExpectation, Instant evaluatedAt, String scope,
        boolean recommendationReady, List<String> deferredPaths, ComplianceScopeCheck complianceScopeCheck,
        List<EligibilityPreflightV3.Candidate> candidates) {
    public EligibilityPreflightV4 {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }
}
