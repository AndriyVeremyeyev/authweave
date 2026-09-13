package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;

/** Scoped capability evidence, not configured authentication or an assurance certification. */
public record EligibilityPreflightV3(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, String capabilityPolicyVersion,
        String contextPolicyVersion, String residencyPolicyVersion, String authenticationControlPolicyVersion,
        AssuranceLevel assuranceExpectation, Instant evaluatedAt, String scope,
        boolean recommendationReady, List<String> deferredPaths, List<Candidate> candidates) {
    public EligibilityPreflightV3 {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }

    public record Candidate(String optionId, String displayName, String plan, String region,
            CapabilityPreflight.Status status, List<CapabilityPreflight.Check> capabilityChecks,
            List<EligibilityPreflight.ContextCheck> contextChecks, List<ResidencyCheck> residencyChecks,
            List<AuthenticationControlCheck> authenticationControlChecks) {
        public Candidate {
            capabilityChecks = List.copyOf(capabilityChecks);
            contextChecks = List.copyOf(contextChecks);
            residencyChecks = List.copyOf(residencyChecks);
            authenticationControlChecks = List.copyOf(authenticationControlChecks);
        }
    }
}
