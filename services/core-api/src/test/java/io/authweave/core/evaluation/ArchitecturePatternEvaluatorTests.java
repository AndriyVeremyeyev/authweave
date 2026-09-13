package io.authweave.core.evaluation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;

import static io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchitecturePatternEvaluatorTests {
    @ParameterizedTest
    @CsvSource({
            "REQUIRED, MATCHES_CHECKED_REQUIREMENTS, NEEDS_INFORMATION, ACCEPTABLE_EXPOSURE_UNDEFINED",
            "PREFERRED, MATCHES_CHECKED_REQUIREMENTS, MATCHES_CHECKED_REQUIREMENTS, PREFERENCE_NOT_SCORED",
            "NOT_REQUIRED, MATCHES_CHECKED_REQUIREMENTS, MATCHES_CHECKED_REQUIREMENTS, NO_REQUIREMENT",
            "UNKNOWN, NEEDS_INFORMATION, NEEDS_INFORMATION, REQUIREMENT_UNKNOWN",
            "FORBIDDEN, NEEDS_INFORMATION, NEEDS_INFORMATION, MINIMIZATION_PROHIBITION_UNDEFINED"
    })
    void minimizationNeverSilentlyBecomesATokenBanOrAutomaticBffChoice(
            RequirementCriticality criticality, Status serverStatus, Status spaStatus, Reason spaReason) {
        var results = ArchitecturePatternEvaluator.evaluate(profile(Set.of(BROWSER), criticality));
        for (var id : List.of(BFF_SESSION, SERVER_SIDE_SESSION)) assertEquals(serverStatus, find(results, id).status());
        var spa = find(results, SPA_CODE_PKCE);
        assertEquals(spaStatus, spa.status());
        assertEquals(spaReason, spa.checks().getLast().reasonCode());
        assertEquals(NOT_APPLICABLE, find(results, NATIVE_CODE_PKCE).status());
        assertEquals(NOT_APPLICABLE, find(results, M2M_CLIENT_CREDENTIALS).status());
    }

    @Test
    void incompleteClientContextCannotProduceAnyAffirmativeMatch() {
        var result = ArchitecturePatternEvaluator.evaluate(ApplicationIdentityProfile.unknown());
        assertTrue(result.stream().allMatch(p -> p.status() == NEEDS_INFORMATION));
        assertTrue(result.stream().flatMap(p -> p.checks().stream())
                .allMatch(c -> c.reasonCode() == Reason.CLIENT_CONTEXT_UNKNOWN));
    }

    @Test
    void browserCriteriaDoNotExcludeNativeOrWorkloadPatterns() {
        for (var criticality : RequirementCriticality.values()) {
            for (var client : List.of(NATIVE_MOBILE, MACHINE_TO_MACHINE)) {
                var result = ArchitecturePatternEvaluator.evaluate(profile(Set.of(client), criticality));
                var selected = result.stream().filter(p -> p.clientType() == client).findFirst().orElseThrow();
                assertEquals(MATCHES_CHECKED_REQUIREMENTS, selected.status());
                assertEquals(Reason.BROWSER_CRITERION_NOT_APPLICABLE, selected.checks().getLast().reasonCode());
                assertEquals(Outcome.NOT_APPLIED, selected.checks().getLast().outcome());
                assertTrue(result.stream().filter(p -> p.clientType() != client).allMatch(p -> p.status() == NOT_APPLICABLE));
            }
        }
    }

    @Test
    void mixedClientsAreAssessedIndependentlyAndOrderDoesNotChangeComparison() {
        var first = ArchitecturePatternEvaluator.evaluate(profile(new LinkedHashSet<>(List.of(MACHINE_TO_MACHINE, BROWSER, NATIVE_MOBILE)),
                RequirementCriticality.UNKNOWN));
        var second = ArchitecturePatternEvaluator.evaluate(profile(Set.of(NATIVE_MOBILE, BROWSER, MACHINE_TO_MACHINE),
                RequirementCriticality.UNKNOWN));
        assertEquals(first, second);
        assertEquals(NEEDS_INFORMATION, find(first, BFF_SESSION).status());
        assertEquals(NEEDS_INFORMATION, find(first, SPA_CODE_PKCE).status());
        assertEquals(MATCHES_CHECKED_REQUIREMENTS, find(first, NATIVE_CODE_PKCE).status());
        assertEquals(MATCHES_CHECKED_REQUIREMENTS, find(first, M2M_CLIENT_CREDENTIALS).status());
        assertEquals(5, first.stream().map(Pattern::patternId).distinct().count());
    }

    @Test
    void partialResultsExposeUnverifiedPrerequisitesAndNoAssuranceOrOperationalInference() {
        var p = profile(Set.of(BROWSER), RequirementCriticality.PREFERRED);
        var changed = new ApplicationIdentityProfile(
                new ApplicationTopology(ApplicationTopology.ApplicationType.PUBLIC_SECTOR_PORTAL, p.application().clients()),
                new AudienceRequirements(Set.of(AudienceRequirements.UserPopulation.CITIZENS),
                        AudienceRequirements.TenancyModel.NO_ORGANIZATION_BOUNDARY, AudienceRequirements.MembershipModel.NOT_APPLICABLE),
                new ProtocolRequirements(java.util.Map.of(), RequirementCriticality.FORBIDDEN,
                        RequirementCriticality.FORBIDDEN, RequirementCriticality.FORBIDDEN),
                new ProvisioningRequirements(RequirementCriticality.REQUIRED, RequirementCriticality.REQUIRED, RequirementCriticality.REQUIRED),
                new SecurityRequirements(RequirementCriticality.REQUIRED, p.security().browserTokenExposureMinimization(),
                        RequirementCriticality.REQUIRED, RequirementCriticality.REQUIRED, SecurityRequirements.AssuranceLevel.HIGH,
                        Set.of(SecurityRequirements.ComplianceTarget.GDPR)),
                new OperationalConstraints(OperationalConstraints.HostingPreference.MANAGED,
                        OperationalConstraints.DeploymentTarget.AZURE, OperationalConstraints.IdentityExpertise.LIMITED,
                        OperationalConstraints.BudgetSensitivity.HIGH));
        var result = ArchitecturePatternEvaluator.evaluate(p);
        assertEquals(result, ArchitecturePatternEvaluator.evaluate(changed));
        assertTrue(ArchitecturePatternEvaluator.DEFERRED_PATHS.contains("protocols"));
        assertTrue(ArchitecturePatternEvaluator.DEFERRED_PATHS.contains("operations"));
        for (var pattern : result) {
            assertFalse(pattern.advantages().isEmpty());
            assertFalse(pattern.tradeoffs().isEmpty());
            assertFalse(pattern.prerequisites().isEmpty());
            assertTrue(pattern.references().stream().allMatch(uri -> "https".equals(uri.getScheme())
                    && Set.of("www.ietf.org", "www.rfc-editor.org").contains(uri.getHost())));
            assertThrows(UnsupportedOperationException.class, () -> pattern.prerequisites().add("changed"));
        }
        assertTrue(find(result, M2M_CLIENT_CREDENTIALS).prerequisites().stream().anyMatch(s -> s.contains("confidential client")));
        assertTrue(find(result, NATIVE_CODE_PKCE).prerequisites().stream().anyMatch(s -> s.contains("external user-agent")));
    }

    private static Pattern find(List<Pattern> patterns, PatternId id) {
        return patterns.stream().filter(p -> p.patternId() == id).findFirst().orElseThrow();
    }

    private static ApplicationIdentityProfile profile(Set<ClientType> clients, RequirementCriticality minimization) {
        var p = ApplicationIdentityProfile.unknown();
        return new ApplicationIdentityProfile(new ApplicationTopology(ApplicationTopology.ApplicationType.B2B_SAAS, clients),
                p.audience(), p.protocols(), p.provisioning(),
                new SecurityRequirements(RequirementCriticality.UNKNOWN, minimization, RequirementCriticality.UNKNOWN,
                        RequirementCriticality.UNKNOWN, SecurityRequirements.AssuranceLevel.UNKNOWN, Set.of()), p.operations());
    }
}
