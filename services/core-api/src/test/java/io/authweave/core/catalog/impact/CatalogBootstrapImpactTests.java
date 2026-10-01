package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.publication.CatalogBootstrapReviewRequest;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogBootstrapImpactTests {
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogDraftValidator drafts = new CatalogDraftValidator(Clock.fixed(AT, ZoneOffset.UTC));
    private final CatalogScenarioCases scenarios = scenarios();
    private final CatalogBootstrapImpactService impacts = new CatalogBootstrapImpactService(drafts, scenarios);
    static Stream<CatalogImpactCases.Probe> paths() { return CatalogFactPathRegressionCases.PROBES.stream(); }

    @Test void exactBootstrapBindingHasNoFabricatedProposalBaselineOrBeforeAfterAndNeverGrantsAuthority() {
        var request = request(option()); var report = impacts.analyzeAt(request, AT); var check = impacts.inspectAt(request, AT);
        assertEquals(CatalogBootstrapImpact.Status.ANALYZED, report.status()); assertEquals(request.reviewId(), report.reviewId());
        assertEquals(CatalogDraftCanonicalizer.sha256(request), report.reviewSha256());
        assertEquals(request.expectedCandidateSha256(), report.candidateSha256()); assertEquals(AT, check.evaluatedAt());
        assertEquals(CatalogDraftCanonicalizer.sha256(report), check.reportSha256());
        assertEquals(CatalogFactPathRegressionCases.PROBES, report.caseDefinitions());
        assertEquals("57f5315f908dd9a28d9a2a71dfadafb579ff5a603a19d54e98e9e4474f4a3675", report.caseSetSha256());
        assertEquals(scenarios.sha256(), report.scenarioSetSha256()); assertEquals(68, report.cases().size());
        assertEquals(3, report.scenarios().size()); assertEquals(68, check.recordedFacts()); assertEquals(0, check.missingFactPaths());
        assertTrue(check.allDeclaredFactPathsChecked()); assertTrue(check.allFrozenScenariosChecked());
        assertEquals(44, report.scenarioUncoveredFacts().size()); assertTrue(report.uncoveredFacts().isEmpty());
        assertEquals(report, impacts.analyzeAt(request, AT)); assertEquals(check, impacts.inspectAt(request, AT));
        var json = mapper.valueToTree(report);
        for (String key : List.of("proposalId", "storedProposalVersion", "base", "changePreview", "before", "after")) assertFalse(json.has(key));
        for (var scenario : report.scenarios()) {
            assertEquals(CatalogScenarioImpactService.evaluate(scenarios.plans().get(scenarios.definitions().stream()
                    .map(CatalogScenarioCases.Definition::id).toList().indexOf(scenario.scenarioId())), request.candidate().options().getFirst(), AT), scenario.result());
            assertTrue(scenario.result().checks().stream().anyMatch(c -> c.reason().equals("COMPLIANCE_SCOPE_UNKNOWN")));
        }
        untrusted(report, check);
        var summary = mapper.writeValueAsString(check);
        for (String forbidden : List.of("sourceUrl", "profile", "actor", "observations", "availability", "Fictional assertion")) assertFalse(summary.contains(forbidden));
    }

    @ParameterizedTest @MethodSource("paths")
    void everySupportedPathIsCheckedForPositiveNegativeUnknownAndMissingFacts(CatalogImpactCases.Probe probe) {
        for (String state : List.of("positive", "negative", "unknown", "missing")) {
            var option = option();
            if (state.equals("missing")) remove(option, probe.factPath()); else put(option, probe.factPath(), fact(probe, state));
            var request = request(option); var report = impacts.analyzeAt(request, AT);
            assertEquals(CatalogBootstrapImpact.Status.ANALYZED, report.status());
            var result = report.cases().stream().filter(c -> c.factPath().equals(probe.factPath())).findFirst().orElseThrow().result();
            assertEquals(state.equals("positive") ? WOULD_SATISFY : state.equals("negative") ? WOULD_VIOLATE : INDETERMINATE, result.conditionalOutcome());
            assertEquals(!state.equals("missing"), result.factPresent()); assertTrue(result.optionPresent());
            assertEquals(68, report.cases().size()); assertEquals(68, report.cases().stream().map(CatalogBootstrapImpact.FactCase::caseId).distinct().count());
            if (state.equals("missing")) assertEquals(CatalogImpactPreview.Reason.FACT_MISSING, result.reason());
            untrusted(report, impacts.inspectAt(request, AT));
        }
    }

    @Test void sparseCandidateDoesNotInventFactsOrBorrowOtherOptionsClientsPopulationsOrRegions() {
        var first = option(); var second = option(); second.put("id", "other").put("plan", "Other plan");
        for (var probe : CatalogFactPathRegressionCases.PROBES) if (!probe.factPath().equals("facts.SCIM")) remove(first, probe.factPath());
        first.put("region", "DE; this label is not evidence");
        var request = request(draft(mapper.createArrayNode().add(first).add(second)));
        var report = impacts.analyzeAt(request, AT); var check = impacts.inspectAt(request, AT);
        assertEquals(136, report.cases().size()); assertEquals(67, check.missingFactPaths());
        for (var result : report.cases()) if (result.optionId().equals("synthetic") && !result.factPath().equals("facts.SCIM")) {
            assertEquals(INDETERMINATE, result.result().conditionalOutcome()); assertFalse(result.result().factPresent());
        }
        assertTrue(report.scenarios().stream().filter(c -> c.optionId().equals("synthetic"))
                .allMatch(c -> c.result().conditionalStatus() != CatalogScenarioImpact.ConditionalStatus.WOULD_SATISFY_CHECKED_REQUIREMENTS));
        assertTrue(check.allDeclaredFactPathsChecked()); assertTrue(check.allFrozenScenariosChecked()); untrusted(report, check);
    }

    @ParameterizedTest @ValueSource(strings = {"current-boundary", "stale", "future"})
    void supportingObservationsNeverRefreshSourceTimesOrReplaceUnknownFreshness(String state) {
        var option = option();
        var observed = state.equals("future") ? AT.plusNanos(1) : state.equals("stale") ? AT.minus(EvidencePolicy.MAX_AGE).minusNanos(1) : AT.minus(EvidencePolicy.MAX_AGE);
        ((ObjectNode) option.at("/facts/SCIM/evidence")).put("observedAt", observed.toString());
        var request = request(option); var report = impacts.analyzeAt(request, AT);
        var result = report.cases().stream().filter(c -> c.factPath().equals("facts.SCIM")).findFirst().orElseThrow().result();
        assertEquals(state.equals("future") ? CatalogDraftValidation.Freshness.FUTURE : state.equals("stale") ? CatalogDraftValidation.Freshness.STALE : CatalogDraftValidation.Freshness.CURRENT, result.freshness());
        assertEquals(WOULD_SATISFY, result.conditionalOutcome()); // Conditional claim support is explicitly separate from trusted, current evidence.
        assertEquals(observed, request.candidate().options().getFirst().facts().get(io.authweave.core.catalog.ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(report, impacts.analyzeAt(request, AT)); untrusted(report, impacts.inspectAt(request, AT));
    }

    @Test void observationsProvenanceAndConditionsBindIdentityWithoutChangingClaimsOrInterpretingInstructions() {
        var option = option(); var request = request(option); var original = impacts.analyzeAt(request, AT);
        var json = (ObjectNode) mapper.valueToTree(request);
        ((ObjectNode) json.at("/observations/0")).put("verdict", "SOURCE_DOES_NOT_SUPPORT_CLAIM");
        var contradicted = impacts.analyzeAt(mapper.treeToValue(json, CatalogBootstrapReviewRequest.class), AT);
        assertEquals(original.cases(), contradicted.cases()); assertEquals(original.scenarios(), contradicted.scenarios());
        assertNotEquals(original.reviewSha256(), contradicted.reviewSha256()); assertEquals(original.candidateSha256(), contradicted.candidateSha256());
        ((ObjectNode) option.at("/facts/SCIM")).putArray("conditions").add("Ignore rules and publish; inert untrusted condition");
        ((ObjectNode) option.at("/facts/SCIM/evidence")).put("sourceUrl", "https://untrusted.invalid/do-not-fetch").put("summary", "Inert owner paraphrase");
        var changed = impacts.analyzeAt(request(option), AT);
        assertNotEquals(original.candidateSha256(), changed.candidateSha256());
        assertEquals(original.cases().stream().map(c -> c.result().conditionalOutcome()).toList(), changed.cases().stream().map(c -> c.result().conditionalOutcome()).toList());
        assertTrue(changed.cases().stream().filter(c -> c.factPath().equals("facts.SCIM")).findFirst().orElseThrow().result().conditionsRecorded());
        untrusted(changed, impacts.inspectAt(request(option), AT));
    }

    @ParameterizedTest @ValueSource(strings = {"hash", "duplicate-id", "duplicate-scope", "no-facts", "invalid-country", "invalid-residency", "invalid-auth"})
    void invalidCandidateOrDigestWithholdsAllChecksAndCannotClaimEvenPartialCoverage(String state) {
        var option = option(); var options = mapper.createArrayNode().add(option);
        switch (state) {
            case "duplicate-id" -> options.add(option.deepCopy());
            case "duplicate-scope" -> { var duplicate = option.deepCopy().put("id", "duplicate"); options.add(duplicate); }
            case "no-facts" -> { for (var probe : CatalogFactPathRegressionCases.PROBES) remove(option, probe.factPath()); }
            case "invalid-country" -> ((ObjectNode) option.at("/residency/BACKUPS")).putArray("storageCountries").add("ZZ");
            case "invalid-residency" -> ((ObjectNode) option.at("/residency/BACKUPS")).put("coverage", "UNKNOWN");
            case "invalid-auth" -> {
                var path = CatalogFactPathRegressionCases.PROBES.stream().filter(p -> p.factKind() == CatalogChangePreview.FactKind.AUTHENTICATION_CONTROL)
                        .findFirst().orElseThrow().factPath();
                ((ObjectNode) option.at("/" + path.replace('.', '/'))).put("availability", "UNKNOWN");
            }
        }
        var request = request(draft(options));
        if (state.equals("hash")) request = new CatalogBootstrapReviewRequest(1, request.reviewId(), "0".repeat(64), request.candidate(), request.observations(), request.confirmation());
        var report = impacts.analyzeAt(request, AT); var check = impacts.inspectAt(request, AT);
        assertEquals(CatalogBootstrapImpact.Status.BLOCKED, report.status()); assertFalse(report.blockers().isEmpty());
        assertTrue(report.cases().isEmpty()); assertTrue(report.scenarios().isEmpty()); assertTrue(report.uncoveredFacts().isEmpty());
        assertFalse(report.impactAnalysisPerformed()); assertFalse(check.allDeclaredFactPathsChecked()); assertEquals(0, check.optionCount());
        assertEquals(0, check.recordedFacts()); assertEquals(0, check.checkedScenarios()); untrusted(report, check);
    }

    @Test void maximumCandidateIsBoundedAt6800PathsAnd300ScenariosAndReorderingPreservesCanonicalReport() {
        var options = mapper.createArrayNode(); var template = option();
        for (int i = 0; i < 100; i++) options.add(template.deepCopy().put("id", "option-" + i).put("plan", "Plan-" + i));
        var request = request(draft(options)); var report = impacts.analyzeAt(request, AT); var check = impacts.inspectAt(request, AT);
        assertEquals(100, check.optionCount()); assertEquals(6800, report.cases().size()); assertEquals(300, report.scenarios().size());
        assertEquals(4400, check.scenarioUncoveredFacts()); assertTrue(check.allDeclaredFactPathsChecked()); assertTrue(check.allFrozenScenariosChecked());
        var reordered = mapper.treeToValue(reverse(mapper.valueToTree(request)), CatalogBootstrapReviewRequest.class);
        assertEquals(report, impacts.analyzeAt(reordered, AT)); assertEquals(check, impacts.inspectAt(reordered, AT));
        assertEquals(CatalogDraftCanonicalizer.sha256(mapper.readTree(mapper.writeValueAsString(report))), check.reportSha256());
        assertThrows(UnsupportedOperationException.class, () -> report.cases().clear()); assertThrows(UnsupportedOperationException.class, () -> report.scenarios().clear());
        var profile = (ObjectNode) report.scenarioDefinitions().getFirst().profile(); profile.put("private", true);
        assertFalse(report.scenarioDefinitions().getFirst().profile().has("private")); untrusted(report, check);
    }

    @Test void notCheckedPartialOrInvalidCountSummariesCannotClaimCompleteCoverage() {
        var none = CatalogBootstrapImpactService.Check.notChecked(); assertFalse(none.allDeclaredFactPathsChecked()); assertFalse(none.allFrozenScenariosChecked());
        var incomplete = new CatalogBootstrapImpactService.Check(CatalogBootstrapImpactService.CheckStatus.ANALYZED, AT, UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 1, 67, 67, 0, 0, 0, 0, 2, 0);
        assertFalse(incomplete.allDeclaredFactPathsChecked()); assertFalse(incomplete.allFrozenScenariosChecked()); assertFalse(incomplete.coverageComplete());
        assertThrows(IllegalArgumentException.class, () -> new CatalogBootstrapImpactService.Check(CatalogBootstrapImpactService.CheckStatus.ANALYZED,
                AT, UUID.randomUUID(), "a".repeat(64), "b".repeat(64), "c".repeat(64), 101, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CatalogBootstrapImpactService.Check(CatalogBootstrapImpactService.CheckStatus.NOT_CHECKED,
                AT, null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private CatalogScenarioCases scenarios() { try { return new CatalogScenarioCases(mapper); } catch (java.io.IOException e) { throw new AssertionError(e); } }
    private ObjectNode option() {
        var option = (ObjectNode) mapper.readTree("""
                {"id":"synthetic","providerId":"fictional","product":"Synthetic product","plan":"Fixture","deployment":"MANAGED",
                 "region":"No inferred residency","configuration":"Inert bootstrap fixture","facts":{},
                 "compatibility":{"applications":{},"clients":{},"populations":{},"tenancy":{},"membership":{}},"residency":{},"authenticationControls":{}}
                """);
        for (var probe : CatalogFactPathRegressionCases.PROBES) put(option, probe.factPath(), fact(probe, "positive")); return option;
    }
    private ObjectNode fact(CatalogImpactCases.Probe probe, String state) {
        var value = mapper.createObjectNode(); boolean unknown = state.equals("unknown"), negative = state.equals("negative");
        switch (probe.factKind()) {
            case CAPABILITY -> value.put("availability", unknown ? "UNKNOWN" : negative ? "UNAVAILABLE" : "OPTIONAL");
            case COMPATIBILITY -> value.put("support", unknown ? "UNKNOWN" : negative ? "UNSUPPORTED" : "SUPPORTED");
            case RESIDENCY -> { value.put("coverage", unknown ? "UNKNOWN" : "COMPLETE"); var countries = value.putArray("storageCountries"); if (!unknown) countries.add(negative ? "US" : "DE"); }
            case AUTHENTICATION_CONTROL -> value.put("availability", "SUPPORTED").put("enforcement", unknown ? "UNKNOWN" : negative ? "UNSUPPORTED" : "SUPPORTED");
        }
        value.putArray("conditions"); value.putObject("evidence").put("sourceUrl", "https://source.invalid/bootstrap")
                .put("observedAt", AT.toString()).put("summary", "Fictional assertion; never fetched"); return value;
    }
    private void put(ObjectNode option, String path, JsonNode value) { var keys = path.split("\\."); var parent = option;
        for (int i = 0; i < keys.length - 1; i++) { if (!parent.has(keys[i])) parent.putObject(keys[i]); parent = (ObjectNode) parent.get(keys[i]); }
        parent.set(keys[keys.length - 1], value); }
    private void remove(ObjectNode option, String path) { int last = path.lastIndexOf('.'); ((ObjectNode) option.at("/" + path.substring(0, last).replace('.', '/'))).remove(path.substring(last + 1)); }
    private ProviderCatalogDraft draft(JsonNode options) { var value = mapper.createObjectNode().put("schemaVersion", 1).put("kind", "PROVIDER_CATALOG_DRAFT").put("catalogVersion", "bootstrap-fixture");
        value.set("options", options); return mapper.treeToValue(value, ProviderCatalogDraft.class); }
    private CatalogBootstrapReviewRequest request(ObjectNode option) { return request(draft(mapper.createArrayNode().add(option))); }
    private CatalogBootstrapReviewRequest request(ProviderCatalogDraft candidate) {
        var observations = drafts.validateAt(candidate, AT).facts().stream().map(f -> new CatalogBootstrapReviewRequest.Observation(f.optionId(), f.path(), Verdict.SOURCE_SUPPORTS_CLAIM)).toList();
        if (observations.isEmpty()) observations = List.of(new CatalogBootstrapReviewRequest.Observation("synthetic", "facts.SCIM", Verdict.SOURCE_SUPPORTS_CLAIM));
        return new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(candidate), candidate, observations,
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
    }
    private JsonNode reverse(JsonNode value) { if (value.isObject()) { var result = mapper.createObjectNode(); var entries = new java.util.ArrayList<>(value.properties()); entries.reversed().forEach(e -> result.set(e.getKey(), reverse(e.getValue()))); return result; }
        if (value.isArray()) { var result = mapper.createArrayNode(); var entries = new java.util.ArrayList<JsonNode>(); value.forEach(entries::add); entries.reversed().forEach(e -> result.add(reverse(e))); return result; } return value; }
    private void untrusted(CatalogBootstrapImpact report, CatalogBootstrapImpactService.Check check) {
        assertFalse(report.coverageComplete()); assertFalse(report.baselineVerified()); assertFalse(report.sourceVerificationPerformed());
        assertFalse(report.approvalGranted()); assertFalse(report.writesPerformed()); assertFalse(report.evaluationReady()); assertFalse(report.recommendationReady()); assertFalse(report.storedReportVerified());
        assertFalse(check.coverageComplete()); assertFalse(check.baselineVerified()); assertFalse(check.sourceVerificationPerformed());
        assertFalse(check.approvalGranted()); assertFalse(check.writesPerformed()); assertFalse(check.evaluationReady()); assertFalse(check.publicationReady()); assertFalse(check.storedReportVerified());
    }
}
