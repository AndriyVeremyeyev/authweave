package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ClaimRules;
import io.authweave.core.evaluation.EligibilityEvaluator;

/** Candidate-only conditional checks. No fictitious baseline, proposal revision or before/after diff. */
public record CatalogBootstrapImpact(Status status, Instant evaluatedAt, UUID reviewId, String reviewSha256,
        String candidateSha256, String catalogVersion, CatalogDraftValidation validation, List<Blocker> blockers,
        List<CatalogImpactCases.Probe> caseDefinitions, List<FactCase> cases,
        String scenarioSetSha256, List<CatalogScenarioCases.Definition> scenarioDefinitions,
        List<ScenarioCase> scenarios, List<UncoveredFact> uncoveredFacts, List<UncoveredFact> scenarioUncoveredFacts) {
    public CatalogBootstrapImpact {
        blockers = List.copyOf(blockers); caseDefinitions = List.copyOf(caseDefinitions); cases = List.copyOf(cases);
        scenarioDefinitions = List.copyOf(scenarioDefinitions); scenarios = List.copyOf(scenarios);
        uncoveredFacts = List.copyOf(uncoveredFacts); scenarioUncoveredFacts = List.copyOf(scenarioUncoveredFacts);
    }
    public enum Status { BLOCKED, ANALYZED }
    public enum Blocker { CANDIDATE_DIGEST_MISMATCH, INVALID_CANDIDATE }
    public record FactCase(String caseId, String optionId, String factPath, CatalogImpactPreview.Side result) { }
    public record ScenarioCase(String scenarioId, String optionId, CatalogScenarioImpact.Side result) { }
    public record UncoveredFact(String optionId, String factPath, String reason) { }
    @JsonProperty public String scope() { return "CATALOG_BOOTSTRAP_IMPACT"; }
    @JsonProperty public int reportSchemaVersion() { return 1; }
    @JsonProperty public String canonicalizationVersion() { return CatalogDraftCanonicalizer.VERSION; }
    @JsonProperty public String policyVersion() { return CatalogBootstrapImpactService.POLICY_VERSION; }
    @JsonProperty public String ruleVersion() { return ClaimRules.VERSION; }
    @JsonProperty public String profilePolicyVersion() { return EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION; }
    @JsonProperty public String caseSetVersion() { return CatalogFactPathRegressionCases.VERSION; }
    @JsonProperty public String caseSetSha256() { return CatalogFactPathRegressionCases.SHA256; }
    @JsonProperty public String scenarioSetVersion() { return CatalogScenarioCases.VERSION; }
    @JsonProperty public String analysisBasis() { return "ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS"; }
    @JsonProperty public boolean impactAnalysisPerformed() { return status == Status.ANALYZED; }
    @JsonProperty public boolean hypotheticalEvaluationPerformed() { return impactAnalysisPerformed(); }
    @JsonProperty public List<String> deferredPaths() { return CatalogScenarioImpactService.DEFERRED_PATHS; }
    @JsonProperty public boolean storedReportVerified() { return false; }
    @JsonProperty public boolean coverageComplete() { return false; }
    @JsonProperty public boolean baselineVerified() { return false; }
    @JsonProperty public boolean sourceVerificationPerformed() { return false; }
    @JsonProperty public boolean approvalGranted() { return false; }
    @JsonProperty public boolean writesPerformed() { return false; }
    @JsonProperty public boolean evaluationReady() { return false; }
    @JsonProperty public boolean recommendationReady() { return false; }
}
