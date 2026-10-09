package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.ProviderCatalog.Capability;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateAuditabilityInputTests.*;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full composed-rule changes on fictional .invalid hypotheses, not real catalog approvals or golden acceptance. */
class CandidateDecisionImpactEvaluatorTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant AT = Instant.parse("2026-10-09T17:00:00Z");
    private static CandidateDecisionImpactEvaluator.Snapshot snapshot(JsonNode base, JsonNode audit) {
        if (audit != null) {
            audit = audit.deepCopy(); ((ObjectNode) audit).put("baseCatalogVersion", base.get("catalogVersion").asText());
            ((ObjectNode) audit).put("baseContentSha256", CatalogDraftCanonicalizer.sha256(MAPPER.treeToValue(base, ProviderCatalogDraft.class)));
        }
        return new CandidateDecisionImpactEvaluator.Snapshot(base, supporting(base), audit == null ? null : input(base, audit));
    }
    private static CandidateDecisionImpactEvaluator.Result compare(JsonNode p, JsonNode w, JsonNode before, JsonNode after, JsonNode a, JsonNode b) {
        return CandidateDecisionImpactEvaluator.evaluate(p, 6, w, snapshot(before, a), snapshot(after, b), AT);
    }
    private static CandidateDecisionImpactEvaluator.Result compare(JsonNode before, JsonNode after) { return compare(profile(), weights(), before, after, supplement(), supplement()); }

    @Test void sameSnapshotReplaysIdenticallyAndNeverGrantsAuthority() {
        var base = base(); var report = compare(base, base); assertEquals(report, compare(base, base));
        assertTrue(report.deltas().isEmpty()); assertFalse(report.candidateInputsChanged()); assertFalse(report.decisionOutcomesChanged());
        assertFalse(report.resultDetailsChanged()); assertFalse(report.coverageComplete()); assertFalse(report.approvalGranted());
        assertFalse(report.sourceVerificationPerformed()); assertFalse(report.publicationReady()); assertFalse(report.writesPerformed());
        assertEquals(report.beforeResultSha256(), report.afterResultSha256());
        assertEquals(AT, report.before().binding().inputs().hardChecks().evaluatedAt());
        assertEquals(report.before().binding().inputs().weightsSha256(), report.after().binding().inputs().weightsSha256());
    }
    @ParameterizedTest @EnumSource(Capability.class)
    void everyDeclaredCapabilityRecomputesRequiredFailure(Capability capability) {
        var p = profile(); var path = Map.of(Capability.OIDC, "/protocols/federation/OIDC", Capability.SAML, "/protocols/federation/SAML",
                Capability.OAUTH2_APIS, "/protocols/oauth2ProtectedApis", Capability.SOCIAL_LOGIN, "/protocols/socialLogin",
                Capability.ENTERPRISE_SSO, "/protocols/enterpriseSingleSignOn", Capability.SCIM, "/provisioning/scim",
                Capability.JIT, "/provisioning/justInTimeProvisioning", Capability.GROUP_SYNC, "/provisioning/groupSynchronization",
                Capability.MFA, "/security/multiFactorAuthentication").get(capability);
        ((ObjectNode) p.at(path.substring(0, path.lastIndexOf('/')))).put(path.substring(path.lastIndexOf('/') + 1), "REQUIRED");
        var before = base(); var fact = before.at("/options/0/facts/OIDC").deepCopy();
        ((ObjectNode) before.at("/options/0/facts")).set(capability.name(), fact);
        var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/facts/" + capability)).put("availability", "UNAVAILABLE");
        var result = compare(p, weights(), before, after, supplement(), supplement());
        assertEquals(Verdict.ELIGIBLE, result.before().candidates().getFirst().hardChecks().hardVerdict());
        assertEquals(Verdict.EXCLUDED, result.after().candidates().getFirst().hardChecks().hardVerdict());
        assertTrue(result.decisionOutcomesChanged()); assertTrue(result.after().shortlist().isEmpty());
        assertTrue(result.deltas().stream().anyMatch(d -> d.path().contains("facts." + capability)));
    }
    @ParameterizedTest @ValueSource(strings = {"applications/B2B_SAAS", "clients/BROWSER", "populations/PARTNERS", "tenancy/MULTI_TENANT_ORGANIZATIONS", "membership/SINGLE_ORGANIZATION_PER_USER"})
    void allContextFamiliesAffectTheWholeDecision(String path) {
        var before = base(); var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/compatibility/" + path)).put("support", "UNSUPPORTED");
        var result = compare(before, after); assertTrue(result.decisionOutcomesChanged());
        assertEquals(Verdict.EXCLUDED, result.after().candidates().getFirst().hardChecks().hardVerdict());
    }
    @ParameterizedTest @ValueSource(strings = {"phishingResistance:PHISHING_RESISTANCE", "nonExportableKeys:NON_EXPORTABLE_KEYS", "stepUpAuthentication:STEP_UP_AUTHENTICATION"})
    void authenticationAvailabilityAndEnforcementAreNotLostInTheDelta(String path) {
        var keys = path.split(":"); var p = profile(); ((ObjectNode) p.at("/security/authenticationControls")).put(keys[0], "REQUIRED");
        var before = base(); var fact = before.at("/options/0/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE").deepCopy();
        ((ObjectNode) before.at("/options/0/authenticationControls/BROWSER/PARTNERS")).set(keys[1], fact);
        var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/authenticationControls/BROWSER/PARTNERS/" + keys[1])).put("enforcement", "UNSUPPORTED");
        var result = compare(p, weights(), before, after, supplement(), supplement());
        assertEquals(Verdict.ELIGIBLE, result.before().candidates().getFirst().hardChecks().hardVerdict());
        assertEquals(Verdict.EXCLUDED, result.after().candidates().getFirst().hardChecks().hardVerdict());
        assertTrue(result.deltas().stream().anyMatch(d -> d.path().contains(keys[1])));
    }
    @Test void residencyChangesUseTheCountryAllowlistNotTheOptionRegionLabel() {
        var p = profile(); ((ObjectNode) p.get("security")).put("dataResidency", "REQUIRED");
        ((ObjectNode) p.at("/security/dataResidencyDetails")).set("allowedCountries", MAPPER.createArrayNode().add("DE").add("FR"));
        ((ObjectNode) p.at("/security/dataResidencyDetails")).set("dataCategories", MAPPER.createArrayNode().add("USER_PROFILES"));
        var before = base(); var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/residency/USER_PROFILES")).set("storageCountries", MAPPER.createArrayNode().add("US"));
        var result = compare(p, weights(), before, after, supplement(), supplement()); assertTrue(result.decisionOutcomesChanged());
        assertEquals(Verdict.EXCLUDED, result.after().candidates().getFirst().hardChecks().hardVerdict());
    }
    @ParameterizedTest @EnumSource(Criterion.class)
    void everyAuditabilityCriterionParticipatesInCandidateChangeImpact(Criterion criterion) {
        var p = profile(); var requirements = (ObjectNode) p.at("/security/auditabilityRequirements");
        requirements.set("selectedCriteria", MAPPER.createArrayNode().add(criterion.name()));
        if (criterion == Criterion.AUDIT_LOG_RETENTION) requirements.put("minimumRetentionDays", 30); else requirements.putNull("minimumRetentionDays");
        var audit = supplement(); var fact = (ObjectNode) audit.at("/options/0/facts/0").deepCopy(); fact.put("criterion", criterion.name());
        if (criterion == Criterion.AUDIT_LOG_RETENTION) fact.put("documentedMinimumRetentionDays", 30);
        ((ObjectNode) audit.at("/options/0")).set("facts", MAPPER.createArrayNode().add(fact));
        var after = audit.deepCopy(); var changed = (ObjectNode) after.at("/options/0/facts/0"); changed.put("support", "UNSUPPORTED"); changed.putNull("documentedMinimumRetentionDays");
        var result = compare(p, weights(), base(), base(), audit, after); assertTrue(result.decisionOutcomesChanged());
        assertEquals(Verdict.EXCLUDED, result.after().candidates().getFirst().hardChecks().hardVerdict());
        assertTrue(result.deltas().stream().anyMatch(d -> d.path().contains("security.auditability|" + criterion)));
    }
    @Test void retentionReductionExcludesBeforeScoringAndChangesArchitectureOptionChecks() {
        var audit = supplement(); ((ObjectNode) audit.at("/options/0/facts/1")).put("documentedMinimumRetentionDays", 29);
        var result = compare(profile(), weights(), base(), base(), supplement(), audit);
        assertTrue(result.decisionOutcomesChanged()); assertNull(result.after().candidates().getFirst().score());
        assertTrue(result.deltas().stream().anyMatch(d -> d.path().startsWith("/architecture/")));
    }
    @Test void preferenceChangesShowScoresContributionsAndRankingWithoutChangingHardEligibility() {
        var p = profile(); ((ObjectNode) p.get("provisioning")).put("scim", "PREFERRED");
        var weights = MAPPER.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SCIM\",\"weight\":100}]}");
        var before = base(); var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
        var result = compare(p, weights, before, after, supplement(), supplement());
        assertEquals(Verdict.ELIGIBLE, result.after().candidates().getFirst().hardChecks().hardVerdict());
        assertEquals(100, result.before().candidates().getFirst().score().lowerBound()); assertEquals(0, result.after().candidates().getFirst().score().lowerBound());
        assertTrue(result.deltas().stream().anyMatch(d -> d.path().endsWith("/score")));
    }
    @Test void evidenceOnlyChangeIsVisibleWithoutInventingDecisionOutcomeChange() {
        var before = base(); var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/facts/SCIM/evidence")).put("summary", "Another fictional source paraphrase");
        var result = compare(before, after); assertTrue(result.candidateInputsChanged()); assertTrue(result.resultDetailsChanged());
        assertFalse(result.decisionOutcomesChanged()); assertTrue(result.deltas().stream().anyMatch(d -> d.path().contains("facts.SCIM")));
        var exposed = result.deltas().getFirst().after(); if (exposed.isObject()) ((ObjectNode) exposed).put("tampered", true);
        assertFalse(result.deltas().getFirst().after().has("tampered"));
    }
    @Test void catalogVersionOnlyChangeDoesNotMasqueradeAsDecisionImpact() {
        var p = profile(); ((ObjectNode) p.get("security")).put("auditability", "NOT_REQUIRED");
        var before = base(); var after = before.deepCopy(); ((ObjectNode) after).put("catalogVersion", "another-fictional-version");
        var result = compare(p, weights(), before, after, null, null); assertTrue(result.candidateInputsChanged());
        assertFalse(result.decisionOutcomesChanged()); assertFalse(result.resultDetailsChanged()); assertTrue(result.deltas().isEmpty());
        assertNotEquals(result.beforeResultSha256(), result.afterResultSha256());
    }
    @Test void optionOrderChangesOnlyBindingsNotKeyedDecisionDetails() {
        var p = profile(); ((ObjectNode) p.get("security")).put("auditability", "NOT_REQUIRED");
        var before = base(); var neighbour = (ObjectNode) before.at("/options/0").deepCopy();
        neighbour.put("id", "another-fictional-option"); neighbour.put("configuration", "Independent second configuration");
        ((tools.jackson.databind.node.ArrayNode) before.get("options")).add(neighbour);
        var after = before.deepCopy(); var options = (tools.jackson.databind.node.ArrayNode) after.get("options"); options.add(options.remove(0));
        var result = compare(p, weights(), before, after, null, null);
        assertTrue(result.candidateInputsChanged()); assertFalse(result.decisionOutcomesChanged()); assertFalse(result.resultDetailsChanged());
        assertTrue(result.deltas().isEmpty()); assertNotEquals(result.beforeResultSha256(), result.afterResultSha256());
    }
    @Test void architectureOnlyProtocolChangeIsComparedEvenWhenNotRequiredOrScored() {
        var p = profile(); ((ObjectNode) p.at("/protocols/federation")).put("OIDC", "NOT_REQUIRED");
        var before = base(); ((ObjectNode) before.at("/options/0/facts")).remove("OIDC");
        var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/facts")).set("SAML", base().at("/options/0/facts/OIDC"));
        var result = compare(p, weights(), before, after, supplement(), supplement());
        assertEquals(result.before().candidates().getFirst().hardChecks().hardVerdict(), result.after().candidates().getFirst().hardChecks().hardVerdict());
        assertTrue(result.decisionOutcomesChanged()); assertTrue(result.deltas().stream().anyMatch(d -> d.path().equals("/architecture/patterns/SERVER_SIDE_SESSION")));
    }
    @Test void staleFutureOrContradictedEvidenceCannotBeComparedAsConfirmedSupport() {
        for (var date : List.of(AT.minusSeconds(91L * 86400), AT.plusSeconds(1))) {
            var before = base(); var after = before.deepCopy(); ((ObjectNode) after.at("/options/0/facts/SCIM/evidence")).put("observedAt", date.toString());
            var result = compare(before, after); assertEquals(Verdict.UNRESOLVED, result.after().candidates().getFirst().hardChecks().hardVerdict());
        }
        var base = base(); var sources = supporting(base); var changed = new SourceAssertions(sources.candidateSha256(), sources.facts().stream()
                .map(a -> a.factPath().equals("facts.SCIM") ? new FactAssertion(a.optionId(), a.factPath(), a.claimSha256(), Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM) : a).toList());
        var result = CandidateDecisionImpactEvaluator.evaluate(profile(), 6, weights(), snapshot(base, supplement()),
                new CandidateDecisionImpactEvaluator.Snapshot(base, changed, input(base, supplement())), AT);
        assertEquals(Verdict.UNRESOLVED, result.after().candidates().getFirst().hardChecks().hardVerdict());
    }
    @Test void addedRemovedAndReorderedOptionsUseStableKeysWithoutBorrowingFacts() {
        var p = profile(); ((ObjectNode) p.get("security")).put("auditability", "NOT_REQUIRED");
        var before = base(); var after = before.deepCopy(); var neighbour = (ObjectNode) after.at("/options/0").deepCopy(); neighbour.put("id", "another-fictional-option");
        neighbour.put("configuration", "Another independent fictional configuration");
        ((ObjectNode) neighbour.get("facts")).remove("SCIM"); ((tools.jackson.databind.node.ArrayNode) after.get("options")).insert(0, neighbour);
        var result = compare(p, weights(), before, after, null, null);
        assertEquals(Verdict.UNRESOLVED, result.after().candidates().getFirst().hardChecks().hardVerdict());
        assertTrue(result.deltas().stream().anyMatch(d -> d.path().startsWith("/candidates/another-fictional-option/")));
        assertFalse(result.deltas().stream().anyMatch(d -> d.path().startsWith("/candidates/example-managed-eu/")));
        var reverse = compare(p, weights(), after, before, null, null); assertTrue(reverse.deltas().stream().anyMatch(d -> d.after().isNull()));
    }
    @Test void changedCandidateCannotReuseOldAssertionsOrForeignAuditScope() {
        var base = base(); var changed = base.deepCopy(); ((ObjectNode) changed.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
        assertThrows(IllegalArgumentException.class, () -> CandidateDecisionImpactEvaluator.evaluate(profile(), 6, weights(), snapshot(base, supplement()),
                new CandidateDecisionImpactEvaluator.Snapshot(changed, supporting(base), input(base, supplement())), AT));
    }
}
