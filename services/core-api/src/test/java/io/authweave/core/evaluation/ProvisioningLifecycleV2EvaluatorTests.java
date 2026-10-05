package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.authweave.core.assessment.domain.profile.ProvisioningRequirements;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Declaration;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Outcome;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Status;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.*;
import static io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.*;
import static org.junit.jupiter.api.Assertions.*;

class ProvisioningLifecycleV2EvaluatorTests {
    static Stream<Arguments> designs() { return Stream.of(PatternId.values()).flatMap(p -> Stream.of(GroupStrategy.values()).map(g -> Arguments.of(p, g))); }
    private Map<ConditionId, Declaration> declared(PatternId pattern, GroupStrategy groups) {
        var values = new HashMap<ConditionId, Declaration>(); conditions(pattern, groups).forEach(id -> values.put(id, Declaration.SATISFIED)); return values;
    }
    @Test void inventoriesAreDistinctCompleteAndOffboardingIsNoLongerOneGenericDeclaration() {
        assertEquals(List.of(GroupStrategy.values()), GROUP_STRATEGIES.stream().map(GroupDefinition::groupStrategy).toList());
        assertEquals(List.of(ConditionId.values()), CONDITION_DEFINITIONS.stream().map(ConditionDefinition::conditionId).toList());
        assertEquals(16, CONDITION_DEFINITIONS.size());
        assertEquals(15, conditions(PatternId.SCIM_AND_JIT, GroupStrategy.SCIM_GROUPS).size());
        assertTrue(COMMON.containsAll(List.of(ConditionId.ACCOUNT_DISABLE_AND_LOGIN_BLOCK, ConditionId.APPLICATION_SESSION_INVALIDATION,
                ConditionId.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY)));
        assertFalse(Stream.of(ConditionId.values()).anyMatch(id -> id.name().equals("OFFBOARDING_AND_ACCESS_REVOCATION")));
        assertEquals(List.of("providerOperationsAndEntitlements", "observedProvisioningDelivery", "groupMembershipAndAuthorization",
                "sessionAndTokenRevocation", "reconciliationAndFailureRecovery"), ProvisioningLifecycleEvaluator.DEFERRED_BOUNDARIES);
    }
    @ParameterizedTest @MethodSource("designs")
    void all1500CriticalityAndDesignCombinationsPreserveHardConstraints(PatternId pattern, GroupStrategy groups) {
        for (var scim : RequirementCriticality.values()) for (var jit : RequirementCriticality.values()) for (var group : RequirementCriticality.values()) {
            var result = evaluate(new ProvisioningRequirements(scim, jit, group), pattern, groups, declared(pattern, groups));
            boolean scimPlanned = pattern != PatternId.JIT_LOGIN, jitPlanned = pattern != PatternId.SCIM_PUSH, groupPlanned = groups != GroupStrategy.NONE;
            boolean failure = scim == REQUIRED && !scimPlanned || scim == FORBIDDEN && scimPlanned
                    || jit == REQUIRED && !jitPlanned || jit == FORBIDDEN && jitPlanned
                    || groups != GroupStrategy.UNKNOWN && (group == REQUIRED && !groupPlanned || group == FORBIDDEN && groupPlanned)
                    || groups == GroupStrategy.SCIM_GROUPS && !scimPlanned;
            boolean unknown = scim == UNKNOWN || jit == UNKNOWN || group == UNKNOWN || groups == GroupStrategy.UNKNOWN;
            assertEquals(failure ? Status.CONDITIONALLY_DOES_NOT_MATCH : unknown ? Status.NEEDS_INFORMATION : Status.CONDITIONALLY_MATCHES, result.status());
            var groupCheck = result.requirementChecks().getLast();
            assertEquals(group, groupCheck.criticality());
            assertEquals(group == UNKNOWN ? Reason.REQUIREMENT_UNKNOWN : group == PREFERRED ? Reason.PREFERENCE_NOT_SCORED : group == NOT_REQUIRED ? Reason.NO_REQUIREMENT
                    : groups == GroupStrategy.UNKNOWN ? Reason.GROUP_STRATEGY_UNKNOWN
                    : group == REQUIRED ? groupPlanned ? Reason.REQUIRED_MECHANISM_PLANNED : Reason.REQUIRED_MECHANISM_ABSENT
                    : groupPlanned ? Reason.FORBIDDEN_MECHANISM_PLANNED : Reason.FORBIDDEN_MECHANISM_ABSENT, groupCheck.reasonCode());
            assertEquals(ProvisioningLifecycleEvaluator.CHECKED_PATHS, result.requirementChecks().stream().map(RequirementCheck::profilePath).toList());
        }
    }
    @ParameterizedTest @MethodSource("designs")
    void eachConditionKeepsOmittedUnknownAndUnmetOutcomesVisible(PatternId pattern, GroupStrategy groups) {
        var requirements = new ProvisioningRequirements(NOT_REQUIRED, NOT_REQUIRED, NOT_REQUIRED);
        var ids = conditions(pattern, groups);
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertEquals(ids, evaluate(requirements, pattern, groups, Map.of()).conditionChecks().stream().map(ConditionCheck::conditionId).toList());
        for (var id : ids) {
            var values = declared(pattern, groups); values.remove(id);
            var missing = evaluate(requirements, pattern, groups, values);
            assertEquals(1, missing.conditionChecks().stream().filter(c -> c.outcome() == Outcome.UNKNOWN).count());
            values.put(id, Declaration.UNKNOWN); assertEquals(missing.conditionChecks(), evaluate(requirements, pattern, groups, values).conditionChecks());
            values.put(id, Declaration.NOT_SATISFIED); assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, evaluate(requirements, pattern, groups, values).status());
        }
    }
    @ParameterizedTest @MethodSource("designs")
    void foreignConditionsAndNullsCannotBecomeAssertions(PatternId pattern, GroupStrategy groups) {
        var requirements = new ProvisioningRequirements(NOT_REQUIRED, NOT_REQUIRED, NOT_REQUIRED);
        var values = declared(pattern, groups); var result = evaluate(requirements, pattern, groups, values); values.clear();
        assertEquals(conditions(pattern, groups).size(), result.declarations().size());
        assertThrows(UnsupportedOperationException.class, () -> result.declarations().clear());
        for (var id : ConditionId.values()) if (!conditions(pattern, groups).contains(id)) {
            assertThrows(IllegalArgumentException.class, () -> evaluate(requirements, pattern, groups, Map.of(id, Declaration.SATISFIED)));
            assertThrows(IllegalArgumentException.class, () -> new ProvisioningLifecycleV2Request(0L, pattern, groups, Map.of(id, Declaration.SATISFIED)));
        }
        values.put(conditions(pattern, groups).getFirst(), null);
        assertThrows(NullPointerException.class, () -> evaluate(requirements, pattern, groups, values));
    }
    @Test void groupTransportCannotSubstituteScimOrHideUnknownOffboarding() {
        var required = new ProvisioningRequirements(REQUIRED, NOT_REQUIRED, REQUIRED);
        var bridge = evaluate(required, PatternId.JIT_LOGIN, GroupStrategy.APPLICATION_BRIDGE, Map.of());
        assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, bridge.status());
        assertEquals(Reason.REQUIRED_MECHANISM_ABSENT, bridge.requirementChecks().getFirst().reasonCode());
        assertEquals(Outcome.CONDITIONALLY_SATISFIED, bridge.requirementChecks().getLast().outcome());
        assertTrue(bridge.conditionChecks().stream().allMatch(c -> c.outcome() == Outcome.UNKNOWN));
        var scim = evaluate(required, PatternId.SCIM_PUSH, GroupStrategy.SCIM_GROUPS, declared(PatternId.SCIM_PUSH, GroupStrategy.SCIM_GROUPS));
        assertEquals(Status.CONDITIONALLY_MATCHES, scim.status());
        var invalid = evaluate(new ProvisioningRequirements(NOT_REQUIRED, NOT_REQUIRED, NOT_REQUIRED), PatternId.JIT_LOGIN, GroupStrategy.SCIM_GROUPS,
                declared(PatternId.JIT_LOGIN, GroupStrategy.SCIM_GROUPS));
        assertEquals(Reason.SCIM_GROUPS_REQUIRE_SCIM_PATTERN, invalid.designChecks().getFirst().reasonCode());
        assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, invalid.status());
    }
    @Test void evenAllMetDesignsNeverVerifyGroupsRevocationOrPublication() {
        var analysis = evaluate(new ProvisioningRequirements(REQUIRED, REQUIRED, REQUIRED), PatternId.SCIM_AND_JIT, GroupStrategy.SCIM_GROUPS,
                declared(PatternId.SCIM_AND_JIT, GroupStrategy.SCIM_GROUPS));
        var result = new ProvisioningLifecycleV2Preview(UUID.randomUUID(), UUID.randomUUID(), 0, Instant.EPOCH, analysis);
        assertEquals(Status.CONDITIONALLY_MATCHES, analysis.status());
        assertFalse(result.configurationVerified()); assertFalse(result.providerCompatibilityVerified()); assertFalse(result.lifecycleVerified());
        assertFalse(result.groupSynchronizationVerified()); assertFalse(result.accessRevocationVerified()); assertFalse(result.writesPerformed());
        assertFalse(result.publicationReady()); assertFalse(result.recommendationReady());
        assertEquals("provisioning-lifecycle-design-2", result.policyVersion());
        assertThrows(IllegalArgumentException.class, () -> new ProvisioningLifecycleV2Preview(result.workspaceId(), result.assessmentId(), -1, Instant.EPOCH, analysis));
        assertThrows(IllegalArgumentException.class, () -> new ProvisioningLifecycleV2Preview(result.workspaceId(), result.assessmentId(), 9007199254740992L, Instant.EPOCH, analysis));
        assertThrows(NullPointerException.class, () -> new ProvisioningLifecycleV2Request(0L, PatternId.SCIM_PUSH, null, Map.of()));
    }
}
