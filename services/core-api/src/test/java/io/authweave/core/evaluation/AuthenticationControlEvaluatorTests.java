package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.*;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.*;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.ProviderCatalog.*;

import static io.authweave.core.catalog.ProviderCatalog.Support.*;
import static io.authweave.core.catalog.ProviderCatalog.EvidenceStatus.*;
import static io.authweave.core.catalog.ProviderCatalog.AuthenticationControl.*;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationControlEvaluatorTests {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final URI SOURCE = URI.create("https://evidence.authweave.invalid/control");

    @ParameterizedTest
    @CsvSource({"SUPPORTED,SUPPORTED,PASS,CONTROL_ENFORCEABLE",
            "SUPPORTED,UNSUPPORTED,FAIL,ENFORCEMENT_UNSUPPORTED", "SUPPORTED,UNKNOWN,UNKNOWN,ENFORCEMENT_UNKNOWN",
            "UNSUPPORTED,UNSUPPORTED,FAIL,CONTROL_UNAVAILABLE", "UNSUPPORTED,UNKNOWN,FAIL,CONTROL_UNAVAILABLE",
            "UNKNOWN,UNSUPPORTED,FAIL,ENFORCEMENT_UNSUPPORTED", "UNKNOWN,UNKNOWN,UNKNOWN,CONTROL_AVAILABILITY_UNKNOWN"})
    void requiresAvailabilityAndEnforceability(Support availability, Support enforcement, String outcome, String reason) {
        for (var check : evaluate(REQUIRED, new AuthenticationControlFact(availability, enforcement, REVIEWED, SOURCE, NOW))) {
            assertEquals(outcome, check.outcome().name());
            assertEquals(reason, check.reasonCode().name());
        }
    }

    @ParameterizedTest
    @CsvSource({"REQUIRED,PASS,CONTROL_ENFORCEABLE", "PREFERRED,NOT_APPLIED,PREFERENCE_NOT_SCORED",
            "NOT_REQUIRED,NOT_APPLIED,NO_REQUIREMENT", "UNKNOWN,UNKNOWN,REQUIREMENT_UNKNOWN",
            "FORBIDDEN,UNKNOWN,CONTROL_INTENT_UNCLEAR"})
    void criticalitiesDoNotInventPreferencesOrWeakerAuthentication(RequirementCriticality criticality, String outcome, String reason) {
        for (var check : evaluate(criticality, fact(SUPPORTED, REVIEWED, NOW))) {
            assertEquals(outcome, check.outcome().name());
            assertEquals(reason, check.reasonCode().name());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Support.class, names = {"SUPPORTED", "UNSUPPORTED"})
    void evidenceGatePrecedesBothPositiveAndNegativeClaims(Support support) {
        for (var check : evaluate(REQUIRED, null)) assertEquals("EVIDENCE_MISSING", check.reasonCode().name());
        var cases = Map.of("EVIDENCE_UNREVIEWED", fact(support, UNREVIEWED, NOW),
                "EVIDENCE_FROM_FUTURE", fact(support, REVIEWED, NOW.plusNanos(1)),
                "EVIDENCE_STALE", fact(support, REVIEWED, NOW.minus(Duration.ofDays(90)).minusNanos(1)));
        cases.forEach((reason, evidence) -> evaluate(REQUIRED, evidence).forEach(check -> {
            assertEquals("UNKNOWN", check.outcome().name()); assertEquals(reason, check.reasonCode().name());
        }));
        evaluate(REQUIRED, fact(support, REVIEWED, NOW.minus(Duration.ofDays(90)))).forEach(check ->
                assertEquals(support == SUPPORTED ? "PASS" : "FAIL", check.outcome().name()));
    }

    @Test
    void scopeIsExplicitAndNeverBorrowedAcrossClientPopulationOrOption() {
        var option = option(fact(SUPPORTED, REVIEWED, NOW));
        var profile = profile(REQUIRED, Set.of(ClientType.BROWSER, ClientType.NATIVE_MOBILE, ClientType.MACHINE_TO_MACHINE),
                Set.of(UserPopulation.PARTNERS, UserPopulation.EMPLOYEES), AssuranceLevel.HIGH);
        var checks = AuthenticationControlEvaluator.evaluate(profile, option, NOW);
        assertEquals(12, checks.size());
        assertEquals(3, checks.stream().filter(c -> c.outcome().name().equals("PASS")).count());
        assertEquals(9, checks.stream().filter(c -> c.reasonCode().name().equals("EVIDENCE_MISSING")).count());
        var empty = new Option("empty", "Empty", "Test", "Test", Map.of(), Compatibility.empty());
        assertTrue(AuthenticationControlEvaluator.evaluate(profile, empty, NOW).stream()
                .allMatch(c -> c.reasonCode().name().equals("EVIDENCE_MISSING")));
        assertEquals("CLIENT_SCOPE_UNKNOWN", AuthenticationControlEvaluator.evaluate(
                profile(REQUIRED, Set.of(), Set.of(UserPopulation.PARTNERS), AssuranceLevel.HIGH), option, NOW).getFirst().reasonCode().name());
        assertEquals("POPULATION_SCOPE_UNKNOWN", AuthenticationControlEvaluator.evaluate(
                profile(REQUIRED, Set.of(ClientType.BROWSER), Set.of(), AssuranceLevel.HIGH), option, NOW).getFirst().reasonCode().name());
        var all = AuthenticationControlEvaluator.evaluate(profile(REQUIRED, Set.of(ClientType.BROWSER, ClientType.NATIVE_MOBILE),
                Set.of(UserPopulation.values()), AssuranceLevel.HIGH), option, NOW);
        assertEquals(36, all.size());
        assertEquals(all, AuthenticationControlEvaluator.evaluate(profile(REQUIRED, Set.of(ClientType.NATIVE_MOBILE, ClientType.BROWSER),
                Set.of(UserPopulation.values()), AssuranceLevel.HIGH), option, NOW));
    }

    @ParameterizedTest
    @EnumSource(RequirementCriticality.class)
    void workloadOnlyProfilesDoNotPretendToEvaluateHumanAuthentication(RequirementCriticality criticality) {
        var checks = AuthenticationControlEvaluator.evaluate(profile(criticality, Set.of(ClientType.MACHINE_TO_MACHINE),
                Set.of(), AssuranceLevel.HIGH), option(null), NOW);
        assertEquals(3, checks.size());
        checks.forEach(c -> {
            assertEquals("NOT_APPLIED", c.outcome().name());
            assertEquals("HUMAN_AUTH_NOT_APPLICABLE", c.reasonCode().name());
            assertNull(c.client()); assertNull(c.population()); assertNull(c.evidence());
        });
    }

    @Test
    void broadAssuranceLabelsDoNotSetControlsAndControlsStayIndependent() {
        var option = option(fact(SUPPORTED, REVIEWED, NOW));
        var baseline = profile(RequirementCriticality.UNKNOWN, Set.of(ClientType.BROWSER), Set.of(UserPopulation.PARTNERS), AssuranceLevel.BASELINE);
        var expected = AuthenticationControlEvaluator.evaluate(baseline, option, NOW);
        for (var assurance : AssuranceLevel.values()) assertEquals(expected, AuthenticationControlEvaluator.evaluate(
                profile(RequirementCriticality.UNKNOWN, Set.of(ClientType.BROWSER), Set.of(UserPopulation.PARTNERS), assurance), option, NOW));
        expected.forEach(c -> assertEquals("REQUIREMENT_UNKNOWN", c.reasonCode().name()));
        var s = baseline.security();
        var mixed = new ApplicationIdentityProfile(baseline.application(), baseline.audience(), baseline.protocols(), baseline.provisioning(),
                new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), s.auditability(),
                s.dataResidency(), s.assurance(), s.complianceTargets(), s.dataResidencyDetails(),
                new AuthenticationControls(REQUIRED, RequirementCriticality.PREFERRED, RequirementCriticality.UNKNOWN)), baseline.operations());
        assertEquals(List.of("PASS", "NOT_APPLIED", "UNKNOWN"), AuthenticationControlEvaluator.evaluate(mixed, option, NOW)
                .stream().map(c -> c.outcome().name()).toList());
    }

    @Test
    void combinedPreflightRetainsUnknownsWhileFailureWins() throws Exception {
        ProviderCatalog catalog;
        try (var input = new ClassPathResource("catalog/synthetic.v4.json").getInputStream()) {
            catalog = JsonMapper.builder().build().readValue(input, ProviderCatalog.class);
        }
        var profile = profile(REQUIRED, Set.of(ClientType.BROWSER), Set.of(UserPopulation.PARTNERS), AssuranceLevel.HIGH);
        var candidates = EligibilityEvaluator.evaluateWithAuthenticationControls(profile, catalog, NOW);
        assertEquals("NEEDS_INFORMATION", candidates.getFirst().status().name()); // Other requirements remain unknown.
        assertEquals("DOES_NOT_MATCH", candidates.get(1).status().name());
        assertTrue(candidates.get(1).authenticationControlChecks().stream().anyMatch(c -> c.outcome().name().equals("UNKNOWN")));
        assertEquals("NEEDS_INFORMATION", candidates.getLast().status().name());
        assertEquals(candidates, EligibilityEvaluator.evaluateWithAuthenticationControls(profile,
                new ProviderCatalog(4, catalog.catalogVersion(), catalog.kind(), catalog.options().reversed()), NOW));
    }

    @Test
    void catalogRejectsContradictoryOrUnscopedEvidenceAndCopiesNestedMaps() {
        for (var availability : List.of(UNSUPPORTED, Support.UNKNOWN)) assertThrows(IllegalArgumentException.class,
                () -> new AuthenticationControlFact(availability, SUPPORTED, REVIEWED, SOURCE, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Option("test", "Test", "Test", "Test", Map.of(), Compatibility.empty(),
                Map.of(), Map.of(ClientType.MACHINE_TO_MACHINE, Map.of())));
        var facts = new HashMap<AuthenticationControl, AuthenticationControlFact>();
        facts.put(PHISHING_RESISTANCE, fact(SUPPORTED, REVIEWED, NOW));
        var populations = new HashMap<UserPopulation, Map<AuthenticationControl, AuthenticationControlFact>>();
        populations.put(UserPopulation.PARTNERS, facts);
        var clients = new HashMap<ClientType, Map<UserPopulation, Map<AuthenticationControl, AuthenticationControlFact>>>();
        clients.put(ClientType.BROWSER, populations);
        var option = new Option("test", "Test", "Test", "Test", Map.of(), Compatibility.empty(), Map.of(), clients);
        facts.clear(); populations.clear(); clients.clear();
        assertEquals(1, option.authenticationControls().get(ClientType.BROWSER).get(UserPopulation.PARTNERS).size());
        assertThrows(UnsupportedOperationException.class, () -> option.authenticationControls().get(ClientType.BROWSER)
                .get(UserPopulation.PARTNERS).clear());
    }

    private static List<AuthenticationControlCheck> evaluate(RequirementCriticality criticality, AuthenticationControlFact fact) {
        return AuthenticationControlEvaluator.evaluate(profile(criticality, Set.of(ClientType.BROWSER),
                Set.of(UserPopulation.PARTNERS), AssuranceLevel.HIGH), option(fact), NOW);
    }

    private static AuthenticationControlFact fact(Support support, EvidenceStatus status, Instant at) {
        return new AuthenticationControlFact(support, support, status, SOURCE, at);
    }

    private static Option option(AuthenticationControlFact fact) {
        var controls = fact == null ? Map.<AuthenticationControl, AuthenticationControlFact>of()
                : Map.of(PHISHING_RESISTANCE, fact, NON_EXPORTABLE_KEYS, fact, STEP_UP_AUTHENTICATION, fact);
        return new Option("test", "Test", "Test", "Test", Map.of(), Compatibility.empty(), Map.of(),
                Map.of(ClientType.BROWSER, Map.of(UserPopulation.PARTNERS, controls)));
    }

    private static ApplicationIdentityProfile profile(RequirementCriticality criticality, Set<ClientType> clients,
            Set<UserPopulation> populations, AssuranceLevel assurance) {
        var base = ApplicationIdentityProfile.unknown();
        var s = base.security();
        return new ApplicationIdentityProfile(new ApplicationTopology(ApplicationType.B2B_SAAS, clients),
                new AudienceRequirements(populations, TenancyModel.MULTI_TENANT_ORGANIZATIONS, MembershipModel.SINGLE_ORGANIZATION_PER_USER),
                base.protocols(), base.provisioning(), new SecurityRequirements(s.multiFactorAuthentication(),
                s.browserTokenExposureMinimization(), s.auditability(), s.dataResidency(), assurance, s.complianceTargets(),
                s.dataResidencyDetails(), new AuthenticationControls(criticality, criticality, criticality)), base.operations());
    }
}
