package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.authweave.core.catalog.ProviderCatalog;

/** Extends eligibility with scoped residency, without claiming a complete recommendation. */
public record EligibilityPreflightV2(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, String capabilityPolicyVersion,
        String contextPolicyVersion, String residencyPolicyVersion, Instant evaluatedAt, String scope,
        boolean recommendationReady, List<String> deferredPaths, List<Candidate> candidates) {
    public EligibilityPreflightV2 {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }

    public record Candidate(String optionId, String displayName, String plan, String region,
            CapabilityPreflight.Status status, List<CapabilityPreflight.Check> capabilityChecks,
            List<EligibilityPreflight.ContextCheck> contextChecks, List<ResidencyCheck> residencyChecks) {
        public Candidate {
            capabilityChecks = List.copyOf(capabilityChecks);
            contextChecks = List.copyOf(contextChecks);
            residencyChecks = List.copyOf(residencyChecks);
        }
    }
}
