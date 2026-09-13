package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.ProviderCatalog.*;

import static io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory.*;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.*;
import static io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Status.*;
import static io.authweave.core.evaluation.ResidencyCheck.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class ResidencyEvaluatorTests {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final URI SOURCE = URI.create("https://catalog.example.invalid/test#residency");

    @Test
    void completeEvidenceMustFitTheEntireAllowlistWithoutRequiringEveryAllowedCountry() {
        var security = security(REQUIRED, Set.of("DE", "FR"), Set.of(USER_PROFILES));
        var check = only(security, fact(COMPLETE, "DE"));
        assertEquals(PASS, check.outcome());
        assertEquals(STORAGE_WITHIN_ALLOWED_COUNTRIES, check.reasonCode());
        assertEquals(List.of("DE", "FR"), check.allowedCountries());
        assertTrue(check.outsideAllowedCountries().isEmpty());
        assertEquals(USER_PROFILES, check.dataCategory());
        var fail = only(security, fact(COMPLETE, "DE", "US"));
        assertEquals(FAIL, fail.outcome());
        assertEquals(STORAGE_OUTSIDE_ALLOWED_COUNTRIES, fail.reasonCode());
        assertEquals(List.of("US"), fail.outsideAllowedCountries());
    }

    @Test
    void partialEvidenceCanProveAViolationButNeverProveAMatch() {
        var security = security(REQUIRED, Set.of("DE"), Set.of(USER_PROFILES));
        var incomplete = only(security, fact(PARTIAL, "DE"));
        assertEquals(CapabilityPreflight.Outcome.UNKNOWN, incomplete.outcome());
        assertEquals(STORAGE_LOCATIONS_INCOMPLETE, incomplete.reasonCode());
        assertEquals(FAIL, only(security, fact(PARTIAL, "US")).outcome());
        var unknown = only(security, fact(ResidencyCoverage.UNKNOWN));
        assertEquals(CapabilityPreflight.Outcome.UNKNOWN, unknown.outcome());
        assertEquals(STORAGE_LOCATIONS_UNKNOWN, unknown.reasonCode());
    }

    @Test
    void criticalityNeverTurnsAPreferenceOrAmbiguousProhibitionIntoAHardExclusion() {
        for (var c : RequirementCriticality.values()) {
            var check = only(security(c, Set.of("DE"), Set.of(USER_PROFILES)), fact(COMPLETE, "US"));
            switch (c) {
                case REQUIRED -> assertEquals(FAIL, check.outcome());
                case PREFERRED -> {
                    assertEquals(NOT_APPLIED, check.outcome());
                    assertEquals(PREFERENCE_NOT_SCORED, check.reasonCode());
                }
                case NOT_REQUIRED -> {
                    assertEquals(NOT_APPLIED, check.outcome());
                    assertEquals(NO_REQUIREMENT, check.reasonCode());
                }
                case FORBIDDEN -> {
                    assertEquals(CapabilityPreflight.Outcome.UNKNOWN, check.outcome());
                    assertEquals(RESIDENCY_INTENT_UNCLEAR, check.reasonCode());
                }
                case UNKNOWN -> {
                    assertEquals(CapabilityPreflight.Outcome.UNKNOWN, check.outcome());
                    assertEquals(REQUIREMENT_UNKNOWN, check.reasonCode());
                }
            }
        }
    }

    @Test
    void missingProfileDetailsDoNotBecomeUnrestrictedStorage() {
        var scope = ResidencyEvaluator.evaluate(security(REQUIRED, Set.of("DE"), Set.of()), Map.of(), NOW).getFirst();
        assertEquals(DATA_SCOPE_UNKNOWN, scope.reasonCode());
        assertNull(scope.dataCategory());
        assertNull(scope.evidence());
        assertEquals(ALLOWED_COUNTRIES_UNKNOWN,
                only(security(REQUIRED, Set.of(), Set.of(USER_PROFILES)), fact(COMPLETE, "DE")).reasonCode());
        assertEquals(REQUIREMENT_UNKNOWN, ResidencyEvaluator.evaluate(SecurityRequirements.unknown(), Map.of(), NOW)
                .getFirst().reasonCode());
        for (var c : List.of(PREFERRED, NOT_REQUIRED)) {
            assertEquals(NOT_APPLIED, ResidencyEvaluator.evaluate(security(c, Set.of(), Set.of()), Map.of(), NOW)
                    .getFirst().outcome());
        }
    }

    @Test
    void evidenceGateAppliesToBothInsideAndOutsideStorageBeforeAnyConclusion() {
        for (var country : List.of("DE", "US")) {
            var security = security(REQUIRED, Set.of("DE"), Set.of(USER_PROFILES));
            var unreviewed = new ResidencyFact(COMPLETE, List.of(country), EvidenceStatus.UNREVIEWED, SOURCE, NOW);
            var future = new ResidencyFact(COMPLETE, List.of(country), EvidenceStatus.REVIEWED, SOURCE, NOW.plusNanos(1));
            var stale = new ResidencyFact(PARTIAL, List.of(country), EvidenceStatus.REVIEWED, SOURCE,
                    NOW.minus(EvidencePolicy.MAX_AGE).minusNanos(1));
            for (var fact : List.of(unreviewed, future, stale)) {
                var check = only(security, fact);
                assertEquals(CapabilityPreflight.Outcome.UNKNOWN, check.outcome());
                assertEquals(ResidencyCheck.Reason.valueOf(EvidencePolicy.problem(fact, NOW).name()), check.reasonCode());
                assertEquals(fact, check.evidence());
                assertTrue(check.outsideAllowedCountries().isEmpty(), "Unusable evidence cannot establish outside storage.");
            }
            var boundary = new ResidencyFact(COMPLETE, List.of(country), EvidenceStatus.REVIEWED, SOURCE,
                    NOW.minus(EvidencePolicy.MAX_AGE));
            assertEquals(country.equals("DE") ? PASS : FAIL, only(security, boundary).outcome());
        }
    }

    @Test
    void categoryAndOptionBoundariesDoNotBorrowEvidenceOrTrustRegionLabels() throws Exception {
        var base = fixture().options().getFirst();
        var isolated = new Option("isolated", base.displayName(), "Another plan", "DE only", base.facts(),
                base.compatibility(), Map.of(USER_PROFILES, fact(COMPLETE, "DE")));
        var profile = profile(REQUIRED, Set.of("DE"), Set.of(BACKUPS, USER_PROFILES));
        var checks = EligibilityEvaluator.evaluateWithResidency(profile,
                new ProviderCatalog(3, "test", Kind.SYNTHETIC, List.of(base, isolated)), NOW).getLast().residencyChecks();
        assertEquals(List.of(BACKUPS, USER_PROFILES), checks.stream().map(ResidencyCheck::dataCategory).toList());
        assertEquals(EVIDENCE_MISSING, checks.getFirst().reasonCode());
        assertNull(checks.getFirst().evidence());
        assertEquals(PASS, checks.getLast().outcome());
        var unselected = ResidencyEvaluator.evaluate(security(REQUIRED, Set.of("DE"), Set.of(USER_PROFILES)),
                Map.of(USER_PROFILES, fact(COMPLETE, "DE"), BACKUPS, fact(COMPLETE, "US")), NOW);
        assertEquals(1, unselected.size());
        assertEquals(PASS, unselected.getFirst().outcome());
    }

    @Test
    void changingOnlyTheAllowlistChangesEligibilityAndPreservesLegacyChecks() throws Exception {
        var catalog = fixture();
        var allowed = profile(REQUIRED, Set.of("DE", "FR"), Set.of(USER_PROFILES, BACKUPS));
        var restricted = profile(REQUIRED, Set.of("DE"), Set.of(USER_PROFILES, BACKUPS));
        var first = EligibilityEvaluator.evaluateWithResidency(allowed, catalog, NOW);
        assertEquals(List.of(MATCHES_CHECKED_REQUIREMENTS, DOES_NOT_MATCH, NEEDS_INFORMATION),
                first.stream().map(EligibilityPreflightV2.Candidate::status).toList());
        var blocked = EligibilityEvaluator.evaluateWithResidency(restricted, catalog, NOW).getFirst();
        assertEquals(DOES_NOT_MATCH, blocked.status());
        assertEquals(List.of("FR"), blocked.residencyChecks().getFirst().outsideAllowedCountries());
        assertEquals(first.getFirst().capabilityChecks(), blocked.capabilityChecks());
        assertEquals(first.getFirst().contextChecks(), blocked.contextChecks());
        assertEquals(EligibilityEvaluator.evaluate(allowed, catalog, NOW), EligibilityEvaluator.evaluate(restricted, catalog, NOW));
        assertTrue(EligibilityEvaluator.DEFERRED_PATHS.contains("security.dataResidency"));
        assertFalse(EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS.contains("security.dataResidency"));
    }

    @Test
    void failuresInAnyFamilyDominateWithoutHidingUnknownResidencyOrContext() throws Exception {
        var catalog = fixture();
        var incomplete = EligibilityEvaluator.evaluateWithResidency(profile(REQUIRED, Set.of(), Set.of()), catalog, NOW);
        assertEquals(DOES_NOT_MATCH, incomplete.get(1).status()); // Existing SCIM constraint still fails.
        assertEquals(DATA_SCOPE_UNKNOWN, incomplete.get(1).residencyChecks().getFirst().reasonCode());
        var blank = ApplicationIdentityProfile.unknown();
        var required = new ApplicationIdentityProfile(blank.application(), blank.audience(), blank.protocols(),
                blank.provisioning(), security(REQUIRED, Set.of("CA"), Set.of(USER_PROFILES)), blank.operations());
        var blocked = EligibilityEvaluator.evaluateWithResidency(required, catalog, NOW).getFirst();
        assertEquals(DOES_NOT_MATCH, blocked.status());
        assertTrue(blocked.contextChecks().stream().allMatch(c -> c.outcome() == CapabilityPreflight.Outcome.UNKNOWN));
        var allowed = EligibilityEvaluator.evaluateWithResidency(profile(NOT_REQUIRED, Set.of(), Set.of()), catalog, NOW);
        assertEquals(MATCHES_CHECKED_REQUIREMENTS, allowed.getFirst().status());
        assertEquals(NEEDS_INFORMATION, EligibilityEvaluator.evaluateWithResidency(blank, catalog, NOW).getFirst().status());
    }

    @Test
    void optionCountryAndCategoryOrderingDoesNotChangeResults() throws Exception {
        var catalog = fixture();
        var profile = profile(REQUIRED, Set.of("FR", "DE"), Set.of(USER_PROFILES, BACKUPS, CREDENTIALS, AUDIT_LOGS));
        var checks = EligibilityEvaluator.evaluateWithResidency(profile, catalog, NOW);
        assertEquals(checks, EligibilityEvaluator.evaluateWithResidency(profile,
                new ProviderCatalog(3, catalog.catalogVersion(), catalog.kind(), catalog.options().reversed()), NOW));
        assertEquals(List.of(AUDIT_LOGS, BACKUPS, CREDENTIALS, USER_PROFILES),
                checks.getFirst().residencyChecks().stream().map(ResidencyCheck::dataCategory).toList());
        assertEquals(fact(COMPLETE, "DE", "FR"), fact(COMPLETE, "FR", "DE"));
        var countries = new ArrayList<>(List.of("DE"));
        var immutable = new ResidencyFact(COMPLETE, countries, EvidenceStatus.REVIEWED, SOURCE, NOW);
        countries.add("US");
        assertEquals(List.of("DE"), immutable.storageCountries());
        assertThrows(UnsupportedOperationException.class, () -> immutable.storageCountries().add("US"));
    }

    @Test
    void catalogRejectsAmbiguousCountryCoverageAndUnscopedProvenance() {
        for (var coverage : List.of(COMPLETE, PARTIAL)) {
            assertThrows(IllegalArgumentException.class, () -> fact(coverage));
        }
        assertThrows(IllegalArgumentException.class, () -> fact(ResidencyCoverage.UNKNOWN, "DE"));
        for (var countries : List.of(List.of("DE", "DE"), List.of("de"), List.of("EU"), List.of("ZZ"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ResidencyFact(COMPLETE, countries, EvidenceStatus.REVIEWED, SOURCE, NOW));
        }
        for (var url : List.of("https://vendor.example.com/region", "https://user:secret@catalog.example.invalid/facts")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ResidencyFact(COMPLETE, List.of("DE"), EvidenceStatus.REVIEWED, URI.create(url), NOW));
        }
        assertThrows(NullPointerException.class, () -> new ResidencyFact(COMPLETE, List.of("DE"), null, SOURCE, NOW));
        assertThrows(NullPointerException.class, () -> new ResidencyFact(COMPLETE, List.of("DE"), EvidenceStatus.REVIEWED, SOURCE, null));
    }

    private static ResidencyCheck only(SecurityRequirements security, ResidencyFact fact) {
        return ResidencyEvaluator.evaluate(security, Map.of(USER_PROFILES, fact), NOW).getFirst();
    }

    private static ResidencyFact fact(ResidencyCoverage coverage, String... countries) {
        return new ResidencyFact(coverage, List.of(countries), EvidenceStatus.REVIEWED, SOURCE, NOW);
    }

    private static SecurityRequirements security(RequirementCriticality criticality, Set<String> countries,
            Set<DataResidencyDetails.DataCategory> categories) {
        var base = SecurityRequirements.unknown();
        return new SecurityRequirements(base.multiFactorAuthentication(), base.browserTokenExposureMinimization(),
                base.auditability(), criticality, base.assurance(), base.complianceTargets(),
                new DataResidencyDetails(countries, categories));
    }

    private static ApplicationIdentityProfile profile(RequirementCriticality criticality, Set<String> countries,
            Set<DataResidencyDetails.DataCategory> categories) throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            var p = mapper.treeToValue(mapper.readTree(input).get(0).get("profile"), ApplicationIdentityProfile.class);
            var s = p.security();
            return new ApplicationIdentityProfile(p.application(), p.audience(), p.protocols(), p.provisioning(),
                    new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(),
                    s.auditability(), criticality, s.assurance(), s.complianceTargets(),
                    new DataResidencyDetails(countries, categories)), p.operations());
        }
    }

    private static ProviderCatalog fixture() throws Exception {
        try (var input = new ClassPathResource("catalog/synthetic.v3.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(input, ProviderCatalog.class);
        }
    }
}
