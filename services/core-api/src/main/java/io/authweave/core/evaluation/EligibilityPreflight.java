package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.authweave.core.catalog.ProviderCatalog;

/** Capability and context checks, still not a full architecture recommendation. */
public record EligibilityPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, String capabilityPolicyVersion,
        Instant evaluatedAt, String scope, boolean recommendationReady,
        List<String> deferredPaths, List<Candidate> candidates) {
    public EligibilityPreflight {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }

    public enum Dimension { APPLICATION_TYPE, CLIENT_TYPE, USER_POPULATION, TENANCY, MEMBERSHIP }
    public enum Reason {
        CONTEXT_SUPPORTED, CONTEXT_UNSUPPORTED, PROFILE_CONTEXT_UNKNOWN, CONTEXT_SUPPORT_UNKNOWN,
        HUMAN_POPULATION_NOT_APPLICABLE,
        EVIDENCE_MISSING, EVIDENCE_UNREVIEWED, EVIDENCE_FROM_FUTURE, EVIDENCE_STALE
    }
    public record ContextCheck(Dimension dimension, String profilePath, String requestedValue,
            CapabilityPreflight.Outcome outcome, Reason reasonCode, String explanation,
            ProviderCatalog.CompatibilityFact evidence) { }

    public record Candidate(String optionId, String displayName, String plan, String region,
            CapabilityPreflight.Status status, List<CapabilityPreflight.Check> capabilityChecks,
            List<ContextCheck> contextChecks) {
        public Candidate {
            capabilityChecks = List.copyOf(capabilityChecks);
            contextChecks = List.copyOf(contextChecks);
        }
    }
}
