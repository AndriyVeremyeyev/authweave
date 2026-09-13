package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.ProviderCatalog.*;

import static io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType.B2B_SAAS;
import static io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType.OTHER;
import static io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.*;
import static io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.*;
import static io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel.MULTI_TENANT_ORGANIZATIONS;
import static io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel.NO_ORGANIZATION_BOUNDARY;
import static io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel.MULTIPLE_ORGANIZATIONS_PER_USER;
import static io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel.NOT_APPLICABLE;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Status.*;
import static io.authweave.core.evaluation.EligibilityPreflight.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class EligibilityEvaluatorTests {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final URI SOURCE = URI.create("https://example.invalid/test#context");
    private static final CompatibilityFact YES = fact(Support.SUPPORTED, EvidenceStatus.REVIEWED, NOW);
    private static final CompatibilityFact NO = fact(Support.UNSUPPORTED, EvidenceStatus.REVIEWED, NOW);

    @Test
    void syntheticProfilesDemonstrateCapabilityAndContextExclusionsSeparately() throws Exception {
        var catalog = fixture();
        var b2b = EligibilityEvaluator.evaluate(seed(0), catalog, NOW);
        assertEquals(List.of(MATCHES_CHECKED_REQUIREMENTS, DOES_NOT_MATCH, NEEDS_INFORMATION),
                b2b.stream().map(EligibilityPreflight.Candidate::status).toList());
        assertEquals(6, b2b.getFirst().contextChecks().size());
        assertEquals(9, b2b.getFirst().capabilityChecks().size());
        var workforce = EligibilityEvaluator.evaluate(seed(2), catalog, NOW).getFirst();
        assertEquals(DOES_NOT_MATCH, workforce.status());
        assertEquals(CONTEXT_UNSUPPORTED, workforce.contextChecks().getFirst().reasonCode());
        assertNotEquals(DOES_NOT_MATCH, CapabilityEvaluator.evaluate(seed(2), catalog, NOW).getFirst().status());
        var publicPortal = EligibilityEvaluator.evaluate(seed(1), catalog, NOW);
        assertEquals(DOES_NOT_MATCH, publicPortal.getFirst().status());
        assertTrue(publicPortal.get(1).contextChecks().stream().allMatch(c -> c.outcome() == PASS));
    }

    @Test
    void absentFactsAreUnknownAndDoNotBorrowSupportFromAnotherPlanOrRegion() throws Exception {
        var complete = fixture().options().getFirst();
        var missing = new Option("another-plan", complete.displayName(), "Basic", "Other region",
                complete.facts(), Compatibility.empty());
        var result = EligibilityEvaluator.evaluate(seed(0),
                new ProviderCatalog(3, "test", Kind.SYNTHETIC, List.of(complete, missing)), NOW).getFirst();
        assertEquals(NEEDS_INFORMATION, result.status());
        assertTrue(result.contextChecks().stream().allMatch(c -> c.reasonCode() == EVIDENCE_MISSING));
        assertTrue(result.contextChecks().stream().allMatch(c -> c.evidence() == null));
    }

    @Test
    void unusablePositiveAndNegativeEvidenceNeverProvesCompatibilityOrExclusion() throws Exception {
        for (var support : List.of(Support.SUPPORTED, Support.UNSUPPORTED)) {
            assertFactOutcome(fact(support, EvidenceStatus.UNREVIEWED, NOW), UNKNOWN, EVIDENCE_UNREVIEWED);
            assertFactOutcome(fact(support, EvidenceStatus.REVIEWED, NOW.plusNanos(1)), UNKNOWN, EVIDENCE_FROM_FUTURE);
            assertFactOutcome(fact(support, EvidenceStatus.REVIEWED, NOW.minus(EvidencePolicy.MAX_AGE).minusNanos(1)),
                    UNKNOWN, EVIDENCE_STALE);
        }
        assertFactOutcome(fact(Support.SUPPORTED, EvidenceStatus.REVIEWED, NOW.minus(EvidencePolicy.MAX_AGE)),
                PASS, CONTEXT_SUPPORTED);
        assertFactOutcome(fact(Support.UNSUPPORTED, EvidenceStatus.REVIEWED, NOW.minus(EvidencePolicy.MAX_AGE)),
                FAIL, CONTEXT_UNSUPPORTED);
        assertFactOutcome(fact(Support.UNKNOWN, EvidenceStatus.REVIEWED, NOW), UNKNOWN, CONTEXT_SUPPORT_UNKNOWN);
    }

    @Test
    void everyClientAndPopulationIsCheckedInStableOrder() throws Exception {
        var baseline = fixture().options().getFirst().compatibility();
        var facts = new Compatibility(baseline.applications(),
                Map.of(BROWSER, YES, NATIVE_MOBILE, NO, MACHINE_TO_MACHINE, YES),
                Map.of(EXTERNAL_CUSTOMERS, YES, PARTNERS, NO, INTERNAL_OPERATORS, YES),
                baseline.tenancy(), baseline.membership());
        var profile = withContext(seed(0),
                new ApplicationTopology(B2B_SAAS, new LinkedHashSet<>(List.of(NATIVE_MOBILE, MACHINE_TO_MACHINE, BROWSER))),
                new AudienceRequirements(new LinkedHashSet<>(List.of(PARTNERS, INTERNAL_OPERATORS, EXTERNAL_CUSTOMERS)),
                        MULTI_TENANT_ORGANIZATIONS, MULTIPLE_ORGANIZATIONS_PER_USER));
        var checks = TopologyEvaluator.evaluate(profile, facts, NOW);
        assertEquals(List.of("B2B_SAAS", "BROWSER", "MACHINE_TO_MACHINE", "NATIVE_MOBILE",
                        "EXTERNAL_CUSTOMERS", "INTERNAL_OPERATORS", "PARTNERS",
                        "MULTI_TENANT_ORGANIZATIONS", "MULTIPLE_ORGANIZATIONS_PER_USER"),
                checks.stream().map(EligibilityPreflight.ContextCheck::requestedValue).toList());
        assertEquals(2, checks.stream().filter(c -> c.outcome() == FAIL).count());
        var reordered = withContext(profile, new ApplicationTopology(B2B_SAAS, Set.of(BROWSER, MACHINE_TO_MACHINE, NATIVE_MOBILE)),
                new AudienceRequirements(Set.of(EXTERNAL_CUSTOMERS, INTERNAL_OPERATORS, PARTNERS),
                        MULTI_TENANT_ORGANIZATIONS, MULTIPLE_ORGANIZATIONS_PER_USER));
        assertEquals(checks, TopologyEvaluator.evaluate(reordered, facts, NOW));
        assertEquals(DOES_NOT_MATCH, evaluate(profile, facts).status());
    }

    @Test
    void eachTenancyAndMembershipSelectionRequiresItsOwnEvidence() throws Exception {
        var baseline = fixture().options().getFirst().compatibility();
        var tenancyNo = new Compatibility(baseline.applications(), baseline.clients(), baseline.populations(),
                Map.of(MULTI_TENANT_ORGANIZATIONS, NO), baseline.membership());
        var membershipNo = new Compatibility(baseline.applications(), baseline.clients(), baseline.populations(),
                baseline.tenancy(), Map.of(MULTIPLE_ORGANIZATIONS_PER_USER, NO));
        for (var facts : List.of(tenancyNo, membershipNo)) {
            var result = evaluate(seed(0), facts);
            assertEquals(DOES_NOT_MATCH, result.status());
            assertEquals(1, result.contextChecks().stream().filter(c -> c.outcome() == FAIL).count());
        }
        var profile = withContext(seed(0), seed(0).application(),
                new AudienceRequirements(Set.of(CITIZENS), NO_ORGANIZATION_BOUNDARY, NOT_APPLICABLE));
        var checks = TopologyEvaluator.evaluate(profile, fixture().options().get(1).compatibility(), NOW);
        assertEquals(PASS, checks.getLast().outcome());
        assertEquals("NOT_APPLICABLE", checks.getLast().requestedValue());
    }

    @Test
    void unknownAndUnclassifiedProfileContextCannotProduceAnAffirmativeMatch() throws Exception {
        var facts = fixture().options().getFirst().compatibility();
        var checks = TopologyEvaluator.evaluate(ApplicationIdentityProfile.unknown(), facts, NOW);
        assertEquals(5, checks.size());
        assertTrue(checks.stream().allMatch(c -> c.reasonCode() == PROFILE_CONTEXT_UNKNOWN));
        var other = withContext(seed(0), new ApplicationTopology(OTHER, Set.of(BROWSER)), seed(0).audience());
        assertEquals(NEEDS_INFORMATION, evaluate(other, facts).status());
        var emptyClients = withContext(seed(0), new ApplicationTopology(B2B_SAAS, Set.of()), seed(0).audience());
        assertEquals(NEEDS_INFORMATION, evaluate(emptyClients, facts).status());
        var emptyHumans = withContext(seed(0), seed(0).application(),
                new AudienceRequirements(Set.of(), MULTI_TENANT_ORGANIZATIONS, MULTIPLE_ORGANIZATIONS_PER_USER));
        assertEquals(NEEDS_INFORMATION, evaluate(emptyHumans, facts).status());
    }

    @Test
    void emptyHumanPopulationIsNotAppliedOnlyForMachineOnlyClients() throws Exception {
        var facts = fixture().options().getFirst().compatibility();
        var audience = new AudienceRequirements(Set.of(), MULTI_TENANT_ORGANIZATIONS, MULTIPLE_ORGANIZATIONS_PER_USER);
        var profile = withContext(seed(0), new ApplicationTopology(B2B_SAAS, Set.of(MACHINE_TO_MACHINE)), audience);
        var checks = TopologyEvaluator.evaluate(profile, facts, NOW);
        assertEquals(HUMAN_POPULATION_NOT_APPLICABLE, checks.get(2).reasonCode());
        assertEquals(NOT_APPLIED, checks.get(2).outcome());
        assertNull(checks.get(2).requestedValue());
        assertEquals(MATCHES_CHECKED_REQUIREMENTS, evaluate(profile, facts).status());
        var mixed = withContext(profile, new ApplicationTopology(B2B_SAAS, Set.of(BROWSER, MACHINE_TO_MACHINE)), audience);
        assertEquals(NEEDS_INFORMATION, evaluate(mixed, facts).status());
        var unknownAudience = withContext(profile, profile.application(), AudienceRequirements.unknown());
        assertEquals(NEEDS_INFORMATION, evaluate(unknownAudience, facts).status());
    }

    @Test
    void failuresInEitherFamilyTakePrecedenceWithoutHidingUnknownChecks() throws Exception {
        var profile = seed(0);
        var noScim = fixture().options().get(1);
        var result = EligibilityEvaluator.evaluate(profile,
                new ProviderCatalog(3, "test", Kind.SYNTHETIC, List.of(new Option(noScim.id(), noScim.displayName(),
                        noScim.plan(), noScim.region(), noScim.facts(), Compatibility.empty()))), NOW).getFirst();
        assertEquals(DOES_NOT_MATCH, result.status());
        assertTrue(result.contextChecks().stream().allMatch(c -> c.outcome() == UNKNOWN));
        var unknown = ApplicationIdentityProfile.unknown();
        var contextWithUnknownCapabilities = new ApplicationIdentityProfile(seed(2).application(), seed(2).audience(),
                unknown.protocols(), unknown.provisioning(), unknown.security(), unknown.operations());
        var incompatible = EligibilityEvaluator.evaluate(contextWithUnknownCapabilities, fixture(), NOW).getFirst();
        assertEquals(DOES_NOT_MATCH, incompatible.status());
        assertTrue(incompatible.capabilityChecks().stream().allMatch(c -> c.outcome() == UNKNOWN));
    }

    @Test
    void absenceOfCapabilityConstraintsDoesNotOverridePositiveContextChecks() throws Exception {
        var p = seed(0);
        var none = RequirementCriticality.NOT_REQUIRED;
        var profile = new ApplicationIdentityProfile(p.application(), p.audience(),
                new ProtocolRequirements(Map.of(ProtocolRequirements.FederationProtocol.OIDC, none,
                        ProtocolRequirements.FederationProtocol.SAML, none), none, none, none),
                new ProvisioningRequirements(none, none, none),
                new SecurityRequirements(none, none, none, none, SecurityRequirements.AssuranceLevel.UNKNOWN, Set.of()),
                p.operations());
        assertEquals(NEEDS_INFORMATION, CapabilityEvaluator.evaluate(profile, fixture(), NOW).getFirst().status());
        assertEquals(MATCHES_CHECKED_REQUIREMENTS, EligibilityEvaluator.evaluate(profile, fixture(), NOW).getFirst().status());
    }

    @Test
    void hostingAndOperationsRemainPreferencesOutsideThisPolicy() throws Exception {
        var profile = seed(0);
        for (var hosting : OperationalConstraints.HostingPreference.values()) {
            var changed = new ApplicationIdentityProfile(profile.application(), profile.audience(), profile.protocols(),
                    profile.provisioning(), profile.security(), new OperationalConstraints(hosting,
                    OperationalConstraints.DeploymentTarget.ON_PREMISES, OperationalConstraints.IdentityExpertise.LIMITED,
                    OperationalConstraints.BudgetSensitivity.HIGH));
            assertEquals(EligibilityEvaluator.evaluate(profile, fixture(), NOW),
                    EligibilityEvaluator.evaluate(changed, fixture(), NOW));
        }
        assertTrue(EligibilityEvaluator.DEFERRED_PATHS.contains("operations"));
    }

    @Test
    void optionOrderDoesNotChangeTheResultAndEvidenceMapsAreImmutable() throws Exception {
        var catalog = fixture();
        assertEquals(EligibilityEvaluator.evaluate(seed(0), catalog, NOW),
                EligibilityEvaluator.evaluate(seed(0),
                        new ProviderCatalog(3, catalog.catalogVersion(), catalog.kind(), catalog.options().reversed()), NOW));
        var applications = new EnumMap<ApplicationTopology.ApplicationType, CompatibilityFact>(ApplicationTopology.ApplicationType.class);
        applications.put(B2B_SAAS, YES);
        var copy = new Compatibility(applications, Map.of(), Map.of(), Map.of(), Map.of());
        applications.put(B2B_SAAS, NO);
        assertEquals(YES, copy.applications().get(B2B_SAAS));
        assertThrows(UnsupportedOperationException.class, () -> copy.applications().put(B2B_SAAS, NO));
    }

    @Test
    void catalogRejectsUnknownCategoriesAndIncompleteOrRealProvenance() {
        for (var application : List.of(OTHER, ApplicationTopology.ApplicationType.UNKNOWN)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Compatibility(Map.of(application, YES), Map.of(), Map.of(), Map.of(), Map.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new Compatibility(Map.of(), Map.of(), Map.of(),
                Map.of(AudienceRequirements.TenancyModel.UNKNOWN, YES), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Compatibility(Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(AudienceRequirements.MembershipModel.UNKNOWN, YES)));
        assertThrows(NullPointerException.class, () -> fact(null, EvidenceStatus.REVIEWED, NOW));
        assertThrows(NullPointerException.class, () -> fact(Support.SUPPORTED, null, NOW));
        assertThrows(NullPointerException.class, () -> fact(Support.SUPPORTED, EvidenceStatus.REVIEWED, null));
        assertThrows(NullPointerException.class, () -> new CompatibilityFact(Support.SUPPORTED, EvidenceStatus.REVIEWED, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new CompatibilityFact(Support.SUPPORTED, EvidenceStatus.REVIEWED,
                URI.create("https://vendor.example.com/facts"), NOW));
        assertThrows(IllegalArgumentException.class, () -> new CompatibilityFact(Support.SUPPORTED, EvidenceStatus.REVIEWED,
                URI.create("https://user:secret@example.invalid/facts"), NOW));
    }

    private static void assertFactOutcome(CompatibilityFact fact, CapabilityPreflight.Outcome outcome,
            EligibilityPreflight.Reason reason) throws Exception {
        var checks = TopologyEvaluator.evaluate(seed(0),
                new Compatibility(Map.of(B2B_SAAS, fact), Map.of(), Map.of(), Map.of(), Map.of()), NOW);
        assertEquals(outcome, checks.getFirst().outcome());
        assertEquals(reason, checks.getFirst().reasonCode());
        assertEquals(fact, checks.getFirst().evidence());
    }

    private static CompatibilityFact fact(Support support, EvidenceStatus status, Instant at) {
        return new CompatibilityFact(support, status, SOURCE, at);
    }

    private static EligibilityPreflight.Candidate evaluate(ApplicationIdentityProfile profile, Compatibility compatibility) throws Exception {
        var option = fixture().options().getFirst();
        return EligibilityEvaluator.evaluate(profile, new ProviderCatalog(3, "test", Kind.SYNTHETIC,
                List.of(new Option(option.id(), option.displayName(), option.plan(), option.region(), option.facts(), compatibility))), NOW).getFirst();
    }

    private static ApplicationIdentityProfile withContext(ApplicationIdentityProfile p, ApplicationTopology app, AudienceRequirements audience) {
        return new ApplicationIdentityProfile(app, audience, p.protocols(), p.provisioning(), p.security(), p.operations());
    }

    private static ProviderCatalog fixture() throws Exception {
        try (var input = new ClassPathResource("catalog/synthetic.v3.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(input, ProviderCatalog.class);
        }
    }

    private static ApplicationIdentityProfile seed(int index) throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            return mapper.treeToValue(mapper.readTree(input).get(index).get("profile"), ApplicationIdentityProfile.class);
        }
    }
}
