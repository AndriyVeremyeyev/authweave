package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.AuditabilityFacts.*;
import io.authweave.core.catalog.ProviderCatalog.EvidenceStatus;
import io.authweave.core.catalog.ProviderCatalog.Support;
import static io.authweave.core.evaluation.AuditabilityEvaluator.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome;
import static org.junit.jupiter.api.Assertions.*;

class AuditabilityEvaluatorTests {
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final Scope TARGET = new Scope("fictional-eu", "Fictional Plan", "EU", "Fictional configuration");
    private static final URI SOURCE = URI.create("https://audit.example.invalid/docs");
    private static final AuditabilityRequirements ALL = new AuditabilityRequirements(EnumSet.allOf(Criterion.class), 30);
    enum EvidenceState { MISSING, UNREVIEWED, CURRENT, AT_90_DAYS, STALE, FUTURE }

    static Stream<Arguments> matrix() {
        return Arrays.stream(Criterion.values()).flatMap(criterion -> Arrays.stream(Support.values())
                .flatMap(support -> Arrays.stream(EvidenceState.values()).map(state -> Arguments.of(criterion, support, state))));
    }

    @ParameterizedTest @MethodSource("matrix")
    void everyCriterionSupportAndEvidenceStateIsGatedBeforeAConditionalVerdict(Criterion criterion, Support support, EvidenceState state) {
        var facts = state == EvidenceState.MISSING ? List.<Fact>of() : List.of(fact(criterion, support, state,
                criterion == Criterion.AUDIT_LOG_RETENTION && support == Support.SUPPORTED ? 30 : null));
        var requirements = selected(criterion, 30);
        var analysis = evaluate(RequirementCriticality.REQUIRED, requirements, TARGET, facts, AT);
        var check = check(analysis, criterion);
        var expected = switch (state) {
            case MISSING -> Reason.EVIDENCE_MISSING;
            case UNREVIEWED -> Reason.EVIDENCE_UNREVIEWED;
            case STALE -> Reason.EVIDENCE_STALE;
            case FUTURE -> Reason.EVIDENCE_FROM_FUTURE;
            case CURRENT, AT_90_DAYS -> support == Support.UNSUPPORTED ? Reason.CAPABILITY_UNAVAILABLE :
                    support == Support.UNKNOWN ? Reason.CAPABILITY_UNKNOWN : criterion == Criterion.AUDIT_LOG_RETENTION ?
                            Reason.RETENTION_MEETS_MINIMUM : Reason.DOCUMENTED_CAPABILITY_AVAILABLE;
        };
        var outcome = expected == Reason.CAPABILITY_UNAVAILABLE ? Outcome.FAIL :
                expected == Reason.DOCUMENTED_CAPABILITY_AVAILABLE || expected == Reason.RETENTION_MEETS_MINIMUM ? Outcome.PASS : Outcome.UNKNOWN;
        assertEquals(expected, check.reasonCode()); assertEquals(outcome, check.outcome());
        assertEquals(outcome == Outcome.FAIL ? Status.DOES_NOT_MATCH : outcome == Outcome.PASS ?
                Status.MATCHES_CHECKED_REQUIREMENTS : Status.NEEDS_INFORMATION, analysis.status());
        assertEquals(expected == Reason.RETENTION_MEETS_MINIMUM ? 30 : null, check.documentedMinimumRetentionDays());
        assertEquals(5, analysis.checks().stream().filter(c -> c.reasonCode() == Reason.CRITERION_NOT_SELECTED).count());
        assertFalse(analysis.configurationVerified()); assertFalse(analysis.complianceVerified()); assertFalse(analysis.recommendationReady());
        assertEquals(POLICY_VERSION, analysis.policyVersion()); assertEquals(AT, analysis.evaluatedAt());
    }

    @ParameterizedTest @EnumSource(RequirementCriticality.class)
    void criticalityNeverSilentlyAddsScopeScoresOrWeakerLoggingInstructions(RequirementCriticality criticality) {
        var analysis = evaluate(criticality, AuditabilityRequirements.unspecified(), TARGET, allSupported(), AT);
        var reason = switch (criticality) {
            case REQUIRED -> Reason.AUDIT_SCOPE_UNKNOWN;
            case PREFERRED -> Reason.PREFERENCE_NOT_SCORED;
            case NOT_REQUIRED -> Reason.NO_REQUIREMENT;
            case UNKNOWN -> Reason.REQUIREMENT_UNKNOWN;
            case FORBIDDEN -> Reason.AUDIT_INTENT_UNCLEAR;
        };
        assertTrue(analysis.checks().stream().allMatch(c -> c.reasonCode() == reason));
        assertEquals(criticality == RequirementCriticality.NOT_REQUIRED || criticality == RequirementCriticality.PREFERRED ?
                Status.NOT_APPLIED : Status.NEEDS_INFORMATION, analysis.status());
        assertFalse(analysis.recommendationReady());
    }

    @ParameterizedTest @ValueSource(ints = { 0, 1, 29, 30, 31, 36500 })
    void documentedMinimumRetentionIsComparedToTheExplicitThresholdNotSourceAgeOrAnExternalSink(int days) {
        var analysis = evaluate(RequirementCriticality.REQUIRED, selected(Criterion.AUDIT_LOG_RETENTION, 30), TARGET,
                List.of(fact(Criterion.AUDIT_LOG_RETENTION, Support.SUPPORTED, EvidenceState.CURRENT, days)), AT);
        var check = check(analysis, Criterion.AUDIT_LOG_RETENTION);
        assertEquals(days < 30 ? Reason.RETENTION_BELOW_MINIMUM : Reason.RETENTION_MEETS_MINIMUM, check.reasonCode());
        assertEquals(days, check.documentedMinimumRetentionDays());
        assertEquals(days < 30 ? Status.DOES_NOT_MATCH : Status.MATCHES_CHECKED_REQUIREMENTS, analysis.status());
    }

    @Test void availabilityWithoutADocumentedRetentionDurationStaysUnknown() {
        var analysis = evaluate(RequirementCriticality.REQUIRED, ALL, TARGET,
                List.of(fact(Criterion.AUDIT_LOG_RETENTION, Support.SUPPORTED, EvidenceState.CURRENT, null)), AT);
        assertEquals(Reason.RETENTION_DURATION_UNKNOWN, check(analysis, Criterion.AUDIT_LOG_RETENTION).reasonCode());
        assertEquals(Status.NEEDS_INFORMATION, analysis.status());
    }

    @ParameterizedTest @EnumSource(value = RequirementCriticality.class, names = { "PREFERRED", "NOT_REQUIRED", "UNKNOWN", "FORBIDDEN" })
    void explicitSelectedDetailsDoNotTurnPreferencesUnknownsOrProhibitionsIntoElimination(RequirementCriticality criticality) {
        for (var facts : List.of(allSupported(), List.<Fact>of(),
                List.of(fact(Criterion.AUTHENTICATION_SUCCESS_EVENTS, Support.UNSUPPORTED, EvidenceState.CURRENT, null)))) {
            var analysis = evaluate(criticality, ALL, TARGET, facts, AT);
            assertTrue(analysis.checks().stream().noneMatch(c -> c.outcome() == Outcome.FAIL || c.outcome() == Outcome.PASS));
            assertTrue(analysis.checks().stream().allMatch(c -> c.documentedMinimumRetentionDays() == null));
            assertEquals(criticality == RequirementCriticality.PREFERRED || criticality == RequirementCriticality.NOT_REQUIRED ?
                    Status.NOT_APPLIED : Status.NEEDS_INFORMATION, analysis.status());
        }
    }

    @Test void unselectedUnavailableCapabilitiesDoNotCreateAnImplicitRequirementOrLeakARetentionThreshold() {
        var facts = Arrays.stream(Criterion.values()).map(c -> fact(c, c == Criterion.AUTHENTICATION_SUCCESS_EVENTS ?
                Support.SUPPORTED : Support.UNSUPPORTED, EvidenceState.CURRENT, null)).toList();
        var analysis = evaluate(RequirementCriticality.REQUIRED, selected(Criterion.AUTHENTICATION_SUCCESS_EVENTS, 30), TARGET, facts, AT);
        assertEquals(Status.MATCHES_CHECKED_REQUIREMENTS, analysis.status());
        assertEquals(1, analysis.checks().stream().filter(c -> c.outcome() == Outcome.PASS).count());
        assertEquals(5, analysis.checks().stream().filter(c -> c.reasonCode() == Reason.CRITERION_NOT_SELECTED).count());
        assertNull(analysis.requirements().minimumRetentionDays());
    }

    @Test void retentionReasonsAndNumericValuesCannotDescribeAnEventOrExportCriterion() {
        assertThrows(IllegalArgumentException.class, () -> new Check(Criterion.AUDIT_LOG_EXPORT, Outcome.PASS, Reason.RETENTION_MEETS_MINIMUM, 30));
        assertThrows(IllegalArgumentException.class, () -> new Check(Criterion.AUTHENTICATION_SUCCESS_EVENTS, Outcome.UNKNOWN, Reason.RETENTION_DURATION_UNKNOWN, null));
        assertThrows(IllegalArgumentException.class, () -> new Check(Criterion.AUDIT_LOG_RETENTION, Outcome.PASS, Reason.DOCUMENTED_CAPABILITY_AVAILABLE, null));
        assertThrows(IllegalArgumentException.class, () -> new Check(Criterion.AUDIT_LOG_EXPORT, Outcome.UNKNOWN, Reason.EVIDENCE_MISSING, 30));
    }

    @Test void aFailureDoesNotEraseOtherUnknownCriteriaAndMissingEvidenceIsNotAnExclusion() {
        var analysis = evaluate(RequirementCriticality.REQUIRED, ALL, TARGET,
                List.of(fact(Criterion.AUTHENTICATION_SUCCESS_EVENTS, Support.UNSUPPORTED, EvidenceState.CURRENT, null)), AT);
        assertEquals(Status.DOES_NOT_MATCH, analysis.status());
        assertEquals(1, analysis.checks().stream().filter(c -> c.outcome() == Outcome.FAIL).count());
        assertEquals(5, analysis.checks().stream().filter(c -> c.reasonCode() == Reason.EVIDENCE_MISSING).count());
        assertEquals(Status.NEEDS_INFORMATION, evaluate(RequirementCriticality.REQUIRED, ALL, TARGET, List.of(), AT).status());
    }

    @ParameterizedTest @EnumSource(RequirementCriticality.class)
    void pooledForeignScopeApplicationLogsOrDuplicateFactsAreRejectedEvenWhenChecksWouldBeSkipped(RequirementCriticality criticality) {
        var own = fact(Criterion.AUDIT_LOG_EXPORT, Support.SUPPORTED, EvidenceState.CURRENT, null);
        for (var foreign : List.of(new Scope("other-eu", TARGET.plan(), TARGET.region(), TARGET.configuration()),
                new Scope(TARGET.optionId(), "Other Plan", TARGET.region(), TARGET.configuration()),
                new Scope(TARGET.optionId(), TARGET.plan(), "US", TARGET.configuration()),
                new Scope(TARGET.optionId(), TARGET.plan(), TARGET.region(), "Other configuration"))) {
            var fact = new Fact(foreign, Emitter.IDENTITY_PROVIDER, own.criterion(), own.support(), null, own.evidenceStatus(), SOURCE, AT);
            assertThrows(IllegalArgumentException.class, () -> evaluate(criticality, AuditabilityRequirements.unspecified(), TARGET, List.of(fact), AT));
        }
        var appLog = new Fact(TARGET, Emitter.APPLICATION, own.criterion(), own.support(), null, own.evidenceStatus(), SOURCE, AT);
        assertThrows(IllegalArgumentException.class, () -> evaluate(criticality, ALL, TARGET, List.of(appLog), AT));
        assertThrows(IllegalArgumentException.class, () -> evaluate(criticality, ALL, TARGET, List.of(own, own), AT));
    }

    @Test void sixFreshDocumentedCapabilitiesAreNotConfigurationComplianceLifecycleOrRecommendationEvidence() {
        var analysis = evaluate(RequirementCriticality.REQUIRED, ALL, TARGET, allSupported(), AT);
        assertEquals(Status.MATCHES_CHECKED_REQUIREMENTS, analysis.status());
        assertEquals(6, analysis.checks().stream().filter(c -> c.outcome() == Outcome.PASS).count());
        var json = JsonMapper.builder().build().valueToTree(analysis);
        assertEquals("SYNTHETIC_SCOPED_PROVIDER_CAPABILITY_EVIDENCE", json.get("analysisBasis").asText());
        assertFalse(json.get("configurationVerified").asBoolean()); assertFalse(json.get("complianceVerified").asBoolean());
        assertFalse(json.get("recommendationReady").asBoolean());
        assertEquals(7, json.get("deferredBoundaries").size());
        assertFalse(json.has("approvalGranted")); assertFalse(json.has("sourceVerificationPerformed"));
        var profile = ApplicationIdentityProfile.unknown();
        assertEquals(Status.NEEDS_INFORMATION, evaluate(profile.security().auditability(), AuditabilityRequirements.unspecified(), TARGET, allSupported(), AT).status());
        assertEquals(RequirementCriticality.UNKNOWN, profile.security().auditability());
    }

    @ParameterizedTest @ValueSource(strings = { "missing", "duplicate", "wrong-order", "wrong-outcome", "unselected", "wrong-criticality", "wrong-threshold" })
    void forgedOrPartialCellsCannotProduceAFalseMatch(String variant) {
        var good = evaluate(RequirementCriticality.REQUIRED, ALL, TARGET, allSupported(), AT);
        assertThrows(IllegalArgumentException.class, () -> {
            var checks = new ArrayList<>(good.checks());
            var requirements = ALL;
            var criticality = RequirementCriticality.REQUIRED;
            switch (variant) {
                case "missing" -> checks.clear();
                case "duplicate" -> checks.set(1, checks.getFirst());
                case "wrong-order" -> java.util.Collections.swap(checks, 0, 1);
                case "wrong-outcome" -> checks.set(0, new Check(checks.getFirst().criterion(), Outcome.PASS, Reason.EVIDENCE_MISSING, null));
                case "unselected" -> requirements = AuditabilityRequirements.unspecified();
                case "wrong-criticality" -> criticality = RequirementCriticality.UNKNOWN;
                default -> requirements = new AuditabilityRequirements(ALL.selectedCriteria(), 31);
            }
            new Analysis(TARGET, criticality, requirements, AT, checks);
        });
    }

    @Test void definitionsInputsAndResultsAreImmutableAndNullsCannotAcquireImplicitDefaults() {
        assertEquals("auditability-capability-preflight-1", POLICY_VERSION);
        assertEquals(List.of(Criterion.values()), DEFINITIONS.stream().map(Definition::criterion).toList());
        assertThrows(UnsupportedOperationException.class, () -> DEFINITIONS.clear());
        var criteria = EnumSet.allOf(Criterion.class); var requirements = new AuditabilityRequirements(criteria, 30);
        criteria.clear(); assertEquals(6, requirements.selectedCriteria().size());
        var facts = new ArrayList<>(allSupported()); var analysis = evaluate(RequirementCriticality.REQUIRED, requirements, TARGET, facts, AT);
        facts.clear(); assertEquals(Status.MATCHES_CHECKED_REQUIREMENTS, analysis.status());
        assertThrows(UnsupportedOperationException.class, () -> analysis.checks().clear());
        assertThrows(UnsupportedOperationException.class, () -> requirements.selectedCriteria().clear());
        assertThrows(UnsupportedOperationException.class, () -> analysis.deferredBoundaries().clear());
        assertThrows(NullPointerException.class, () -> evaluate(null, ALL, TARGET, List.of(), AT));
        assertThrows(NullPointerException.class, () -> evaluate(RequirementCriticality.REQUIRED, null, TARGET, List.of(), AT));
        assertThrows(NullPointerException.class, () -> evaluate(RequirementCriticality.REQUIRED, ALL, null, List.of(), AT));
        assertThrows(NullPointerException.class, () -> evaluate(RequirementCriticality.REQUIRED, ALL, TARGET, null, AT));
        assertThrows(NullPointerException.class, () -> evaluate(RequirementCriticality.REQUIRED, ALL, TARGET, List.of(), null));
    }

    private static AuditabilityRequirements selected(Criterion criterion, int days) {
        return new AuditabilityRequirements(Set.of(criterion), criterion == Criterion.AUDIT_LOG_RETENTION ? days : null);
    }
    private static Check check(Analysis analysis, Criterion criterion) {
        return analysis.checks().stream().filter(c -> c.criterion() == criterion).findFirst().orElseThrow();
    }
    private static List<Fact> allSupported() {
        return Arrays.stream(Criterion.values()).map(c -> fact(c, Support.SUPPORTED, EvidenceState.CURRENT,
                c == Criterion.AUDIT_LOG_RETENTION ? 30 : null)).toList();
    }
    private static Fact fact(Criterion criterion, Support support, EvidenceState state, Integer days) {
        Instant observed = switch (state) {
            case AT_90_DAYS -> AT.minus(Duration.ofDays(90));
            case STALE -> AT.minus(Duration.ofDays(90)).minusNanos(1);
            case FUTURE -> AT.plusNanos(1);
            default -> AT;
        };
        return new Fact(TARGET, Emitter.IDENTITY_PROVIDER, criterion, support, days,
                state == EvidenceState.UNREVIEWED ? EvidenceStatus.UNREVIEWED : EvidenceStatus.REVIEWED, SOURCE, observed);
    }
}
