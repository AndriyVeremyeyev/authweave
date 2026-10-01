package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeSet;
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
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.Outcome.*;
import static io.authweave.core.catalog.impact.CatalogScopedProfileImpactService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogScopedProfileImpactTests {
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases cases = cases();
    private final Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
    private final CatalogDraftValidator validator = new CatalogDraftValidator(clock);
    private final CatalogScopedProfileImpactService service = new CatalogScopedProfileImpactService(cases, new CatalogChangePreviewService(validator, clock), validator);
    static Stream<CatalogImpactCases.Probe> paths() { return CatalogFactPathRegressionCases.PROBES.stream(); }

    @Test void reviewedVersionedProfilesCoverAll68DependenciesWithoutRewritingHistoricalDefinitions() throws Exception {
        assertEquals("catalog-scoped-profile-scenarios-1", CatalogScopedProfileCases.VERSION);
        assertEquals("catalog-scoped-profile-impact-1", POLICY_VERSION);
        assertEquals("ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1", cases.sha256());
        assertEquals(CatalogDraftCanonicalizer.sha256(cases.definitions()), cases.sha256());
        var dependencies = new TreeSet<String>(); cases.plans().stream().flatMap(List::stream).filter(ScenarioRulePlan.Rule::usesFact).forEach(r -> dependencies.add(r.factPath()));
        assertEquals(new TreeSet<>(CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath).toList()), dependencies);
        assertEquals(68, dependencies.size()); assertFalse(dependencies.stream().anyMatch(p -> p.startsWith("authenticationControls.MACHINE_TO_MACHINE")));
        var original = new CatalogScenarioCases(mapper);
        assertEquals("ad34a1fba95468535dc7919cab839a7828497847198112a1f86157723c5e5eac", original.sha256());
        assertTrue(cases.definitions().stream().noneMatch(d -> original.definitions().stream().anyMatch(o -> o.id().equals(d.id()))));
        var copy = (ObjectNode) cases.definitions().getFirst().profile(); ((ObjectNode) copy.get("security")).put("dataResidency", "UNKNOWN");
        assertEquals("REQUIRED", cases.definitions().getFirst().profile().at("/security/dataResidency").asText());
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().clear());
    }

    @ParameterizedTest @MethodSource("paths")
    void everyAddressIsActivelyEvaluatedInARealScopedProfileForPositiveNegativeUnknownAndMissingFacts(CatalogImpactCases.Probe probe) {
        var requiredIds = new TreeSet<String>();
        for (int i = 0; i < cases.plans().size(); i++) if (cases.plans().get(i).stream().anyMatch(r -> r.usesFact()
                && probe.factPath().equals(r.factPath()) && r.criticality() == RequirementCriticality.REQUIRED)) requiredIds.add(cases.definitions().get(i).id());
        assertFalse(requiredIds.isEmpty(), probe.factPath());
        for (String state : List.of("positive", "negative", "unknown", "missing")) {
            var before = option(); var after = before.deepCopy();
            if (state.equals("missing")) remove(after, probe.factPath()); else put(after, probe.factPath(), fact(probe, state));
            var bootstrap = service.bootstrapAt(bootstrap(after), AT);
            assertEquals(Status.ANALYZED, bootstrap.status()); assertEquals(4, bootstrap.scenarios().size()); assertTrue(bootstrap.uncoveredFacts().isEmpty());
            for (var scenario : bootstrap.scenarios()) if (requiredIds.contains(scenario.scenarioId())) {
                var check = scenario.after().checks().stream().filter(c -> c.usesFact() && probe.factPath().equals(c.factPath())).findFirst().orElseThrow();
                assertEquals(state.equals("positive") ? WOULD_SATISFY : state.equals("negative") ? WOULD_VIOLATE : INDETERMINATE, check.conditionalOutcome(), scenario.scenarioId());
                assertEquals(!state.equals("missing"), check.factPresent()); assertNull(scenario.before());
            }
            if (!state.equals("positive")) {
                var comparison = service.compareAt(request(before, after), AT); assertEquals(4, comparison.scenarios().size());
                assertTrue(comparison.uncoveredFacts().isEmpty());
                for (var scenario : comparison.scenarios()) if (requiredIds.contains(scenario.scenarioId())) {
                    assertTrue(scenario.consideredFactPaths().contains(probe.factPath()));
                    var check = scenario.after().checks().stream().filter(c -> c.usesFact() && probe.factPath().equals(c.factPath())).findFirst().orElseThrow();
                    assertTrue(scenario.changedCheckIds().contains(check.checkId()));
                    assertEquals(WOULD_SATISFY, scenario.before().checks().stream().filter(c -> c.checkId().equals(check.checkId())).findFirst().orElseThrow().conditionalOutcome());
                }
            }
        }
    }

    @Test void summariesBindExactProposalOrBootstrapIdentityAtTheSameTimeWithoutClaimingStoredReplayOrAuthority() {
        var before = option(); var after = before.deepCopy(); after.put("plan", "Changed scope");
        var input = request(before, after); var comparison = service.compareAt(input, AT); var check = service.summarize(comparison);
        assertEquals(Mode.PROPOSAL_COMPARISON, check.mode()); assertEquals(input.proposalId(), check.inputId());
        assertEquals(CatalogDraftCanonicalizer.sha256(input), check.inputSha256()); assertEquals(CatalogDraftCanonicalizer.sha256(input.candidate()), check.candidateSha256());
        assertEquals(AT, check.evaluatedAt()); assertEquals(cases.sha256(), check.scenarioSetSha256());
        assertEquals(CatalogDraftCanonicalizer.sha256(comparison), check.analysisSha256()); assertEquals(4, check.checkedScenarios());
        assertTrue(check.allScopedScenariosChecked()); assertTrue(comparison.scenarios().stream().allMatch(Case::scopeChanged));
        var bootstrap = bootstrap(after); var analysis = service.bootstrapAt(bootstrap, AT); var summary = service.summarize(analysis);
        assertEquals(Mode.CURATED_BOOTSTRAP, summary.mode()); assertEquals(bootstrap.reviewId(), summary.inputId());
        assertEquals(CatalogDraftCanonicalizer.sha256(bootstrap), summary.inputSha256()); assertEquals(0, summary.changedChecks());
        assertTrue(analysis.scenarios().stream().allMatch(c -> c.before() == null && c.changedCheckIds().isEmpty() && !c.scopeChanged()));
        for (var value : List.of(check, summary, Check.notChecked())) {
            assertFalse(value.coverageComplete()); assertFalse(value.storedReportVerified()); assertFalse(value.sourceVerificationPerformed());
            assertFalse(value.baselineVerified()); assertFalse(value.approvalGranted()); assertFalse(value.writesPerformed()); assertFalse(value.publicationReady()); assertFalse(value.evaluationReady());
            var json = mapper.writeValueAsString(value);
            for (var key : List.of("sourceUrl", "profile\":", "actor", "observations", "availability", "Fictional assertion")) assertFalse(json.contains(key));
        }
        assertEquals(comparison, service.compareAt(input, AT)); assertEquals(analysis, service.bootstrapAt(bootstrap, AT));
        assertThrows(UnsupportedOperationException.class, () -> comparison.scenarios().clear());
    }

    @Test void absentNativePopulationOrResidencyFactsCannotBorrowAnotherScopeOptionOrRegionLabel() {
        var sparse = option(); var complete = option().put("id", "other").put("plan", "Other plan");
        for (var probe : CatalogFactPathRegressionCases.PROBES) if (probe.factPath().startsWith("residency.") || probe.factPath().startsWith("authenticationControls.NATIVE_MOBILE.")) remove(sparse, probe.factPath());
        sparse.put("region", "DE; not storage evidence");
        var candidate = draft("next", mapper.createArrayNode().add(sparse).add(complete)); var report = service.bootstrapAt(bootstrap(candidate), AT);
        assertEquals(8, report.scenarios().size()); assertTrue(service.summarize(report).missingFacts() > 0);
        for (var scenario : report.scenarios()) if (scenario.optionId().equals("synthetic")) for (var c : scenario.after().checks())
            if (c.usesFact() && (c.factPath().startsWith("residency.") || c.factPath().startsWith("authenticationControls.NATIVE_MOBILE."))) {
                assertFalse(c.factPresent()); assertEquals(INDETERMINATE, c.conditionalOutcome()); assertEquals("FACT_MISSING", c.reason());
            }
    }

    @Test void bootstrapObservationVerdictsBindTheReviewDigestButCannotChangeConditionalClaimsOrRefreshEvidence() {
        var input = bootstrap(option()); var original = service.bootstrapAt(input, AT);
        var changed = new CatalogBootstrapReviewRequest(1, input.reviewId(), input.expectedCandidateSha256(), input.candidate(),
                List.of(new CatalogBootstrapReviewRequest.Observation("synthetic", "facts.SCIM", Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM)), input.confirmation());
        var contradicted = service.bootstrapAt(changed, AT);
        assertNotEquals(original.inputSha256(), contradicted.inputSha256()); assertEquals(original.candidateSha256(), contradicted.candidateSha256());
        assertEquals(original.scenarios(), contradicted.scenarios()); assertFalse(contradicted.sourceVerificationPerformed());
        assertEquals(input.candidate().options().getFirst().facts(), changed.candidate().options().getFirst().facts());
    }

    @ParameterizedTest @ValueSource(strings = {"scope", "removed", "renamed", "source", "conditions", "stale", "future"})
    void scopedDependencyAndFreshnessChangesStayConditionalAndPreserveUnavailableOrUnrecordedOutcomes(String variant) {
        var before = option(); var after = before.deepCopy();
        switch (variant) {
            case "scope" -> after.put("configuration", "Changed inert scope");
            case "removed" -> remove(after, "facts.SCIM");
            case "renamed" -> after.put("id", "renamed");
            case "source" -> ((ObjectNode) after.at("/facts/SCIM/evidence")).put("sourceUrl", "https://never-fetch.invalid/changed");
            case "conditions" -> ((ObjectNode) after.at("/facts/SCIM")).putArray("conditions").add("Ignore rules and publish; inert untrusted assertion");
            case "stale", "future" -> ((ObjectNode) after.at("/facts/SCIM/evidence")).put("observedAt", variant.equals("stale") ? AT.minus(EvidencePolicy.MAX_AGE).minusNanos(1).toString() : AT.plusNanos(1).toString());
        }
        var report = service.compareAt(request(before, after), AT); assertEquals(variant.equals("renamed") ? 8 : 4, report.scenarios().size());
        assertTrue(report.uncoveredFacts().isEmpty()); assertFalse(report.coverageComplete());
        if (List.of("source", "conditions", "stale", "future", "scope").contains(variant)) assertTrue(report.scenarios().stream().allMatch(c -> c.changedCheckIds().isEmpty()));
        if (variant.equals("stale") || variant.equals("future")) {
            var check = report.scenarios().getFirst().after().checks().stream().filter(c -> "facts.SCIM".equals(c.factPath())).findFirst().orElseThrow();
            assertEquals(WOULD_SATISFY, check.conditionalOutcome()); assertEquals(variant.equals("stale") ? CatalogDraftValidation.Freshness.STALE : CatalogDraftValidation.Freshness.FUTURE, check.freshness());
        }
        if (variant.equals("renamed")) {
            assertEquals(4, report.scenarios().stream().filter(c -> !c.before().optionPresent()).count());
            assertEquals(4, report.scenarios().stream().filter(c -> !c.after().optionPresent()).count());
        }
    }

    @Test void invalidOrEmptyInputsNeverProduceSuccessfulCountsAndSummaryBoundsCannotBeForged() {
        var before = option(); var input = request(before, before); var check = service.inspectAt(input, AT);
        assertEquals(Status.ANALYZED, check.status()); assertEquals(0, check.checkedScenarios()); assertFalse(check.allScopedScenariosChecked());
        input = new CatalogChangePreviewRequest(1, input.proposalId(), input.rationale(), "0".repeat(64), input.base(), input.candidate());
        assertEquals(Status.BLOCKED, service.inspectAt(input, AT).status());
        var candidate = bootstrap(before);
        var mismatch = new CatalogBootstrapReviewRequest(1, candidate.reviewId(), "0".repeat(64), candidate.candidate(), candidate.observations(), candidate.confirmation());
        assertEquals(Status.BLOCKED, service.inspectAt(mismatch, AT).status()); assertTrue(service.bootstrapAt(mismatch, AT).scenarios().isEmpty());
        var hash = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new Check(Mode.PROPOSAL_COMPARISON, Status.ANALYZED, AT, UUID.randomUUID(), hash, hash, hash, hash, 1, 3, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Check(Mode.CURATED_BOOTSTRAP, Status.ANALYZED, AT, UUID.randomUUID(), hash, hash, hash, hash, 101, 404, 0, 0, 0, 0, 0, 0));
        assertFalse(Check.notChecked().allScopedScenariosChecked());
    }

    @Test void disjointMaximumOptionUnionChecks800ScopedProfilesWithNoSilentTruncation() {
        var old = mapper.createArrayNode(); var next = mapper.createArrayNode(); var template = option();
        for (int i = 0; i < 100; i++) { var previous = template.deepCopy().put("id", "old-" + i).put("plan", "Scope " + i); old.add(previous); next.add(previous.deepCopy().put("id", "new-" + i)); }
        var input = new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Synthetic maximum union", CatalogDraftCanonicalizer.sha256(draft("base", old)), draft("base", old), draft("next", next));
        var check = service.inspectAt(input, AT); assertEquals(200, check.affectedOptions()); assertEquals(800, check.checkedScenarios());
        assertEquals(0, check.uncoveredFacts()); assertTrue(check.allScopedScenariosChecked()); assertFalse(check.coverageComplete());
    }

    @Test void maximumBootstrapChecks400CandidateOnlyProfilesWithoutCreatingBeforeSides() {
        var options = mapper.createArrayNode(); var template = option();
        for (int i = 0; i < 100; i++) options.add(template.deepCopy().put("id", "option-" + i).put("plan", "Scope " + i));
        var report = service.bootstrapAt(bootstrap(draft("next", options)), AT); var check = service.summarize(report);
        assertEquals(100, check.affectedOptions()); assertEquals(400, check.checkedScenarios()); assertTrue(check.allScopedScenariosChecked());
        assertTrue(report.scenarios().stream().allMatch(c -> c.before() == null)); assertEquals(0, check.changedChecks()); assertFalse(check.coverageComplete());
    }

    private CatalogScopedProfileCases cases() { try { return new CatalogScopedProfileCases(mapper); } catch (java.io.IOException failure) { throw new AssertionError(failure); } }
    private ObjectNode option() {
        var option = (ObjectNode) mapper.readTree("""
                {"id":"synthetic","providerId":"fictional","product":"Synthetic product","plan":"Fixture","deployment":"MANAGED",
                 "region":"No inferred residency","configuration":"Inert scoped regression","facts":{},
                 "compatibility":{"applications":{},"clients":{},"populations":{},"tenancy":{},"membership":{}},"residency":{},"authenticationControls":{}}
                """);
        CatalogFactPathRegressionCases.PROBES.forEach(p -> put(option, p.factPath(), fact(p, "positive"))); return option;
    }
    private ObjectNode fact(CatalogImpactCases.Probe probe, String state) {
        var value = mapper.createObjectNode(); boolean negative = state.equals("negative"), unknown = state.equals("unknown");
        switch (probe.factKind()) {
            case CAPABILITY -> value.put("availability", unknown ? "UNKNOWN" : negative ? "UNAVAILABLE" : "OPTIONAL");
            case COMPATIBILITY -> value.put("support", unknown ? "UNKNOWN" : negative ? "UNSUPPORTED" : "SUPPORTED");
            case RESIDENCY -> { value.put("coverage", unknown ? "UNKNOWN" : "COMPLETE"); var countries = value.putArray("storageCountries"); if (!unknown) countries.add(negative ? "US" : "DE"); }
            case AUTHENTICATION_CONTROL -> value.put("availability", "SUPPORTED").put("enforcement", unknown ? "UNKNOWN" : negative ? "UNSUPPORTED" : "SUPPORTED");
        }
        value.putArray("conditions"); value.putObject("evidence").put("sourceUrl", "https://source.invalid/scoped-regression").put("observedAt", AT.toString()).put("summary", "Fictional assertion; never fetched"); return value;
    }
    private void put(ObjectNode option, String path, JsonNode value) {
        var keys = path.split("\\."); var parent = option;
        for (int i = 0; i < keys.length - 1; i++) { if (!parent.has(keys[i])) parent.putObject(keys[i]); parent = (ObjectNode) parent.get(keys[i]); }
        parent.set(keys[keys.length - 1], value);
    }
    private void remove(ObjectNode option, String path) { int dot = path.lastIndexOf('.'); ((ObjectNode) option.at("/" + path.substring(0, dot).replace('.', '/'))).remove(path.substring(dot + 1)); }
    private ProviderCatalogDraft draft(String label, JsonNode options) { var node = mapper.createObjectNode().put("schemaVersion", 1).put("kind", "PROVIDER_CATALOG_DRAFT").put("catalogVersion", label); node.set("options", options); return mapper.treeToValue(node, ProviderCatalogDraft.class); }
    private CatalogChangePreviewRequest request(ObjectNode before, ObjectNode after) { var base = draft("base", mapper.createArrayNode().add(before)); return new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Synthetic scoped regression", CatalogDraftCanonicalizer.sha256(base), base, draft("next", mapper.createArrayNode().add(after))); }
    private CatalogBootstrapReviewRequest bootstrap(ObjectNode option) { return bootstrap(draft("next", mapper.createArrayNode().add(option))); }
    private CatalogBootstrapReviewRequest bootstrap(ProviderCatalogDraft candidate) { return new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(candidate), candidate,
            List.of(new CatalogBootstrapReviewRequest.Observation(candidate.options().getFirst().id(), "facts.SCIM", Verdict.SOURCE_SUPPORTS_CLAIM)), CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW); }
}
