package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import io.authweave.core.assessment.domain.*;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;
import io.authweave.core.catalog.ProviderCatalog;

import static io.authweave.core.assessment.domain.profile.ComplianceScopeStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ComplianceScopeEvaluatorTests {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @ParameterizedTest
    @CsvSource({"UNKNOWN,false,true,UNKNOWN,COMPLIANCE_SCOPE_UNKNOWN",
            "UNKNOWN,true,true,UNKNOWN,COMPLIANCE_SCOPE_UNKNOWN",
            "NONE_IDENTIFIED,false,true,NOT_APPLIED,NO_COMPLIANCE_TARGETS_IDENTIFIED",
            "NONE_IDENTIFIED,true,false,UNKNOWN,COMPLIANCE_SCOPE_INCONSISTENT",
            "TARGETS_IDENTIFIED,false,false,UNKNOWN,COMPLIANCE_SCOPE_INCONSISTENT",
            "TARGETS_IDENTIFIED,true,true,UNKNOWN,COMPLIANCE_TARGETS_NOT_EVALUATED"})
    void distinguishesScopeFromTargetsAndNeverClaimsCompliance(ComplianceScopeStatus status, boolean targets,
            boolean valid, String outcome, String reason) {
        var profile = withScope(ApplicationIdentityProfile.unknown(), status, targets ? Set.of(ComplianceTarget.SOC_2) : Set.of());
        var validation = ApplicationIdentityProfileValidator.validate(profile);
        assertEquals(valid, validation.canSave());
        if (!valid) {
            var issue = validation.contradictions().getFirst();
            assertEquals("security.complianceTargets", issue.path());
            assertEquals(status == NONE_IDENTIFIED ? "compliance_scope_none_has_targets" : "compliance_scope_targets_missing", issue.code());
            var draft = Assessment.createDraft(new AssessmentId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()));
            assertThrows(InvalidApplicationIdentityProfileException.class, () -> draft.updateProfile(profile));
            assertEquals(ApplicationIdentityProfile.unknown(), draft.profile());
        }
        var check = ComplianceScopeEvaluator.evaluate(profile.security());
        assertEquals(status, check.scopeStatus());
        assertEquals(targets ? List.of(ComplianceTarget.SOC_2) : List.of(), check.recordedTargets());
        assertEquals(outcome, check.outcome().name());
        assertEquals(reason, check.reasonCode().name());
        assertFalse(check.verificationPerformed());
    }

    @ParameterizedTest
    @EnumSource(ComplianceTarget.class)
    void noFrameworkLabelBecomesProviderEvidenceOrACompliancePass(ComplianceTarget target) {
        for (var status : List.of(UNKNOWN, TARGETS_IDENTIFIED)) {
            var check = ComplianceScopeEvaluator.evaluate(withScope(ApplicationIdentityProfile.unknown(), status, Set.of(target)).security());
            assertEquals("UNKNOWN", check.outcome().name());
            assertEquals(List.of(target), check.recordedTargets());
            assertFalse(check.verificationPerformed());
        }
    }

    @Test
    void listsAreImmutableSortedAndNotAffectedByTopologyOrRegionLabels() {
        var targets = Set.of(ComplianceTarget.SOC_2, ComplianceTarget.GDPR, ComplianceTarget.OTHER);
        var base = withScope(ApplicationIdentityProfile.unknown(), TARGETS_IDENTIFIED, targets);
        var check = ComplianceScopeEvaluator.evaluate(base.security());
        assertEquals(List.of(ComplianceTarget.GDPR, ComplianceTarget.OTHER, ComplianceTarget.SOC_2), check.recordedTargets());
        assertThrows(UnsupportedOperationException.class, () -> check.recordedTargets().clear());
        assertEquals(check, ComplianceScopeEvaluator.evaluate(withScope(ApplicationIdentityProfile.unknown(), TARGETS_IDENTIFIED,
                Set.of(ComplianceTarget.OTHER, ComplianceTarget.GDPR, ComplianceTarget.SOC_2)).security()));
        // Scope evaluation accepts only security inputs: it cannot infer obligations from client type or geography.
        assertEquals("UNKNOWN", ComplianceScopeEvaluator.evaluate(SecurityRequirements.unknown()).outcome().name());
    }

    @ParameterizedTest
    @EnumSource(ComplianceScopeStatus.class)
    void sharedScopeUnknownGatesMatchesButNeverErasesAnExistingFailure(ComplianceScopeStatus scope) throws Exception {
        ProviderCatalog catalog;
        ApplicationIdentityProfile seed;
        var mapper = JsonMapper.builder().build();
        try (var input = new ClassPathResource("catalog/synthetic.v4.json").getInputStream()) {
            catalog = mapper.readValue(input, ProviderCatalog.class);
        }
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            seed = mapper.treeToValue(mapper.readTree(input).get(0).get("profile"), ApplicationIdentityProfile.class);
        }
        var s = seed.security();
        var profile = new ApplicationIdentityProfile(seed.application(), seed.audience(), seed.protocols(), seed.provisioning(),
                new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), s.auditability(),
                RequirementCriticality.NOT_REQUIRED, s.assurance(), scope == TARGETS_IDENTIFIED ? Set.of(ComplianceTarget.GDPR) : Set.of(),
                s.dataResidencyDetails(), new AuthenticationControls(RequirementCriticality.NOT_REQUIRED,
                        RequirementCriticality.NOT_REQUIRED, RequirementCriticality.NOT_REQUIRED), scope), seed.operations());
        var before = EligibilityEvaluator.evaluateWithAuthenticationControls(profile, catalog, NOW);
        assertEquals("MATCHES_CHECKED_REQUIREMENTS", before.getFirst().status().name());
        var after = EligibilityEvaluator.evaluateWithComplianceScope(profile, catalog, NOW);
        assertEquals(scope == NONE_IDENTIFIED ? "MATCHES_CHECKED_REQUIREMENTS" : "NEEDS_INFORMATION", after.getFirst().status().name());
        assertEquals("DOES_NOT_MATCH", after.get(1).status().name());
        assertEquals("NEEDS_INFORMATION", after.getLast().status().name());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).capabilityChecks(), after.get(i).capabilityChecks());
            assertEquals(before.get(i).contextChecks(), after.get(i).contextChecks());
            assertEquals(before.get(i).residencyChecks(), after.get(i).residencyChecks());
            assertEquals(before.get(i).authenticationControlChecks(), after.get(i).authenticationControlChecks());
        }
        assertEquals(after, EligibilityEvaluator.evaluateWithComplianceScope(profile,
                new ProviderCatalog(4, catalog.catalogVersion(), catalog.kind(), catalog.options().reversed()), NOW));
        assertTrue(EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS.contains("security.complianceTargets"));
    }

    private static ApplicationIdentityProfile withScope(ApplicationIdentityProfile base, ComplianceScopeStatus scope, Set<ComplianceTarget> targets) {
        var s = base.security();
        return new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), s.auditability(),
                s.dataResidency(), s.assurance(), targets, s.dataResidencyDetails(), s.authenticationControls(), scope), base.operations());
    }
}
