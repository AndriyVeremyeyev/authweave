package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.draft.CatalogChangePreview;
import io.authweave.core.catalog.draft.CatalogDraftValidation.Freshness;

/** Full profile inputs, conditional checked dimensions only; not a full product recommendation. */
public record CatalogScenarioImpact(String scope, String policyVersion, String ruleVersion, String profilePolicyVersion,
        String caseSetVersion, String caseSetSha256, String analysisBasis, Instant evaluatedAt,
        UUID proposalId, String proposalSha256, Long storedProposalVersion, boolean storedRequestDigestVerified,
        CatalogImpactPreview.Status status, boolean impactAnalysisPerformed, boolean hypotheticalEvaluationPerformed,
        boolean coverageComplete, boolean baselineVerified, boolean sourceVerificationPerformed, boolean approvalGranted,
        boolean writesPerformed, boolean evaluationReady, boolean recommendationReady, List<String> deferredPaths,
        CatalogChangePreview changePreview, List<CatalogScenarioCases.Definition> scenarioDefinitions,
        List<ScenarioImpact> scenarios, List<CatalogImpactPreview.UncoveredChange> uncoveredChanges) {
    public CatalogScenarioImpact {
        deferredPaths = List.copyOf(deferredPaths); scenarioDefinitions = List.copyOf(scenarioDefinitions);
        scenarios = List.copyOf(scenarios); uncoveredChanges = List.copyOf(uncoveredChanges);
    }
    public enum ConditionalStatus { WOULD_SATISFY_CHECKED_REQUIREMENTS, WOULD_VIOLATE_CHECKED_REQUIREMENTS, INDETERMINATE, OPTION_ABSENT }
    public record Check(String checkId, String profilePath, String factPath, boolean usesFact, boolean factPresent,
            CatalogImpactPreview.Outcome conditionalOutcome, String reason, Freshness freshness, boolean conditionsRecorded) { }
    public record Side(boolean optionPresent, ConditionalStatus conditionalStatus, List<Check> checks) {
        public Side { checks = List.copyOf(checks); }
    }
    public record ScenarioImpact(String scenarioId, String optionId, boolean scopeChanged, List<String> affectedFactPaths,
            boolean conditionalStatusChanged, List<String> changedCheckIds, Side before, Side after) {
        public ScenarioImpact { affectedFactPaths = List.copyOf(affectedFactPaths); changedCheckIds = List.copyOf(changedCheckIds); }
    }
}
