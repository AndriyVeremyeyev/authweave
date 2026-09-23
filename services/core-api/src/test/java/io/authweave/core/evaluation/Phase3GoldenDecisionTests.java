package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.impact.CatalogScenarioCases;

import static io.authweave.core.evaluation.HardConstraintPreflight.Verdict.*;
import static io.authweave.core.evaluation.WeightedComparisonPreview.ScoreStatus.SCORED;
import static io.authweave.core.evaluation.WeightedComparisonPreview.ScoreStatus.UNRESOLVED_HARD_CONSTRAINTS;
import static org.junit.jupiter.api.Assertions.*;

/** Exact regression expectations for the current synthetic decision slice, not the full 18-case evaluation dataset. */
class Phase3GoldenDecisionTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private static final UUID WORKSPACE = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ASSESSMENT = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final Map<ProviderCatalog.Capability, Integer> B2B_WEIGHTS = Map.of(
            ProviderCatalog.Capability.SOCIAL_LOGIN, 60, ProviderCatalog.Capability.JIT, 40);
    private static final Map<ProviderCatalog.Capability, Integer> WORKFORCE_WEIGHTS = Map.of(
            ProviderCatalog.Capability.SAML, 100);
    private static final Map<String, String> B2B_AVAILABLE = Map.of("SOCIAL_LOGIN", "AVAILABLE", "JIT", "AVAILABLE");
    private static final Map<String, String> B2B_UNKNOWN = Map.of("SOCIAL_LOGIN", "UNKNOWN", "JIT", "UNKNOWN");
    private static final Map<String, String> WORKFORCE_AVAILABLE = Map.of("SAML", "AVAILABLE");
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void frozenProfilesPinHardVerdictsUncertaintyAndPreferenceEvidence() throws Exception {
        var cases = new CatalogScenarioCases(mapper);
        var catalog = catalog();
        assertEquals("catalog-profile-scenarios-1", CatalogScenarioCases.VERSION);
        assertEquals("ad34a1fba95468535dc7919cab839a7828497847198112a1f86157723c5e5eac", cases.sha256());
        assertEquals(List.of("b2b-saas", "public-sector-portal", "internal-workforce"),
                cases.definitions().stream().map(CatalogScenarioCases.Definition::id).toList());
        assertEquals("synthetic-2026-09-12.4", catalog.catalogVersion());
        assertEquals(ProviderCatalog.Kind.SYNTHETIC, catalog.kind());

        assertCase(cases, catalog, "b2b-saas", false, AT, B2B_WEIGHTS, List.of(
                expected("fictional-complete", UNRESOLVED, Map.of(),
                        Map.of("REQUIREMENT_UNKNOWN", 7L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), B2B_AVAILABLE,
                        UNRESOLVED_HARD_CONSTRAINTS, null),
                expected("fictional-no-scim", EXCLUDED, Map.of("REQUIRED_CAPABILITY_UNAVAILABLE", 1L),
                        Map.of("REQUIREMENT_UNKNOWN", 7L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), B2B_AVAILABLE,
                        WeightedComparisonPreview.ScoreStatus.EXCLUDED, null),
                expected("fictional-unreviewed", UNRESOLVED, Map.of(),
                        Map.of("EVIDENCE_UNREVIEWED", 1L, "REQUIREMENT_UNKNOWN", 7L,
                                "COMPLIANCE_SCOPE_UNKNOWN", 1L), B2B_AVAILABLE, UNRESOLVED_HARD_CONSTRAINTS, null)));
        assertCase(cases, catalog, "public-sector-portal", false, AT, null, List.of(
                expected("fictional-complete", EXCLUDED, Map.of("CONTEXT_UNSUPPORTED", 4L),
                        Map.of("REQUIREMENT_UNKNOWN", 6L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), Map.of(), null, null),
                expected("fictional-no-scim", UNRESOLVED, Map.of(),
                        Map.of("REQUIREMENT_UNKNOWN", 6L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), Map.of(), null, null),
                expected("fictional-unreviewed", UNRESOLVED, Map.of(),
                        Map.of("REQUIREMENT_UNKNOWN", 6L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), Map.of(), null, null)));
        assertCase(cases, catalog, "internal-workforce", false, AT, WORKFORCE_WEIGHTS, List.of(
                expected("fictional-complete", EXCLUDED, Map.of("CONTEXT_UNSUPPORTED", 3L),
                        Map.of("REQUIREMENT_UNKNOWN", 9L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), WORKFORCE_AVAILABLE,
                        WeightedComparisonPreview.ScoreStatus.EXCLUDED, null),
                expected("fictional-no-scim", EXCLUDED, Map.of("CONTEXT_UNSUPPORTED", 3L),
                        Map.of("REQUIREMENT_UNKNOWN", 9L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), WORKFORCE_AVAILABLE,
                        WeightedComparisonPreview.ScoreStatus.EXCLUDED, null),
                expected("fictional-unreviewed", UNRESOLVED, Map.of(),
                        Map.of("REQUIREMENT_UNKNOWN", 9L, "COMPLIANCE_SCOPE_UNKNOWN", 1L), WORKFORCE_AVAILABLE,
                        UNRESOLVED_HARD_CONSTRAINTS, null)));
    }

    @Test
    void clarifiedB2bInputsCanScoreOnlyCheckedOptionUntilEvidenceGoesStale() throws Exception {
        var cases = new CatalogScenarioCases(mapper);
        var catalog = catalog();
        assertCase(cases, catalog, "b2b-saas", true, AT, B2B_WEIGHTS, List.of(
                expected("fictional-complete", PASSES_CHECKED_REQUIREMENTS, Map.of(), Map.of(), B2B_AVAILABLE,
                        SCORED, 100),
                expected("fictional-no-scim", EXCLUDED, Map.of("REQUIRED_CAPABILITY_UNAVAILABLE", 1L),
                        Map.of(), B2B_AVAILABLE, WeightedComparisonPreview.ScoreStatus.EXCLUDED, null),
                expected("fictional-unreviewed", UNRESOLVED, Map.of(), Map.of("EVIDENCE_UNREVIEWED", 1L),
                        B2B_AVAILABLE, UNRESOLVED_HARD_CONSTRAINTS, null)));

        assertCase(cases, catalog, "b2b-saas", true, AT.plusSeconds(91L * 86400), B2B_WEIGHTS, List.of(
                expected("fictional-complete", UNRESOLVED, Map.of(), Map.of("EVIDENCE_STALE", 13L),
                        B2B_UNKNOWN, UNRESOLVED_HARD_CONSTRAINTS, null),
                expected("fictional-no-scim", UNRESOLVED, Map.of(), Map.of("EVIDENCE_STALE", 13L),
                        B2B_UNKNOWN, UNRESOLVED_HARD_CONSTRAINTS, null),
                expected("fictional-unreviewed", UNRESOLVED, Map.of(),
                        Map.of("EVIDENCE_STALE", 12L, "EVIDENCE_UNREVIEWED", 1L),
                        B2B_UNKNOWN, UNRESOLVED_HARD_CONSTRAINTS, null)));
    }

    private void assertCase(CatalogScenarioCases cases, ProviderCatalog catalog, String profileId,
            boolean clarifyB2b, Instant at, Map<ProviderCatalog.Capability, Integer> weights,
            List<ExpectedCandidate> expected) {
        var definition = cases.definitions().stream().filter(item -> item.id().equals(profileId)).findFirst().orElseThrow();
        var json = (ObjectNode) definition.profile();
        if (clarifyB2b) {
            // Test-only explicit choices; never infer these values for an owner's assessment.
            var security = (ObjectNode) json.get("security");
            security.put("complianceScopeStatus", "NONE_IDENTIFIED");
            security.put("dataResidency", "NOT_REQUIRED");
            var controls = (ObjectNode) security.get("authenticationControls");
            for (var field : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) {
                controls.put(field, "NOT_REQUIRED");
            }
        }
        var profile = mapper.treeToValue(json, ApplicationIdentityProfile.class);
        var eligibility = new EligibilityPreflightV4(WORKSPACE, ASSESSMENT, 1, catalog.catalogVersion(), catalog.kind(),
                EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION, CapabilityEvaluator.POLICY_VERSION,
                EligibilityEvaluator.POLICY_VERSION, ResidencyEvaluator.POLICY_VERSION,
                AuthenticationControlEvaluator.POLICY_VERSION, ComplianceScopeEvaluator.POLICY_VERSION,
                profile.security().assurance(), at, "SYNTHETIC_ELIGIBILITY_PREFLIGHT", false,
                EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS,
                ComplianceScopeEvaluator.evaluate(profile.security()),
                EligibilityEvaluator.evaluateWithComplianceScope(profile, catalog, at));
        var comparison = SyntheticComparison.from(eligibility);
        assertEquals(WORKSPACE, comparison.workspaceId());
        assertEquals(ASSESSMENT, comparison.assessmentId());
        assertEquals(1, comparison.assessmentVersion());
        assertEquals(SyntheticComparison.POLICY_VERSION, comparison.policyVersion());
        assertEquals(HardConstraintPreflight.POLICY_VERSION, comparison.hardConstraintPolicyVersion());
        assertEquals(CapabilityPreferenceEvaluator.POLICY_VERSION, comparison.preferencePolicyVersion());
        assertEquals(at, comparison.evaluatedAt());
        assertFalse(comparison.rankingPerformed());
        assertFalse(comparison.recommendationReady());
        assertTrue(comparison.deferredPaths().contains("operations"));
        assertEquals(eligibility.candidates(), EligibilityEvaluator.evaluateWithComplianceScope(profile, catalog, at),
                profileId + " must be deterministic at a fixed instant");

        Map<String, WeightedComparisonPreview.CandidateScore> scores = Map.of();
        if (weights != null) {
            var preview = WeightedComparisonPreview.from(comparison, new WeightedComparisonRequest(weights));
            assertFalse(preview.rankingPerformed());
            assertFalse(preview.recommendationReady());
            assertEquals(comparison, preview.comparison());
            preview.scores().stream().filter(score -> score.score() == null).forEach(score ->
                    assertTrue(score.contributions().isEmpty(), score.optionId() + " must withhold contributions"));
            scores = preview.scores().stream().collect(Collectors.toMap(
                    WeightedComparisonPreview.CandidateScore::optionId, Function.identity()));
        }
        var actual = new java.util.ArrayList<ExpectedCandidate>();
        for (var candidate : comparison.candidates()) {
            if (profileId.equals("b2b-saas") && candidate.optionId().equals("fictional-no-scim")
                    && at.equals(AT)) {
                assertEquals(List.of("provisioning.scim"), candidate.exclusionReasons().stream()
                        .map(HardConstraintPreflight.Finding::profilePath).toList());
            }
            var score = scores.get(candidate.optionId());
            actual.add(expected(candidate.optionId(), candidate.hardVerdict(), reasons(candidate.exclusionReasons()),
                    reasons(candidate.informationGaps()), candidate.capabilityPreferences().stream().collect(
                            Collectors.toMap(preference -> preference.capability().name(),
                                    preference -> preference.outcome().name())),
                    score == null ? null : score.status(), score == null ? null : score.score()));
        }
        assertEquals(expected, actual, profileId + " at " + at);
    }

    private ProviderCatalog catalog() throws Exception {
        try (var input = new ClassPathResource("catalog/synthetic.v4.json").getInputStream()) {
            return mapper.readValue(input, ProviderCatalog.class);
        }
    }

    private static Map<String, Long> reasons(List<HardConstraintPreflight.Finding> findings) {
        return findings.stream().collect(Collectors.groupingBy(HardConstraintPreflight.Finding::reasonCode,
                Collectors.counting()));
    }

    private record ExpectedCandidate(String optionId, HardConstraintPreflight.Verdict verdict,
            Map<String, Long> exclusionReasons, Map<String, Long> informationGaps, Map<String, String> preferences,
            WeightedComparisonPreview.ScoreStatus scoreStatus, Integer score) { }

    private static ExpectedCandidate expected(String optionId, HardConstraintPreflight.Verdict verdict,
            Map<String, Long> exclusionReasons, Map<String, Long> informationGaps, Map<String, String> preferences,
            WeightedComparisonPreview.ScoreStatus scoreStatus, Integer score) {
        return new ExpectedCandidate(optionId, verdict, exclusionReasons, informationGaps, preferences, scoreStatus, score);
    }
}
