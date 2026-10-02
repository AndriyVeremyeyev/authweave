package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.AuditabilityFacts.Fact;
import io.authweave.core.catalog.ProviderCatalog;

/** Separate partial capability preview; never merged eligibility, logging verification or publication evidence. */
public record AuditabilityCapabilityPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String baseCatalogVersion, String evidenceVersion, ProviderCatalog.Kind catalogKind, Instant evaluatedAt,
        RequirementCriticality criticality, AuditabilityRequirements requirements, List<Candidate> candidates) {
    public AuditabilityCapabilityPreflight {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(assessmentId); Objects.requireNonNull(evaluatedAt);
        Objects.requireNonNull(catalogKind); Objects.requireNonNull(criticality); Objects.requireNonNull(requirements);
        if (assessmentVersion < 0 || assessmentVersion > 9_007_199_254_740_991L)
            throw new IllegalArgumentException("Invalid auditability assessment version");
        for (var version : new String[] { baseCatalogVersion, evidenceVersion })
            if (version == null || !version.matches("[a-z0-9][a-z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid auditability preview version");
        candidates = List.copyOf(candidates);
        if (candidates.isEmpty() || candidates.size() > 100) throw new IllegalArgumentException("Invalid auditability candidate count");
        var scopes = new HashSet<io.authweave.core.catalog.AuditabilityFacts.Scope>();
        for (var candidate : candidates) {
            var analysis = candidate.analysis();
            if (!scopes.add(analysis.optionScope()) || analysis.criticality() != criticality || !analysis.requirements().equals(requirements)
                    || !analysis.evaluatedAt().equals(evaluatedAt)) throw new IllegalArgumentException("Unbound auditability candidate analysis");
        }
    }
    public record Candidate(String displayName, AuditabilityEvaluator.Analysis analysis, List<Fact> evidence) {
        public Candidate {
            Objects.requireNonNull(displayName); Objects.requireNonNull(analysis); evidence = List.copyOf(evidence);
            // Recompute to bind result to the exact returned evidence, including its freshness and source scope.
            if (!analysis.equals(AuditabilityEvaluator.evaluate(analysis.criticality(), analysis.requirements(),
                    analysis.optionScope(), evidence, analysis.evaluatedAt())))
                throw new IllegalArgumentException("Auditability result does not match its evidence");
        }
    }
    @JsonProperty public String policyVersion() { return AuditabilityEvaluator.POLICY_VERSION; }
    @JsonProperty public String scope() { return "SYNTHETIC_AUDITABILITY_CAPABILITY_PREFLIGHT"; }
    @JsonProperty public List<String> checkedPaths() { return List.of("security.auditability", "security.auditabilityRequirements"); }
    @JsonProperty public boolean sourceVerificationPerformed() { return false; }
    @JsonProperty public boolean recommendationReady() { return false; }
}
