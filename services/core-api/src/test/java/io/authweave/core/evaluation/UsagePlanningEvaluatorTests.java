package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import io.authweave.core.assessment.domain.profile.UsagePlanning;
import io.authweave.core.assessment.domain.profile.UsagePlanning.*;
import static org.junit.jupiter.api.Assertions.*;

class UsagePlanningEvaluatorTests {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ASSESSMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @Test
    void unknownInputsNeverBecomeZeroOrAPriceQuote() {
        var result = evaluate(UsagePlanning.unknown());
        assertEquals(UsagePlanningPreflight.Status.NEEDS_INFORMATION, result.status());
        assertEquals(5, result.missingPaths().size());
        assertEquals(4, result.quantityChecks().size());
        result.quantityChecks().forEach(check -> {
            assertNull(check.input()); assertEquals(UsagePlanningPreflight.QuantityStatus.UNKNOWN, check.status());
            assertEquals(check.metric().unit(), check.unit()); assertFalse(check.definition().isBlank());
        });
        assertFalse(result.pricingEvaluated()); assertFalse(result.recommendationReady());
        assertEquals(WORKSPACE, result.workspaceId()); assertEquals(ASSESSMENT, result.assessmentId());
        assertEquals(7, result.assessmentVersion()); assertEquals(NOW, result.evaluatedAt());
        assertTrue(result.explanation().contains("not verified measurements or a cost estimate"));
    }

    @ParameterizedTest
    @EnumSource(Basis.class)
    void zeroAndSafeMaximumAreRecordedValuesNotUnknown(Basis basis) {
        for (long value : new long[] {0, 9007199254740991L}) {
            var result = evaluate(new UsagePlanning("One synthetic production environment; monthly planning scenario.",
                    basis == Basis.ASSUMED ? List.of("No growth modeled beyond this planning month.") : List.of(), volumes(basis, value)));
            assertEquals(UsagePlanningPreflight.Status.INPUTS_RECORDED, result.status());
            assertTrue(result.missingPaths().isEmpty());
            result.quantityChecks().forEach(check -> {
                assertEquals(value, check.input().value()); assertEquals(basis.name(), check.status().name());
            });
            assertFalse(result.pricingEvaluated()); assertFalse(result.recommendationReady());
        }
    }

    @Test
    void assumptionsAreRequiredOnlyForAssumedValuesAndScopeIsStillNeeded() {
        var quantities = volumes(Basis.OBSERVED, 20);
        assertTrue(evaluate(new UsagePlanning("Observed test month", List.of(), quantities)).missingPaths().isEmpty());
        quantities.put(Metric.MONTHLY_ACTIVE_USERS, new Quantity(Basis.ASSUMED, 100L));
        assertEquals(List.of("operations.usagePlanning.assumptions"),
                evaluate(new UsagePlanning("Projected month", List.of(), quantities)).missingPaths());
        assertEquals(List.of("operations.usagePlanning.scopeDescription"),
                evaluate(new UsagePlanning(" \n", List.of("One pilot cohort."), quantities)).missingPaths());
        quantities.remove(Metric.ENTERPRISE_SSO_CONNECTIONS);
        assertEquals(List.of("operations.usagePlanning.volumes.ENTERPRISE_SSO_CONNECTIONS"),
                evaluate(new UsagePlanning("Projected month", List.of("One pilot cohort."), quantities)).missingPaths());
    }

    @Test
    void outputOrderIsStableAndCollectionsAreDefensivelyCopied() {
        var quantities = volumes(Basis.ASSUMED, 1);
        var notes = new ArrayList<>(List.of("One synthetic environment."));
        var planning = new UsagePlanning("Future month", notes, quantities);
        var first = evaluate(planning);
        var reversed = new LinkedHashMap<Metric, Quantity>();
        List.of(Metric.values()).reversed().forEach(metric -> reversed.put(metric, quantities.get(metric)));
        assertEquals(first, evaluate(new UsagePlanning("Future month", notes, reversed)));
        notes.clear(); quantities.clear(); reversed.clear();
        assertEquals(first, evaluate(planning));
        assertThrows(UnsupportedOperationException.class, () -> planning.volumes().clear());
        assertThrows(UnsupportedOperationException.class, () -> planning.assumptions().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.quantityChecks().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.missingPaths().clear());
    }

    @Test
    void domainRejectsInvalidCountsAndUnboundedOrEmptyAssumptions() {
        assertThrows(NullPointerException.class, () -> new Quantity(Basis.ASSUMED, null));
        assertThrows(NullPointerException.class, () -> new Quantity(null, 1L));
        for (long value : new long[] {-1, 9007199254740992L, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Quantity(Basis.ASSUMED, value));
        }
        for (var notes : List.of(List.of(""), List.of(" \t"), List.of("same", "same"), List.of("x".repeat(501)))) {
            assertThrows(IllegalArgumentException.class, () -> new UsagePlanning("Scope", notes, Map.of()));
        }
        var many = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "Assumption " + i).toList();
        assertThrows(IllegalArgumentException.class, () -> new UsagePlanning("Scope", many, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new UsagePlanning("x".repeat(501), List.of(), Map.of()));
        assertTrue(UsagePlanning.unknown().isUnrecorded());
        assertFalse(new UsagePlanning("Partial context", List.of(), Map.of()).isUnrecorded());
    }

    private static Map<Metric, Quantity> volumes(Basis basis, long value) {
        var map = new LinkedHashMap<Metric, Quantity>();
        for (var metric : Metric.values()) map.put(metric, new Quantity(basis, value));
        return map;
    }
    private static UsagePlanningPreflight evaluate(UsagePlanning planning) {
        return UsagePlanningEvaluator.evaluate(WORKSPACE, ASSESSMENT, 7, planning, NOW);
    }
}
