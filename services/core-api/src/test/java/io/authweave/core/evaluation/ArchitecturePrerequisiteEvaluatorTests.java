package io.authweave.core.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import static io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchitecturePrerequisiteEvaluatorTests {
    static Stream<Arguments> declarationMatrix() {
        return DEFINITIONS.stream().flatMap(d -> Arrays.stream(ClientScope.values()).flatMap(scope -> Arrays.stream(Declaration.values())
                .map(declaration -> Arguments.of(d, scope, declaration))));
    }
    @ParameterizedTest @MethodSource("declarationMatrix")
    void everyPrerequisiteDeclarationAndClientScopeHasAnExplicitConditionalOutcome(Definition definition, ClientScope scope, Declaration declaration) {
        var declarations = satisfied(definition.patternId()); declarations.put(definition.prerequisiteId(), declaration);
        var analysis = evaluate(definition.patternId(), scope, declarations);
        var check = analysis.checks().stream().filter(c -> c.prerequisiteId() == definition.prerequisiteId()).findFirst().orElseThrow();
        if (scope == ClientScope.NOT_SELECTED) {
            assertEquals(Status.NOT_APPLICABLE, analysis.status());
            assertTrue(analysis.checks().stream().allMatch(c -> c.outcome() == Outcome.NOT_APPLICABLE && c.reasonCode() == Reason.PATTERN_NOT_APPLICABLE));
        } else if (scope == ClientScope.UNKNOWN) {
            assertEquals(Status.NEEDS_INFORMATION, analysis.status());
            assertTrue(analysis.checks().stream().allMatch(c -> c.outcome() == Outcome.UNKNOWN && c.reasonCode() == Reason.CLIENT_SCOPE_UNKNOWN));
        } else {
            assertEquals(switch (declaration) { case SATISFIED -> Status.CONDITIONALLY_MATCHES; case NOT_SATISFIED -> Status.CONDITIONALLY_DOES_NOT_MATCH;
                case UNKNOWN -> Status.NEEDS_INFORMATION; }, analysis.status());
            assertEquals(switch (declaration) { case SATISFIED -> Outcome.CONDITIONALLY_SATISFIED; case NOT_SATISFIED -> Outcome.CONDITIONALLY_NOT_SATISFIED;
                case UNKNOWN -> Outcome.UNKNOWN; }, check.outcome());
            assertEquals(switch (declaration) { case SATISFIED -> Reason.DECLARED_CONDITION_SATISFIED; case NOT_SATISFIED -> Reason.DECLARED_CONDITION_NOT_SATISFIED;
                case UNKNOWN -> Reason.CONDITION_UNKNOWN; }, check.reasonCode());
        }
        assertFalse(analysis.configurationVerified()); assertFalse(analysis.providerCompatibilityVerified()); assertFalse(analysis.recommendationReady());
        assertEquals("UNVERIFIED_DESIGN_DECLARATIONS", analysis.analysisBasis()); assertEquals(POLICY_VERSION, analysis.policyVersion());
    }

    @Test void sourceOwnedIdsCoverEveryExistingPrerequisiteWithoutFreeTextParsingOrNewProtocolClaims() {
        assertEquals("architecture-prerequisites-1", POLICY_VERSION); assertEquals(11, DEFINITIONS.size());
        assertEquals(11, DEFINITIONS.stream().map(Definition::prerequisiteId).distinct().count());
        for (var pattern : ArchitecturePatternEvaluator.evaluate(ApplicationIdentityProfile.unknown())) {
            var definitions = DEFINITIONS.stream().filter(d -> d.patternId() == pattern.patternId()).toList();
            assertEquals(pattern.prerequisites(), definitions.stream().map(Definition::description).toList());
            assertEquals(ClientScope.UNKNOWN, scope(pattern));
        }
        assertThrows(UnsupportedOperationException.class, () -> DEFINITIONS.clear());
    }

    @ParameterizedTest @EnumSource(PatternId.class)
    void missingDeclarationsStayUnknownAndOneUnmetConditionCannotHideAnotherUnknown(PatternId pattern) {
        var empty = evaluate(pattern, ClientScope.SELECTED, Map.of());
        assertEquals(Status.NEEDS_INFORMATION, empty.status()); assertTrue(empty.checks().stream().allMatch(c -> c.outcome() == Outcome.UNKNOWN));
        var declarations = satisfied(pattern); var ids = List.copyOf(declarations.keySet());
        declarations.put(ids.getFirst(), Declaration.NOT_SATISFIED); declarations.put(ids.getLast(), Declaration.UNKNOWN);
        var mixed = evaluate(pattern, ClientScope.SELECTED, declarations);
        assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, mixed.status());
        assertEquals(1, mixed.checks().stream().filter(c -> c.outcome() == Outcome.CONDITIONALLY_NOT_SATISFIED).count());
        assertEquals(1, mixed.checks().stream().filter(c -> c.outcome() == Outcome.UNKNOWN).count());
        assertFalse(mixed.configurationVerified());
    }

    @ParameterizedTest @EnumSource(PatternId.class)
    void anotherPatternsDeclarationCannotSupplyMissingConditionsEvenInAnUnselectedScope(PatternId pattern) {
        var foreign = DEFINITIONS.stream().filter(d -> d.patternId() != pattern).findFirst().orElseThrow().prerequisiteId();
        for (var scope : ClientScope.values())
            assertThrows(IllegalArgumentException.class, () -> evaluate(pattern, scope, Map.of(foreign, Declaration.SATISFIED)));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "foreign", "selected-as-skipped", "unknown-as-satisfied", "wrong-reason"})
    void partialOrForgedCellsCannotProduceVacuousSuccess(String variant) {
        var good = evaluate(PatternId.BFF_SESSION, ClientScope.SELECTED, satisfied(PatternId.BFF_SESSION));
        assertThrows(IllegalArgumentException.class, () -> {
            var checks = new ArrayList<>(good.checks()); var scope = good.clientScope();
            switch (variant) {
                case "missing" -> checks.clear();
                case "duplicate" -> checks.set(1, checks.getFirst());
                case "foreign" -> checks.set(1, new Check(PrerequisiteId.WORKLOAD_CONFIDENTIAL_CLIENT, Outcome.CONDITIONALLY_SATISFIED, Reason.DECLARED_CONDITION_SATISFIED));
                case "selected-as-skipped" -> checks.set(0, new Check(checks.getFirst().prerequisiteId(), Outcome.NOT_APPLICABLE, Reason.PATTERN_NOT_APPLICABLE));
                case "unknown-as-satisfied" -> scope = ClientScope.UNKNOWN;
                default -> checks.set(0, new Check(checks.getFirst().prerequisiteId(), Outcome.CONDITIONALLY_SATISFIED, Reason.CONDITION_UNKNOWN));
            }
            new Analysis(good.patternId(), scope, checks);
        });
    }

    @Test void nullInputsFailClosedAndResultsDoNotRetainMutableDeclarationMaps() {
        var declarations = satisfied(PatternId.BFF_SESSION); var first = evaluate(PatternId.BFF_SESSION, ClientScope.SELECTED, declarations);
        declarations.clear(); assertEquals(Status.CONDITIONALLY_MATCHES, first.status());
        assertThrows(UnsupportedOperationException.class, () -> first.checks().clear());
        assertThrows(NullPointerException.class, () -> evaluate(null, ClientScope.SELECTED, Map.of()));
        assertThrows(NullPointerException.class, () -> evaluate(PatternId.BFF_SESSION, null, Map.of()));
        declarations.put(PrerequisiteId.BFF_SESSION_DEFENSES, null);
        assertThrows(NullPointerException.class, () -> evaluate(PatternId.BFF_SESSION, ClientScope.SELECTED, declarations));
    }

    private static Map<PrerequisiteId, Declaration> satisfied(PatternId pattern) {
        var result = new HashMap<PrerequisiteId, Declaration>();
        DEFINITIONS.stream().filter(d -> d.patternId() == pattern).forEach(d -> result.put(d.prerequisiteId(), Declaration.SATISFIED));
        return result;
    }
}
