package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.ProviderCatalog.*;

import static io.authweave.core.assessment.domain.profile.RequirementCriticality.*;
import static io.authweave.core.catalog.ProviderCatalog.Capability.*;
import static io.authweave.core.evaluation.CapabilityPreflight.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityEvaluatorTests {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final URI SOURCE = URI.create("https://example.invalid/test#capability");

    @ParameterizedTest
    @CsvSource({
            "REQUIRED, OPTIONAL, PASS, REQUIRED_CAPABILITY_AVAILABLE",
            "REQUIRED, MANDATORY, PASS, REQUIRED_CAPABILITY_AVAILABLE",
            "REQUIRED, UNAVAILABLE, FAIL, REQUIRED_CAPABILITY_UNAVAILABLE",
            "REQUIRED, UNKNOWN, UNKNOWN, CAPABILITY_UNKNOWN",
            "FORBIDDEN, OPTIONAL, PASS, FORBIDDEN_CAPABILITY_AVOIDABLE",
            "FORBIDDEN, MANDATORY, FAIL, FORBIDDEN_CAPABILITY_UNAVOIDABLE",
            "FORBIDDEN, UNAVAILABLE, PASS, FORBIDDEN_CAPABILITY_AVOIDABLE",
            "FORBIDDEN, UNKNOWN, UNKNOWN, CAPABILITY_UNKNOWN",
            "PREFERRED, UNAVAILABLE, NOT_APPLIED, PREFERENCE_NOT_SCORED",
            "NOT_REQUIRED, MANDATORY, NOT_APPLIED, NO_REQUIREMENT",
            "UNKNOWN, OPTIONAL, UNKNOWN, REQUIREMENT_UNKNOWN"
    })
    void appliesCriticalityWithoutTreatingAnOptionalCapabilityAsMandatory(
            RequirementCriticality criticality, Availability availability, Outcome outcome, Reason reason) {
        var check = check(profile(criticality, NOT_REQUIRED), SCIM,
                new Fact(availability, EvidenceStatus.REVIEWED, SOURCE, NOW));
        assertEquals(outcome, check.outcome());
        assertEquals(reason, check.reasonCode());
    }

    @Test
    void changingScimFromRequiredToPreferredRemovesItsEliminationWithoutScoring() {
        var catalog = catalog(Map.of(SCIM, fact(Availability.UNAVAILABLE, NOW)));
        assertEquals(Status.DOES_NOT_MATCH,
                CapabilityEvaluator.evaluate(profile(REQUIRED, NOT_REQUIRED), catalog, NOW).getFirst().status());
        var preferred = CapabilityEvaluator.evaluate(profile(PREFERRED, NOT_REQUIRED), catalog, NOW).getFirst();
        assertNotEquals(Status.DOES_NOT_MATCH, preferred.status());
        assertEquals(Reason.PREFERENCE_NOT_SCORED, find(preferred, SCIM).reasonCode());
        // No hard constraints at all must not create an affirmative match.
        assertEquals(Status.NEEDS_INFORMATION, preferred.status());
    }

    @Test
    void missingUnreviewedStaleAndFutureFactsCannotProveEitherPassOrFailure() {
        assertEquals(Reason.EVIDENCE_MISSING, check(profile(REQUIRED, NOT_REQUIRED), SCIM, null).reasonCode());
        for (var availability : List.of(Availability.OPTIONAL, Availability.UNAVAILABLE)) {
            assertUncertain(new Fact(availability, EvidenceStatus.UNREVIEWED, SOURCE, NOW), Reason.EVIDENCE_UNREVIEWED);
            assertUncertain(fact(availability, NOW.minus(CapabilityEvaluator.MAX_EVIDENCE_AGE).minusNanos(1)), Reason.EVIDENCE_STALE);
            assertUncertain(fact(availability, NOW.plusNanos(1)), Reason.EVIDENCE_FROM_FUTURE);
        }
        assertEquals(Outcome.PASS, check(profile(REQUIRED, NOT_REQUIRED), SCIM,
                fact(Availability.OPTIONAL, NOW.minus(CapabilityEvaluator.MAX_EVIDENCE_AGE))).outcome());
    }

    @Test
    void hardFailureTakesPrecedenceOverMissingInformationButNeitherIsLost() {
        var result = CapabilityEvaluator.evaluate(profile(REQUIRED, UNKNOWN),
                catalog(Map.of(SCIM, fact(Availability.UNAVAILABLE, NOW))), NOW).getFirst();
        assertEquals(Status.DOES_NOT_MATCH, result.status());
        assertEquals(Outcome.FAIL, find(result, SCIM).outcome());
        assertEquals(Reason.REQUIREMENT_UNKNOWN, find(result, SOCIAL_LOGIN).reasonCode());
    }

    @Test
    void aFreshOptionalSocialLoginSatisfiesAnExplicitProhibitionOnlyIfDisabled() {
        var result = CapabilityEvaluator.evaluate(profile(NOT_REQUIRED, FORBIDDEN),
                catalog(Map.of(SOCIAL_LOGIN, fact(Availability.OPTIONAL, NOW))), NOW).getFirst();
        assertEquals(Status.MATCHES_CHECKED_REQUIREMENTS, result.status());
        assertEquals(Reason.FORBIDDEN_CAPABILITY_AVOIDABLE, find(result, SOCIAL_LOGIN).reasonCode());
        assertTrue(find(result, SOCIAL_LOGIN).explanation().contains("must remain disabled"));
    }

    @Test
    void resultIsDeterministicAndDoesNotDependOnCatalogInputOrder() {
        var a = option("a", Map.of(SCIM, fact(Availability.OPTIONAL, NOW)));
        var b = option("b", Map.of(SCIM, fact(Availability.UNAVAILABLE, NOW)));
        var profile = profile(REQUIRED, NOT_REQUIRED);
        assertEquals(CapabilityEvaluator.evaluate(profile, new ProviderCatalog(1, "test-1", Kind.SYNTHETIC, List.of(a, b)), NOW),
                CapabilityEvaluator.evaluate(profile, new ProviderCatalog(1, "test-1", Kind.SYNTHETIC, List.of(b, a)), NOW));
    }

    @Test
    void factsDoNotLeakBetweenPlansOrRegions() {
        var supported = new Option("plan-a", "Fictional option", "Enterprise", "Region A",
                Map.of(SCIM, fact(Availability.OPTIONAL, NOW)));
        var missing = new Option("plan-b", "Fictional option", "Basic", "Region B", Map.of());
        var results = CapabilityEvaluator.evaluate(profile(REQUIRED, NOT_REQUIRED),
                new ProviderCatalog(1, "test-1", Kind.SYNTHETIC, List.of(supported, missing)), NOW);
        assertEquals(Status.MATCHES_CHECKED_REQUIREMENTS, results.getFirst().status());
        assertEquals(Status.NEEDS_INFORMATION, results.getLast().status());
        assertEquals(Reason.EVIDENCE_MISSING, find(results.getLast(), SCIM).reasonCode());
    }

    @Test
    void rejectsIncompleteProvenanceDuplicateOptionsAndRealSourcesInSyntheticCatalog() {
        assertThrows(NullPointerException.class, () -> new Fact(Availability.OPTIONAL, EvidenceStatus.REVIEWED, null, NOW));
        assertThrows(NullPointerException.class, () -> new Fact(Availability.OPTIONAL, EvidenceStatus.REVIEWED, SOURCE, null));
        assertThrows(IllegalArgumentException.class, () -> new Fact(Availability.OPTIONAL, EvidenceStatus.REVIEWED,
                URI.create("https://vendor.example.com/scim"), NOW));
        assertThrows(IllegalArgumentException.class, () -> new Fact(Availability.OPTIONAL, EvidenceStatus.REVIEWED,
                URI.create("https://user:secret@example.invalid/scim"), NOW));
        assertThrows(IllegalArgumentException.class, () -> new Option("plan", "Plan", " ", "Region A", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Option("plan", "Plan", "Basic", "", Map.of()));
        var option = option("a", Map.of());
        assertThrows(IllegalArgumentException.class, () -> new ProviderCatalog(1, "test", Kind.SYNTHETIC, List.of(option, option)));
        assertThrows(IllegalArgumentException.class, () -> new ProviderCatalog(2, "test", Kind.SYNTHETIC, List.of(option)));
    }

    private static void assertUncertain(Fact fact, Reason reason) {
        for (var criticality : List.of(REQUIRED, FORBIDDEN)) {
            var check = check(profile(criticality, NOT_REQUIRED), SCIM, fact);
            assertEquals(Outcome.UNKNOWN, check.outcome());
            assertEquals(reason, check.reasonCode());
        }
    }

    private static Check check(ApplicationIdentityProfile profile, Capability capability, Fact fact) {
        var result = CapabilityEvaluator.evaluate(profile,
                catalog(fact == null ? Map.of() : Map.of(capability, fact)), NOW).getFirst();
        return find(result, capability);
    }

    private static Check find(Candidate candidate, Capability capability) {
        return candidate.checks().stream().filter(check -> check.capability() == capability).findFirst().orElseThrow();
    }

    private static Fact fact(Availability availability, Instant observedAt) {
        return new Fact(availability, EvidenceStatus.REVIEWED, SOURCE, observedAt);
    }

    private static Option option(String id, Map<Capability, Fact> facts) {
        return new Option(id, "Fictional option", "Synthetic plan", "Synthetic region", facts);
    }

    private static ProviderCatalog catalog(Map<Capability, Fact> facts) {
        return new ProviderCatalog(1, "test-1", Kind.SYNTHETIC, List.of(option("a", facts)));
    }

    private static ApplicationIdentityProfile profile(RequirementCriticality scim, RequirementCriticality social) {
        var unknown = ApplicationIdentityProfile.unknown();
        return new ApplicationIdentityProfile(unknown.application(), unknown.audience(),
                new ProtocolRequirements(Map.of(ProtocolRequirements.FederationProtocol.OIDC, NOT_REQUIRED,
                        ProtocolRequirements.FederationProtocol.SAML, NOT_REQUIRED), NOT_REQUIRED, social, NOT_REQUIRED),
                new ProvisioningRequirements(scim, NOT_REQUIRED, NOT_REQUIRED),
                new SecurityRequirements(NOT_REQUIRED, UNKNOWN, UNKNOWN, UNKNOWN, SecurityRequirements.AssuranceLevel.UNKNOWN, Set.of()),
                unknown.operations());
    }
}
