package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog;

/** A read-only, partial check, not a persisted evaluation or architecture recommendation. */
public record CapabilityPreflight(
        UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, Instant evaluatedAt,
        String scope, boolean recommendationReady, List<String> deferredPaths, List<Candidate> candidates) {

    public CapabilityPreflight {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }

    public enum Status { MATCHES_CHECKED_REQUIREMENTS, DOES_NOT_MATCH, NEEDS_INFORMATION }
    public enum Outcome { PASS, FAIL, UNKNOWN, NOT_APPLIED }
    public enum Reason {
        REQUIRED_CAPABILITY_AVAILABLE, REQUIRED_CAPABILITY_UNAVAILABLE,
        FORBIDDEN_CAPABILITY_UNAVOIDABLE, FORBIDDEN_CAPABILITY_AVOIDABLE,
        REQUIREMENT_UNKNOWN, CAPABILITY_UNKNOWN, EVIDENCE_MISSING, EVIDENCE_UNREVIEWED,
        EVIDENCE_STALE, EVIDENCE_FROM_FUTURE, PREFERENCE_NOT_SCORED, NO_REQUIREMENT
    }

    public record Candidate(String optionId, String displayName, String plan, String region,
            Status status, List<Check> checks) {
        public Candidate { checks = List.copyOf(checks); }
    }

    public record Check(ProviderCatalog.Capability capability, String profilePath,
            RequirementCriticality criticality, Outcome outcome, Reason reasonCode,
            String explanation, ProviderCatalog.Fact evidence) { }
}
