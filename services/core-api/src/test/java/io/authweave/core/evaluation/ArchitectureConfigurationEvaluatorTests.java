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
import static io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import static io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.ClientScope;
import static io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Outcome;
import static io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Status;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureConfigurationEvaluatorTests {
    // Independently stated reference designs, not expectations calculated by the evaluator.
    private static final Map<PatternId, List<SettingValue>> EXPECTED = Map.of(
            PatternId.BFF_SESSION, List.of(SettingValue.AUTHORIZATION_CODE, SettingValue.CONFIDENTIAL, SettingValue.SERVER_HELD_CREDENTIAL,
                    SettingValue.APPLICATION_SERVER, SettingValue.S256, SettingValue.EXACT_REGISTERED, SettingValue.ENABLED, SettingValue.ENABLED, SettingValue.DEFENSE_PLANNED, SettingValue.BFF_PROXY),
            PatternId.SERVER_SIDE_SESSION, List.of(SettingValue.AUTHORIZATION_CODE, SettingValue.CONFIDENTIAL, SettingValue.SERVER_HELD_CREDENTIAL,
                    SettingValue.APPLICATION_SERVER, SettingValue.S256, SettingValue.EXACT_REGISTERED, SettingValue.ENABLED, SettingValue.ENABLED, SettingValue.DEFENSE_PLANNED, SettingValue.SESSION_BACKEND),
            PatternId.SPA_CODE_PKCE, List.of(SettingValue.AUTHORIZATION_CODE, SettingValue.PUBLIC, SettingValue.NONE, SettingValue.BROWSER,
                    SettingValue.S256, SettingValue.EXACT_REGISTERED, SettingValue.DIRECT_BROWSER, SettingValue.REQUIRED_ORIGINS_PLANNED),
            PatternId.NATIVE_CODE_PKCE, List.of(SettingValue.AUTHORIZATION_CODE, SettingValue.PUBLIC, SettingValue.NONE, SettingValue.NATIVE_APP,
                    SettingValue.S256, SettingValue.EXACT_REGISTERED, SettingValue.EXTERNAL_BROWSER),
            PatternId.M2M_CLIENT_CREDENTIALS, List.of(SettingValue.CLIENT_CREDENTIALS, SettingValue.CONFIDENTIAL, SettingValue.WORKLOAD_HELD_CREDENTIAL,
                    SettingValue.WORKLOAD, SettingValue.WORKLOAD_OWN_OR_PREARRANGED));
    static Stream<Arguments> matrix() {
        return Arrays.stream(PatternId.values()).flatMap(pattern -> definitions(pattern).stream().flatMap(definition ->
                definition.allowedValues().stream().flatMap(value -> Arrays.stream(ClientScope.values()).map(scope -> Arguments.of(pattern, definition.settingId(), value, scope)))));
    }
    @ParameterizedTest @MethodSource("matrix")
    void eachTypedSettingHasAnExplicitScopeAndConditionalOutcome(PatternId pattern, SettingId id, SettingValue value, ClientScope scope) {
        var settings = matching(pattern); var compatible = settings.get(id) == value
                || pattern == PatternId.NATIVE_CODE_PKCE && id == SettingId.REDIRECT_MATCHING && value == SettingValue.NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION;
        settings.put(id, value);
        var result = ArchitectureConfigurationEvaluator.evaluate(pattern, scope, settings);
        var expectedReason = scope == ClientScope.NOT_SELECTED ? ArchitectureConfigurationEvaluator.Reason.PATTERN_NOT_APPLICABLE
                : scope == ClientScope.UNKNOWN ? ArchitectureConfigurationEvaluator.Reason.CLIENT_SCOPE_UNKNOWN
                : value == SettingValue.UNKNOWN ? ArchitectureConfigurationEvaluator.Reason.SETTING_UNKNOWN
                : compatible ? ArchitectureConfigurationEvaluator.Reason.EXPECTED_SETTING_DECLARED : ArchitectureConfigurationEvaluator.Reason.INCOMPATIBLE_SETTING_DECLARED;
        assertEquals(expectedReason, result.checks().stream().filter(c -> c.settingId() == id).findFirst().orElseThrow().reasonCode());
        assertEquals(scope == ClientScope.NOT_SELECTED ? Status.NOT_APPLICABLE : scope == ClientScope.UNKNOWN || value == SettingValue.UNKNOWN ? Status.NEEDS_INFORMATION
                : compatible ? Status.CONDITIONALLY_MATCHES : Status.CONDITIONALLY_DOES_NOT_MATCH, result.status());
        assertFalse(result.configurationObserved()); assertFalse(result.configurationVerified()); assertFalse(result.providerCompatibilityVerified());
        assertFalse(result.runtimeFlowVerified()); assertFalse(result.recommendationReady()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
        assertEquals("UNVERIFIED_PROPOSED_CONFIGURATION", result.analysisBasis());
    }
    @ParameterizedTest @EnumSource(PatternId.class)
    void omittedSettingsStayUnknownAndFailureDoesNotHideGaps(PatternId pattern) {
        var result = ArchitectureConfigurationEvaluator.evaluate(pattern, ClientScope.SELECTED, Map.of());
        assertTrue(result.checks().stream().allMatch(c -> c.reasonCode() == ArchitectureConfigurationEvaluator.Reason.SETTING_UNKNOWN));
        var mixed = ArchitectureConfigurationEvaluator.evaluate(pattern, ClientScope.SELECTED, Map.of(SettingId.OAUTH_CLIENT_TYPE,
                matching(pattern).get(SettingId.OAUTH_CLIENT_TYPE) == SettingValue.PUBLIC ? SettingValue.CONFIDENTIAL : SettingValue.PUBLIC));
        assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, mixed.status());
        assertEquals(1, mixed.checks().stream().filter(c -> c.outcome() == Outcome.CONDITIONALLY_NOT_SATISFIED).count());
        assertEquals(definitions(pattern).size() - 1, mixed.checks().stream().filter(c -> c.outcome() == Outcome.UNKNOWN).count());
    }
    @ParameterizedTest @EnumSource(PatternId.class)
    void invalidKeysAndMismatchedValueTypesFailEvenOutsideSelectedScope(PatternId pattern) {
        var scoped = definitions(pattern);
        for (var scope : ClientScope.values()) for (var id : SettingId.values()) for (var value : SettingValue.values()) {
            var definition = scoped.stream().filter(d -> d.settingId() == id).findFirst();
            if (definition.isEmpty() || !definition.get().allowedValues().contains(value))
                assertThrows(IllegalArgumentException.class, () -> ArchitectureConfigurationEvaluator.evaluate(pattern, scope, Map.of(id, value)));
        }
    }
    @Test void orderedChecksAreInputBoundAndImmutableRatherThanCallerSuppliedAuthority() {
        var input = matching(PatternId.BFF_SESSION); var result = ArchitectureConfigurationEvaluator.evaluate(PatternId.BFF_SESSION, ClientScope.SELECTED, input);
        input.clear(); assertEquals(10, result.settings().size());
        assertThrows(UnsupportedOperationException.class, () -> result.settings().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.checks().clear());
        for (String mutation : List.of("missing", "duplicate", "reorder", "scope", "settings", "outcome")) {
            assertThrows(IllegalArgumentException.class, () -> {
                var checks = new ArrayList<>(result.checks()); var settings = new HashMap<>(result.settings()); var scope = ClientScope.SELECTED;
                switch (mutation) {
                    case "missing" -> checks.removeLast();
                    case "duplicate" -> checks.set(1, checks.getFirst());
                    case "reorder" -> java.util.Collections.reverse(checks);
                    case "scope" -> scope = ClientScope.UNKNOWN;
                    case "settings" -> settings.put(SettingId.PKCE_METHOD, SettingValue.NONE);
                    default -> checks.set(0, new ArchitectureConfigurationEvaluator.Check(SettingId.OAUTH_FLOW, Outcome.UNKNOWN, ArchitectureConfigurationEvaluator.Reason.SETTING_UNKNOWN));
                }
                new ArchitectureConfigurationEvaluator.Analysis(result.patternId(), scope, settings, checks);
            });
        }
        assertThrows(NullPointerException.class, () -> ArchitectureConfigurationEvaluator.evaluate(null, ClientScope.UNKNOWN, Map.of()));
        assertThrows(NullPointerException.class, () -> ArchitectureConfigurationEvaluator.evaluate(PatternId.BFF_SESSION, null, Map.of()));
        input.put(SettingId.OAUTH_FLOW, null);
        assertThrows(NullPointerException.class, () -> ArchitectureConfigurationEvaluator.evaluate(PatternId.BFF_SESSION, ClientScope.UNKNOWN, input));
    }
    @Test void policyMakesConservativePkceAndNativeRedirectExceptionExplicit() {
        for (var pattern : PatternId.values()) {
            assertEquals(EXPECTED.get(pattern).size(), definitions(pattern).size());
            if (pattern != PatternId.M2M_CLIENT_CREDENTIALS) assertTrue(definitions(pattern).stream().filter(d -> d.settingId() == SettingId.PKCE_METHOD).findFirst().orElseThrow().description().contains("not a universal normative MUST"));
            assertTrue(definitions(pattern).stream().allMatch(d -> d.references().stream().allMatch(uri -> uri.getHost().equals("www.rfc-editor.org"))));
        }
        assertEquals(13, Arrays.stream(PatternId.values()).flatMap(p -> definitions(p).stream()).map(Definition::settingId).distinct().count());
    }
    private static Map<SettingId, SettingValue> matching(PatternId pattern) {
        var result = new HashMap<SettingId, SettingValue>(); var definitions = definitions(pattern);
        for (int i = 0; i < definitions.size(); i++) result.put(definitions.get(i).settingId(), EXPECTED.get(pattern).get(i));
        return result;
    }
}
