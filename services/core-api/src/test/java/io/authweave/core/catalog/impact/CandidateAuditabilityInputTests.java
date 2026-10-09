package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fictional .invalid supplement claims; no live source, identity grant or stored review is created. */
class CandidateAuditabilityInputTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-09T17:00:00Z");
    private static final String ID = "example-managed-eu";
    private static final Path ROOT = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();
    static JsonNode base() { return MAPPER.readTree(ROOT.resolve("packages/contracts/tests/fixtures/provider-catalog-draft.valid.json").toFile()); }
    static JsonNode supplement() { return MAPPER.readTree(ROOT.resolve("packages/contracts/tests/fixtures/catalog-auditability-draft.valid.json").toFile()); }
    static JsonNode profile() {
        var profile = (ObjectNode) MAPPER.readTree(ROOT.resolve("packages/contracts/decision-core/catalog-case.b2b-browser-scim.v1.json").toFile()).get("profile");
        ((ObjectNode) profile.get("audience")).set("populations", MAPPER.createArrayNode().add("PARTNERS"));
        ((ObjectNode) profile.get("audience")).put("tenancy", "MULTI_TENANT_ORGANIZATIONS");
        ((ObjectNode) profile.at("/protocols/federation")).put("SAML", "NOT_REQUIRED");
        ((ObjectNode) profile.get("security")).put("auditability", "REQUIRED");
        var requirements = (ObjectNode) profile.at("/security/auditabilityRequirements");
        requirements.set("selectedCriteria", MAPPER.createArrayNode().add("AUTHENTICATION_SUCCESS_EVENTS").add("AUDIT_LOG_RETENTION"));
        requirements.put("minimumRetentionDays", 30); return profile;
    }
    static JsonNode weights() { return MAPPER.readTree("{\"mode\":\"NONE\",\"values\":[]}"); }
    static SourceAssertions supporting(JsonNode catalog) {
        var assertions = new ArrayList<FactAssertion>();
        for (var option : catalog.get("options")) {
            var typed = MAPPER.treeToValue(option, ProviderCatalogDraft.Option.class);
            CatalogDraftFacts.entries(typed).keySet().stream().sorted().forEach(path -> assertions.add(
                    new FactAssertion(typed.id(), path, claimSha256(option, path), Assertion.SOURCE_SUPPORTS_CLAIM)));
        }
        return new SourceAssertions(DecisionCanonicalizer.sha256(catalog), assertions);
    }
    static CandidateAuditabilityInput input(JsonNode base, JsonNode supplement) {
        var claims = CandidateAuditabilityInput.claimDigests(supplement);
        var assertions = claims.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().optionId() + e.getKey().criterion()))
                .map(e -> new CandidateAuditabilityInput.FactAssertion(e.getKey().optionId(), e.getKey().criterion(), e.getValue(), Assertion.SOURCE_SUPPORTS_CLAIM)).toList();
        return new CandidateAuditabilityInput(DecisionCanonicalizer.sha256(base), supplement, assertions);
    }
    private static CandidateDecisionEvaluator.Result evaluate(JsonNode profile, JsonNode base, CandidateAuditabilityInput audit, Instant at) {
        return CandidateDecisionEvaluator.evaluate(profile, 6, base, supporting(base), audit, weights(), at);
    }
    private static Finding finding(CandidateDecisionEvaluator.Result result, Criterion criterion) {
        return result.candidates().getFirst().hardChecks().findings().stream().filter(f -> f.factPath() != null
                && f.factPath().equals("auditabilitySupplement." + criterion)).findFirst().orElseThrow();
    }

    @Test void optionalSupplementIsNeverFilledFromSyntheticRuntimeFacts() {
        var result = evaluate(profile(), base(), null, NOW);
        assertEquals(Verdict.UNRESOLVED, result.candidates().getFirst().hardChecks().hardVerdict());
        assertEquals("EVIDENCE_MISSING", finding(result, Criterion.AUDIT_LOG_RETENTION).reasonCode());
        assertNull(result.binding().inputs().hardChecks().auditabilitySha256()); assertTrue(result.shortlist().isEmpty());
    }
    @Test void exactSourceHypothesesRunRequiredEventAndRetentionChecksWithoutPromotingTrust() {
        var base = base(); var draft = supplement(); var result = evaluate(profile(), base, input(base, draft), NOW);
        assertEquals(Verdict.ELIGIBLE, result.candidates().getFirst().hardChecks().hardVerdict()); assertEquals(List.of(ID), result.shortlist());
        assertEquals(PASS, finding(result, Criterion.AUTHENTICATION_SUCCESS_EVENTS).outcome());
        var check = finding(result, Criterion.AUDIT_LOG_RETENTION); assertEquals("RETENTION_MEETS_MINIMUM", check.reasonCode());
        assertEquals(30, check.evidence().documentedMinimumRetentionDays()); assertEquals(List.of("Configured pilot environment"), check.evidence().conditions());
        assertEquals(Instant.parse("2026-09-12T12:00:00Z"), check.evidence().observedAt());
        assertTrue(check.evidence().sourceUrl().getHost().endsWith(".invalid"));
        assertEquals(DecisionCanonicalizer.sha256(draft), result.binding().inputs().hardChecks().auditabilitySha256());
        assertFalse(result.sourceAuthorityVerified()); assertFalse(result.configurationVerified()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
    }
    @Test void insufficientOrContradictedReviewDoesNotBecomeConfirmedFailure() {
        for (var verdict : List.of(Assertion.INSUFFICIENT_EVIDENCE, Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM)) {
            var base = base(); var draft = supplement(); ((ObjectNode) draft.at("/options/0/facts/0")).put("support", "UNSUPPORTED");
            var input = input(base, draft);
            var assertions = input.assertions().stream().map(a -> a.criterion() == Criterion.AUTHENTICATION_SUCCESS_EVENTS
                    ? new CandidateAuditabilityInput.FactAssertion(a.optionId(), a.criterion(), a.claimSha256(), verdict) : a).toList();
            var result = evaluate(profile(), base, new CandidateAuditabilityInput(input.candidateSha256(), draft, assertions), NOW);
            assertEquals(Verdict.UNRESOLVED, result.candidates().getFirst().hardChecks().hardVerdict());
            assertEquals(UNKNOWN, finding(result, Criterion.AUTHENTICATION_SUCCESS_EVENTS).outcome());
        }
    }
    @Test void confirmedUnsupportedEventOrInsufficientRetentionExcludesBeforeScoring() {
        var base = base(); var draft = supplement(); var profile = profile();
        ((ObjectNode) profile.at("/security/auditabilityRequirements")).put("minimumRetentionDays", 31);
        var result = evaluate(profile, base, input(base, draft), NOW);
        assertEquals(Verdict.EXCLUDED, result.candidates().getFirst().hardChecks().hardVerdict());
        assertEquals("RETENTION_BELOW_MINIMUM", finding(result, Criterion.AUDIT_LOG_RETENTION).reasonCode()); assertNull(result.candidates().getFirst().score());
        ((ObjectNode) draft.at("/options/0/facts/0")).put("support", "UNSUPPORTED");
        result = evaluate(profile(), base, input(base, draft), NOW);
        assertEquals(FAIL, finding(result, Criterion.AUTHENTICATION_SUCCESS_EVENTS).outcome()); assertTrue(result.shortlist().isEmpty());
    }
    @Test void unknownSupportAndUnknownDocumentedDurationRemainUnresolved() {
        var base = base(); var draft = supplement(); ((ObjectNode) draft.at("/options/0/facts/0")).put("support", "UNKNOWN");
        var result = evaluate(profile(), base, input(base, draft), NOW);
        assertEquals("CAPABILITY_UNKNOWN", finding(result, Criterion.AUTHENTICATION_SUCCESS_EVENTS).reasonCode());
        draft = supplement(); ((ObjectNode) draft.at("/options/0/facts/1")).putNull("documentedMinimumRetentionDays");
        result = evaluate(profile(), base, input(base, draft), NOW);
        assertEquals("RETENTION_DURATION_UNKNOWN", finding(result, Criterion.AUDIT_LOG_RETENTION).reasonCode()); assertTrue(result.shortlist().isEmpty());
    }
    @Test void missingUnreviewedStaleAndFutureAuditEvidenceNeverRenewsObservations() {
        for (String problem : List.of("MISSING", "UNREVIEWED", "STALE", "FUTURE")) {
            var base = base(); var draft = supplement();
            if (problem.equals("MISSING")) ((tools.jackson.databind.node.ArrayNode) draft.at("/options/0/facts")).remove(1);
            if (problem.equals("STALE") || problem.equals("FUTURE")) ((ObjectNode) draft.at("/options/0/facts/1/evidence")).put("observedAt",
                    problem.equals("STALE") ? NOW.minusSeconds(90L * 86400 + 1).toString() : NOW.plusSeconds(1).toString());
            var input = input(base, draft); var assertions = input.assertions();
            if (problem.equals("UNREVIEWED")) assertions = assertions.stream().filter(a -> a.criterion() != Criterion.AUDIT_LOG_RETENTION).toList();
            var result = evaluate(profile(), base, new CandidateAuditabilityInput(input.candidateSha256(), draft, assertions), NOW);
            assertEquals("EVIDENCE_" + problem, finding(result, Criterion.AUDIT_LOG_RETENTION).reasonCode()); assertTrue(result.shortlist().isEmpty());
        }
    }
    @Test void exactNinetyDayBoundaryIsInclusiveAndLaterClockDoesNotChangeDigests() {
        var base = base(); var draft = supplement(); ((ObjectNode) draft.at("/options/0/facts/1/evidence")).put("observedAt", NOW.minusSeconds(90L * 86400).toString());
        var input = input(base, draft); var first = evaluate(profile(), base, input, NOW); var later = evaluate(profile(), base, input, NOW.plusNanos(1));
        assertEquals(PASS, finding(first, Criterion.AUDIT_LOG_RETENTION).outcome()); assertEquals(UNKNOWN, finding(later, Criterion.AUDIT_LOG_RETENTION).outcome());
        assertEquals(first.binding().inputs().hardChecks().auditabilitySha256(), later.binding().inputs().hardChecks().auditabilitySha256());
        assertEquals(first.binding().inputs().hardChecks().auditabilityAssertionsSha256(), later.binding().inputs().hardChecks().auditabilityAssertionsSha256());
    }
    @Test void inactiveCriteriaAndAuditPreferenceCannotEarnPointsOrResolveUnknownAuditIntent() {
        var base = base(); var draft = supplement(); var profile = profile();
        ((ObjectNode) profile.get("security")).put("auditability", "PREFERRED");
        var result = evaluate(profile, base, input(base, draft), NOW);
        assertEquals(NOT_APPLIED, finding(result, Criterion.AUDIT_LOG_RETENTION).outcome()); assertNull(result.candidates().getFirst().score());
        for (String criticality : List.of("UNKNOWN", "FORBIDDEN")) {
            ((ObjectNode) profile.get("security")).put("auditability", criticality);
            assertTrue(evaluate(profile, base, input(base, draft), NOW).shortlist().isEmpty());
        }
    }
    @Test void wrongBaseVersionScopeDuplicateOrForeignClaimsAreRejectedEvenWhenAuditNotRequired() {
        var base = base(); var profile = profile(); ((ObjectNode) profile.get("security")).put("auditability", "NOT_REQUIRED");
        for (String field : List.of("baseCatalogVersion", "baseContentSha256", "region")) {
            var draft = supplement();
            if (field.equals("region")) ((ObjectNode) draft.at("/options/0/scope")).put("region", "Wrong scope");
            else ((ObjectNode) draft).put(field, field.equals("baseContentSha256") ? "0".repeat(64) : "wrong-version");
            var input = input(base, draft); var immutableProfile = profile;
            assertThrows(IllegalArgumentException.class, () -> evaluate(immutableProfile, base, input, NOW));
        }
        var input = input(base, supplement()); var duplicated = new ArrayList<>(input.assertions()); duplicated.add(duplicated.getFirst());
        var immutableProfile = profile;
        assertThrows(IllegalArgumentException.class, () -> evaluate(immutableProfile, base, new CandidateAuditabilityInput(input.candidateSha256(), input.supplement(), duplicated), NOW));
        assertThrows(IllegalArgumentException.class, () -> evaluate(immutableProfile, base, new CandidateAuditabilityInput("0".repeat(64), input.supplement(), input.assertions()), NOW));
    }
    @Test void changedConditionsCannotReuseAnOldSupplementClaimDigestAndInputCannotBeMutated() {
        var base = base(); var draft = supplement(); var input = input(base, draft);
        ((ObjectNode) draft.at("/options/0/facts/0/evidence")).put("summary", "Changed source claim");
        assertNotEquals(draft, input.supplement()); var returned = input.supplement(); ((ObjectNode) returned).put("evidenceVersion", "changed");
        assertNotEquals(returned, input.supplement());
        assertThrows(IllegalArgumentException.class, () -> evaluate(profile(), base, new CandidateAuditabilityInput(input.candidateSha256(), draft, input.assertions()), NOW));
        var result = evaluate(profile(), base, input, NOW); assertEquals(result, evaluate(profile(), base, input, NOW));
    }
    @Test void sensitivityRetainsAuditInputsAndDoesNotRescueRequiredAuditFailure() {
        var base = base(); var profile = profile(); var audit = input(base, supplement());
        ((ObjectNode) profile.at("/protocols/federation")).put("OIDC", "PREFERRED"); ((ObjectNode) profile.get("provisioning")).put("scim", "PREFERRED");
        ((ObjectNode) profile.at("/security/auditabilityRequirements")).put("minimumRetentionDays", 31);
        var first = MAPPER.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"OIDC\",\"weight\":30},{\"capability\":\"SCIM\",\"weight\":70}]}");
        var second = MAPPER.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"OIDC\",\"weight\":70},{\"capability\":\"SCIM\",\"weight\":30}]}");
        var sensitivity = CandidatePreferenceScorer.compareWeights(profile, 6, base, supporting(base), audit, first, second, NOW);
        assertEquals(sensitivity.before().binding().hardChecks(), sensitivity.after().binding().hardChecks());
        assertNull(sensitivity.before().candidates().getFirst().score()); assertNull(sensitivity.after().candidates().getFirst().score());
    }
    @Test void malformedOrDeclaredReviewedFactsCannotBypassTheSupplementContract() {
        var base = base();
        for (String variant : List.of("authority", "emitter", "duration", "duplicate", "http-source")) {
            var draft = supplement();
            if (variant.equals("authority")) ((ObjectNode) draft.at("/options/0/facts/0")).put("evidenceStatus", "REVIEWED");
            if (variant.equals("emitter")) ((ObjectNode) draft.at("/options/0/facts/0")).put("emitter", "APPLICATION");
            if (variant.equals("duration")) ((ObjectNode) draft.at("/options/0/facts/1")).put("documentedMinimumRetentionDays", 36501);
            if (variant.equals("http-source")) ((ObjectNode) draft.at("/options/0/facts/0/evidence")).put("sourceUrl", "http://example.invalid/untrusted");
            if (variant.equals("duplicate")) ((tools.jackson.databind.node.ArrayNode) draft.at("/options/0/facts")).add(draft.at("/options/0/facts/0").deepCopy());
            assertThrows(RuntimeException.class, () -> evaluate(profile(), base, new CandidateAuditabilityInput(DecisionCanonicalizer.sha256(base), draft, List.of()), NOW));
        }
    }
}
