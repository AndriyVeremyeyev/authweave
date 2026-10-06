package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.*;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.*;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.*;
import static io.authweave.core.evaluation.AssuranceCompliancePlanningPreflight.*;
import static org.junit.jupiter.api.Assertions.*;

class AssuranceCompliancePlanningEvaluatorTests {
    private static final UUID WORKSPACE = UUID.fromString("60000000-0000-4000-8000-000000000001"), ASSESSMENT = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private ApplicationIdentityProfile profile(Set<ClientType> clients, Set<UserPopulation> populations, AssuranceLevel assurance,
            RequirementCriticality mfa, AuthenticationControls controls, ComplianceScopeStatus scope, Set<ComplianceTarget> targets) {
        var p = ApplicationIdentityProfile.unknown();
        return new ApplicationIdentityProfile(new ApplicationTopology(p.application().type(), clients),
                new AudienceRequirements(populations, p.audience().tenancy(), p.audience().membership()), p.protocols(), p.provisioning(),
                new SecurityRequirements(mfa, p.security().browserTokenExposureMinimization(), p.security().auditability(), p.security().dataResidency(), assurance,
                        targets, p.security().dataResidencyDetails(), controls, scope, p.security().auditabilityRequirements()), p.operations());
    }
    private AssuranceCompliancePlanningPreflight evaluate(ApplicationIdentityProfile p) { return AssuranceCompliancePlanningEvaluator.evaluate(WORKSPACE, ASSESSMENT, 7, p, AT); }
    @Test void unknownAndGenericLabelsNeverMapToFormalAssuranceOrCompliance() {
        for (var level : AssuranceLevel.values()) {
            var result = evaluate(profile(Set.of(), Set.of(), level, RequirementCriticality.UNKNOWN, AuthenticationControls.unknown(), ComplianceScopeStatus.UNKNOWN, Set.of()));
            assertEquals(level == AssuranceLevel.UNKNOWN ? Reason.EXPECTATION_UNRECORDED : Reason.LABEL_NEEDS_DEFINITION, result.assuranceItems().getFirst().reasonCode());
            assertEquals(ItemStatus.INPUT_CLARIFICATION_NEEDED, result.assuranceItems().getFirst().status());
            assertEquals(HumanScope.SCOPE_UNRESOLVED, result.humanScope()); assertEquals(7, result.assuranceItems().size());
            assertEquals("NEEDS_INFORMATION", result.status()); assertFalse(result.assuranceVerified()); assertFalse(result.complianceVerified());
            assertFalse(result.legalApplicabilityDetermined()); assertFalse(result.providerEligibilityEvaluated()); assertFalse(result.configurationVerified());
            assertFalse(result.recommendationReady()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
        }
    }
    @Test void everyClientAndPopulationSubsetSeparatesHumanAndWorkloadScopeWithoutWildcards() {
        var clients = ClientType.values(); var populations = UserPopulation.values();
        for (int c = 0; c < 1 << clients.length; c++) for (int u = 0; u < 1 << populations.length; u++) {
            var cs = new java.util.HashSet<ClientType>(); var ps = new java.util.HashSet<UserPopulation>();
            for (int i = 0; i < clients.length; i++) if ((c & 1 << i) != 0) cs.add(clients[i]);
            for (int i = 0; i < populations.length; i++) if ((u & 1 << i) != 0) ps.add(populations[i]);
            var result = evaluate(profile(cs, ps, AssuranceLevel.HIGH, RequirementCriticality.REQUIRED, AuthenticationControls.unknown(), ComplianceScopeStatus.NONE_IDENTIFIED, Set.of()));
            boolean machineOnly = cs.equals(Set.of(ClientType.MACHINE_TO_MACHINE));
            assertEquals(machineOnly ? HumanScope.MACHINE_ONLY : cs.isEmpty() || ps.isEmpty() ? HumanScope.SCOPE_UNRESOLVED : HumanScope.HUMAN_SCOPE_RECORDED, result.humanScope());
            for (int index : List.of(1, 2, 3, 4)) assertEquals(machineOnly, result.assuranceItems().get(index).status() == ItemStatus.NOT_APPLIED);
            assertEquals(cs.contains(ClientType.MACHINE_TO_MACHINE) ? ItemStatus.EVIDENCE_NEEDED : cs.isEmpty() ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.NOT_APPLIED,
                    result.assuranceItems().get(5).status());
            assertEquals(ItemStatus.EVIDENCE_NEEDED, result.assuranceItems().get(6).status());
        }
    }
    @Test void all625ControlCombinationsAreIndependentOfAssuranceLabelsAndNeverBecomeVerification() {
        for (var mfa : RequirementCriticality.values()) for (var phishing : RequirementCriticality.values())
            for (var keys : RequirementCriticality.values()) for (var step : RequirementCriticality.values()) {
                var controls = new AuthenticationControls(phishing, keys, step);
                boolean unclear = List.of(mfa, phishing, keys, step).stream().anyMatch(c -> c == RequirementCriticality.UNKNOWN || c == RequirementCriticality.FORBIDDEN);
                for (var level : AssuranceLevel.values()) {
                    var result = evaluate(profile(Set.of(ClientType.BROWSER), Set.of(UserPopulation.EMPLOYEES), level, mfa, controls, ComplianceScopeStatus.UNKNOWN, Set.of()));
                    assertEquals(unclear ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED, result.assuranceItems().get(2).status());
                    assertEquals(controls.phishingResistance(), result.inputs().controls().phishingResistance()); assertEquals(mfa, result.inputs().controls().multiFactorAuthentication());
                    assertFalse(result.assuranceVerified());
                }
            }
    }
    @Test void allTargetSubsetsAndScopeStatesPreserveLabelsWithoutInferringApplicability() {
        var targets = ComplianceTarget.values();
        for (int mask = 0; mask < 1 << targets.length; mask++) for (var scope : ComplianceScopeStatus.values()) {
            var selected = new java.util.HashSet<ComplianceTarget>();
            for (int i = 0; i < targets.length; i++) if ((mask & 1 << i) != 0) selected.add(targets[i]);
            var p = profile(Set.of(), Set.of(), AssuranceLevel.BASELINE, RequirementCriticality.NOT_REQUIRED, AuthenticationControls.unknown(), scope, selected);
            var result = evaluate(p); assertEquals(ComplianceScopeEvaluator.evaluate(p.security()), result.complianceScopeCheck());
            assertEquals(selected.size(), result.complianceItems().size());
            for (var item : result.complianceItems()) assertEquals(scope != ComplianceScopeStatus.TARGETS_IDENTIFIED || item.target() == ComplianceTarget.OTHER
                    ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED, item.status());
            if (scope == ComplianceScopeStatus.NONE_IDENTIFIED && !selected.isEmpty() || scope == ComplianceScopeStatus.TARGETS_IDENTIFIED && selected.isEmpty())
                assertEquals(ComplianceScopeCheck.Reason.COMPLIANCE_SCOPE_INCONSISTENT, result.complianceScopeCheck().reasonCode());
            assertFalse(result.complianceVerified()); assertEquals("NEEDS_INFORMATION", result.status());
        }
    }
    @Test void inventoryIsImmutableAndRejectsMissingReorderedOrSubstitutedRowsAndUnsafeVersions() {
        var p = ApplicationIdentityProfile.unknown(); var r = evaluate(p);
        assertThrows(UnsupportedOperationException.class, () -> r.assuranceItems().clear()); assertThrows(UnsupportedOperationException.class, () -> r.inputs().clients().clear());
        for (var rows : List.of(List.<AssuranceItem>of(), r.assuranceItems().reversed(), java.util.Collections.nCopies(7, r.assuranceItems().getFirst())))
            assertThrows(IllegalArgumentException.class, () -> new AssuranceCompliancePlanningPreflight(WORKSPACE, ASSESSMENT, 7, AT, r.inputs(), r.complianceScopeCheck(), rows, r.complianceItems()));
        for (long version : List.of(-1L, Long.MAX_VALUE)) assertThrows(IllegalArgumentException.class, () -> AssuranceCompliancePlanningEvaluator.evaluate(WORKSPACE, ASSESSMENT, version, p, AT));
        assertThrows(IllegalArgumentException.class, () -> new Inputs(List.of(ClientType.BROWSER, ClientType.BROWSER), List.of(), AssuranceLevel.UNKNOWN, r.inputs().controls(), ComplianceScopeStatus.UNKNOWN, List.of()));
        var fake = new ComplianceScopeCheck("security.complianceScopeStatus", ComplianceScopeStatus.UNKNOWN, List.of(), CapabilityPreflight.Outcome.PASS, ComplianceScopeCheck.Reason.COMPLIANCE_SCOPE_UNKNOWN, "Verified", true);
        assertThrows(IllegalArgumentException.class, () -> new AssuranceCompliancePlanningPreflight(WORKSPACE, ASSESSMENT, 7, AT, r.inputs(), fake, r.assuranceItems(), r.complianceItems()));
    }
}
