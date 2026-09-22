package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.authweave.core.assessment.domain.profile.ComplianceScopeStatus;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;
import io.authweave.core.catalog.ProviderCatalog;

import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class HardConstraintPreflightTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");

    @Test
    void failedConstraintTakesPrecedenceWithoutHidingUnknownEvidenceOrSharedScope() {
        var failed = new CapabilityPreflight.Check(ProviderCatalog.Capability.SCIM, "provisioning.scim",
                RequirementCriticality.REQUIRED, FAIL, CapabilityPreflight.Reason.REQUIRED_CAPABILITY_UNAVAILABLE,
                "The plan does not offer SCIM.", null);
        var missing = new EligibilityPreflight.ContextCheck(EligibilityPreflight.Dimension.TENANCY,
                "audience.tenancy", "MULTI_TENANT_ORGANIZATIONS", UNKNOWN,
                EligibilityPreflight.Reason.EVIDENCE_MISSING, "Tenancy evidence is missing.", null);
        var candidate = candidate(DOES_NOT_MATCH, List.of(failed), List.of(missing));
        var result = HardConstraintPreflight.from(source(candidate, unknownCompliance())).candidates().getFirst();

        assertEquals(HardConstraintPreflight.Verdict.EXCLUDED, result.verdict());
        assertEquals(List.of("REQUIRED_CAPABILITY_UNAVAILABLE"),
                result.exclusionReasons().stream().map(HardConstraintPreflight.Finding::reasonCode).toList());
        assertEquals(List.of("EVIDENCE_MISSING", "COMPLIANCE_SCOPE_UNKNOWN"),
                result.informationGaps().stream().map(HardConstraintPreflight.Finding::reasonCode).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.informationGaps().clear());
    }

    @Test
    void noAffirmativeChecksRemainsUnresolvedAndCheckedPassIsNotARecommendation() {
        var empty = candidate(NEEDS_INFORMATION, List.of(), List.of());
        var passing = new CapabilityPreflight.Check(ProviderCatalog.Capability.SCIM, "provisioning.scim",
                RequirementCriticality.REQUIRED, PASS, CapabilityPreflight.Reason.REQUIRED_CAPABILITY_AVAILABLE,
                "The plan offers SCIM.", null);
        var passed = candidate(MATCHES_CHECKED_REQUIREMENTS, List.of(passing), List.of());
        var compliance = new ComplianceScopeCheck("security.complianceScopeStatus",
                ComplianceScopeStatus.NONE_IDENTIFIED, List.of(), NOT_APPLIED,
                ComplianceScopeCheck.Reason.NO_COMPLIANCE_TARGETS_IDENTIFIED, "No targets were identified.", false);
        var preflight = HardConstraintPreflight.from(source(List.of(empty, passed), compliance));

        assertFalse(preflight.recommendationReady());
        assertTrue(preflight.deferredPaths().contains("operations"));
        assertEquals(HardConstraintPreflight.Verdict.UNRESOLVED, preflight.candidates().getFirst().verdict());
        assertEquals("NO_AFFIRMATIVE_CHECKS", preflight.candidates().getFirst().informationGaps().getFirst().reasonCode());
        assertEquals(HardConstraintPreflight.Verdict.PASSES_CHECKED_REQUIREMENTS, preflight.candidates().get(1).verdict());
        assertTrue(preflight.candidates().get(1).exclusionReasons().isEmpty());
        assertTrue(preflight.candidates().get(1).informationGaps().isEmpty());
    }

    @Test
    void inconsistentPassingStatusFailsClosed() {
        var missing = new CapabilityPreflight.Check(ProviderCatalog.Capability.SCIM, "provisioning.scim",
                RequirementCriticality.REQUIRED, UNKNOWN, CapabilityPreflight.Reason.EVIDENCE_STALE,
                "The observation is stale.", null);
        assertThrows(IllegalStateException.class, () -> HardConstraintPreflight.from(source(
                candidate(MATCHES_CHECKED_REQUIREMENTS, List.of(missing), List.of()),
                new ComplianceScopeCheck("security.complianceScopeStatus", ComplianceScopeStatus.NONE_IDENTIFIED,
                        List.of(), NOT_APPLIED, ComplianceScopeCheck.Reason.NO_COMPLIANCE_TARGETS_IDENTIFIED,
                        "No targets were identified.", false))));
    }

    private static EligibilityPreflightV4 source(EligibilityPreflightV3.Candidate candidate, ComplianceScopeCheck compliance) {
        return source(List.of(candidate), compliance);
    }

    private static EligibilityPreflightV4 source(List<EligibilityPreflightV3.Candidate> candidates, ComplianceScopeCheck compliance) {
        return new EligibilityPreflightV4(UUID.randomUUID(), UUID.randomUUID(), 2,
                "synthetic-test", ProviderCatalog.Kind.SYNTHETIC, EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION,
                CapabilityEvaluator.POLICY_VERSION, EligibilityEvaluator.POLICY_VERSION,
                ResidencyEvaluator.POLICY_VERSION, AuthenticationControlEvaluator.POLICY_VERSION,
                ComplianceScopeEvaluator.POLICY_VERSION, AssuranceLevel.UNKNOWN, AT,
                "SYNTHETIC_ELIGIBILITY_PREFLIGHT", false, EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS,
                compliance, candidates);
    }

    private static EligibilityPreflightV3.Candidate candidate(CapabilityPreflight.Status status,
            List<CapabilityPreflight.Check> capabilities, List<EligibilityPreflight.ContextCheck> context) {
        return new EligibilityPreflightV3.Candidate("fictional-plan", "Fictional Plan", "Demo", "Synthetic region",
                status, capabilities, context, List.of(), List.of());
    }

    private static ComplianceScopeCheck unknownCompliance() {
        return new ComplianceScopeCheck("security.complianceScopeStatus", ComplianceScopeStatus.UNKNOWN,
                List.of(), UNKNOWN, ComplianceScopeCheck.Reason.COMPLIANCE_SCOPE_UNKNOWN,
                "Clarify the requirements scope.", false);
    }
}
