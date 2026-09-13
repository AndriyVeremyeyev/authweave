package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.draft.CatalogChangePreview;
import io.authweave.core.catalog.draft.CatalogDraftValidation.Freshness;

/** Conditional rule-level impact only. No real eligibility decision or trusted evidence is produced. */
public record CatalogImpactPreview(String scope, String policyVersion, String ruleVersion,
        String caseSetVersion, String caseSetSha256, String analysisBasis, Instant evaluatedAt,
        UUID proposalId, String proposalSha256, Long storedProposalVersion, boolean storedRequestDigestVerified,
        Status status, boolean impactAnalysisPerformed, boolean hypotheticalEvaluationPerformed,
        boolean coverageComplete, boolean baselineVerified, boolean sourceVerificationPerformed,
        boolean approvalGranted, boolean writesPerformed, boolean evaluationReady, boolean recommendationReady,
        CatalogChangePreview changePreview, List<CatalogImpactCases.Probe> caseDefinitions,
        List<CaseImpact> cases, List<UncoveredChange> uncoveredChanges) {
    public CatalogImpactPreview { caseDefinitions = List.copyOf(caseDefinitions); cases = List.copyOf(cases); uncoveredChanges = List.copyOf(uncoveredChanges); }
    public enum Status { BLOCKED, ANALYZED }
    public enum Outcome { WOULD_SATISFY, WOULD_VIOLATE, INDETERMINATE, NOT_APPLIED }
    public enum Reason { REQUIRED_CLAIM_AVAILABLE, REQUIRED_CLAIM_UNAVAILABLE, FORBIDDEN_CLAIM_AVOIDABLE, FORBIDDEN_CLAIM_UNAVOIDABLE,
        CLAIM_UNKNOWN, FACT_MISSING, OPTION_ABSENT, NO_REQUIREMENT, PREFERENCE_NOT_SCORED, REQUIREMENT_UNKNOWN,
        CONTEXT_SUPPORTED, CONTEXT_UNSUPPORTED, STORAGE_OUTSIDE_ALLOWLIST, STORAGE_COMPLETE_WITHIN_ALLOWLIST,
        STORAGE_INCOMPLETE, CONTROL_ENFORCEABLE, CONTROL_UNAVAILABLE, ENFORCEMENT_UNSUPPORTED }
    public record Side(boolean optionPresent, boolean factPresent, Outcome conditionalOutcome, Reason reason,
            Freshness freshness, boolean conditionsRecorded) { }
    public record CaseImpact(String caseId, String optionId, String factPath, boolean scopeChanged,
            List<CatalogChangePreview.Aspect> changedAspects, boolean conditionalResultChanged, Side before, Side after) {
        public CaseImpact { changedAspects = List.copyOf(changedAspects); }
    }
    public record UncoveredChange(String optionId, String factPath, String reason) { }
}
