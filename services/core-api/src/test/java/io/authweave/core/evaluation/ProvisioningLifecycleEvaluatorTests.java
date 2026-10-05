package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import io.authweave.core.assessment.domain.profile.ProvisioningRequirements;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import static io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.*;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.*;
import static org.junit.jupiter.api.Assertions.*;

class ProvisioningLifecycleEvaluatorTests {
    private ProvisioningRequirements scimRequired() { return new ProvisioningRequirements(REQUIRED, NOT_REQUIRED, NOT_REQUIRED); }
    private Map<ConditionId, Declaration> declarations(PatternId pattern, Declaration value) {
        var values = new HashMap<ConditionId, Declaration>(); definition(pattern).conditions().forEach(id -> values.put(id, value)); return values;
    }
    @Test void definitionsAreCompleteScopedAndSeparateMechanismsFromObservedBehavior() {
        assertEquals(3, DEFINITIONS.size()); assertEquals(List.of(PatternId.values()), DEFINITIONS.stream().map(Definition::patternId).toList());
        assertEquals(List.of(ConditionId.values()), CONDITION_DEFINITIONS.stream().map(ConditionDefinition::conditionId).toList());
        assertEquals(8, DEFINITIONS.stream().flatMap(d -> d.conditions().stream()).distinct().count());
        for (var d : DEFINITIONS) { assertEquals(d.conditions().size(), d.conditions().stream().distinct().count()); assertFalse(d.advantages().isEmpty()); assertFalse(d.tradeoffs().isEmpty()); }
    }
    @ParameterizedTest @EnumSource(PatternId.class)
    void satisfiedDeclarationsCannotMakeJitReplaceRequiredScim(PatternId pattern) {
        var result = evaluate(scimRequired(), pattern, declarations(pattern, Declaration.SATISFIED));
        assertEquals(pattern == PatternId.JIT_LOGIN ? Status.CONDITIONALLY_DOES_NOT_MATCH : Status.CONDITIONALLY_MATCHES, result.status());
        assertEquals(pattern == PatternId.JIT_LOGIN ? Reason.REQUIRED_MECHANISM_ABSENT : Reason.REQUIRED_MECHANISM_PLANNED, result.requirementChecks().getFirst().reasonCode());
    }
    @ParameterizedTest @EnumSource(PatternId.class)
    void everyMissingUnknownAndNegativeConditionIsVisible(PatternId pattern) {
        assertEquals(Status.NEEDS_INFORMATION, evaluate(new ProvisioningRequirements(NOT_REQUIRED, NOT_REQUIRED, NOT_REQUIRED), pattern, Map.of()).status());
        for (var id : definition(pattern).conditions()) {
            var values = declarations(pattern, Declaration.SATISFIED); values.remove(id);
            var partial = evaluate(new ProvisioningRequirements(NOT_REQUIRED, NOT_REQUIRED, NOT_REQUIRED), pattern, values);
            assertEquals(Status.NEEDS_INFORMATION, partial.status()); assertEquals(1, partial.conditionChecks().stream().filter(c -> c.outcome() == Outcome.UNKNOWN).count());
            values.put(id, Declaration.NOT_SATISFIED); assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, evaluate(scimRequired(), pattern, values).status());
            values.put(id, Declaration.UNKNOWN); assertEquals(Reason.CONDITION_UNKNOWN, evaluate(scimRequired(), pattern, values).conditionChecks().stream().filter(c -> c.conditionId() == id).findFirst().orElseThrow().reasonCode());
        }
    }
    @Test void allCriticalityCombinationsRetainHardFailurePrecedenceAndGroupGaps() {
        for (var scim : RequirementCriticality.values()) for (var jit : RequirementCriticality.values()) for (var groups : RequirementCriticality.values()) for (var pattern : PatternId.values()) {
            var result = evaluate(new ProvisioningRequirements(scim, jit, groups), pattern, declarations(pattern, Declaration.SATISFIED));
            boolean fail = scim == REQUIRED && !definition(pattern).scimPlanned() || scim == FORBIDDEN && definition(pattern).scimPlanned()
                    || jit == REQUIRED && !definition(pattern).jitPlanned() || jit == FORBIDDEN && definition(pattern).jitPlanned();
            boolean unknown = scim == UNKNOWN || jit == UNKNOWN || List.of(REQUIRED, FORBIDDEN, UNKNOWN).contains(groups);
            assertEquals(fail ? Status.CONDITIONALLY_DOES_NOT_MATCH : unknown ? Status.NEEDS_INFORMATION : Status.CONDITIONALLY_MATCHES, result.status());
            assertEquals(groups == REQUIRED ? Reason.GROUP_LIFECYCLE_UNASSESSED : groups == FORBIDDEN ? Reason.GROUP_PROHIBITION_UNASSESSED : groups == UNKNOWN ? Reason.REQUIREMENT_UNKNOWN
                    : groups == PREFERRED ? Reason.PREFERENCE_NOT_SCORED : Reason.NO_REQUIREMENT, result.requirementChecks().getLast().reasonCode());
            assertEquals(CHECKED_PATHS, result.requirementChecks().stream().map(RequirementCheck::profilePath).toList());
        }
    }
    @ParameterizedTest @EnumSource(PatternId.class)
    void declarationMapsAreImmutableAndCannotBorrowForeignConditions(PatternId pattern) {
        var values = declarations(pattern, Declaration.SATISFIED); var result = evaluate(scimRequired(), pattern, values); values.clear();
        assertEquals(definition(pattern).conditions().size(), result.declarations().size());
        assertThrows(UnsupportedOperationException.class, () -> result.declarations().clear());
        if (pattern != PatternId.SCIM_AND_JIT) assertThrows(IllegalArgumentException.class,
                () -> evaluate(scimRequired(), pattern, Map.of(ConditionId.SCIM_JIT_COLLISION_POLICY, Declaration.SATISFIED)));
        var nullValue = new HashMap<ConditionId, Declaration>(); nullValue.put(ConditionId.TENANT_AND_SUBJECT_CORRELATION, null);
        assertThrows(NullPointerException.class, () -> evaluate(scimRequired(), pattern, nullValue));
    }
    @Test void previewNeverClaimsProviderOrLifecycleVerificationAndRejectsInvalidBinding() {
        var analysis = evaluate(scimRequired(), PatternId.SCIM_PUSH, declarations(PatternId.SCIM_PUSH, Declaration.SATISFIED));
        var result = new ProvisioningLifecyclePreview(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.EPOCH, analysis);
        assertFalse(result.configurationVerified()); assertFalse(result.providerCompatibilityVerified()); assertFalse(result.lifecycleVerified());
        assertFalse(result.groupSynchronizationVerified()); assertFalse(result.accessRevocationVerified()); assertFalse(result.writesPerformed());
        assertFalse(result.publicationReady()); assertFalse(result.recommendationReady()); assertEquals(5, result.deferredBoundaries().size());
        assertThrows(IllegalArgumentException.class, () -> new ProvisioningLifecyclePreview(result.workspaceId(), result.assessmentId(), -1, Instant.EPOCH, analysis));
        assertThrows(IllegalArgumentException.class, () -> new ProvisioningLifecyclePreview(result.workspaceId(), result.assessmentId(), 9007199254740992L, Instant.EPOCH, analysis));
        assertThrows(NullPointerException.class, () -> new ProvisioningLifecyclePreview(null, result.assessmentId(), 0, Instant.EPOCH, analysis));
    }
}
