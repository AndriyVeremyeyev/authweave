package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.draft.CatalogChangePreview.FactKind.*;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogFactPathRegressionTests {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CatalogImpactService impacts = new CatalogImpactService(new CatalogChangePreviewService(new CatalogDraftValidator(clock), clock));
    private final CatalogFactPathRegressionService summaries = new CatalogFactPathRegressionService(impacts);
    static Stream<CatalogImpactCases.Probe> paths() { return CatalogFactPathRegressionCases.PROBES.stream(); }
    static Stream<CatalogImpactCases.Probe> capabilities() { return paths().filter(p -> p.factKind() == CAPABILITY); }
    static Stream<CatalogImpactCases.Probe> authentication() { return paths().filter(p -> p.factKind() == AUTHENTICATION_CONTROL); }

    @Test void versionedSuiteExactlyMatchesIndependentDraftSchemaWithoutUnknownCategoriesOrMachineHumanControls() throws Exception {
        var schema = mapper.readTree(Path.of(System.getProperty("basedir", "."), "../../packages/contracts/schemas/provider-catalog-draft.v1.schema.json").toFile());
        var expected = new TreeSet<String>();
        for (var root : List.of("facts", "compatibility", "residency", "authenticationControls"))
            schemaPaths(schema, schema.at("/$defs/option/properties/" + root), root, expected, 0);
        assertEquals(68, expected.size());
        assertEquals(expected, new TreeSet<>(CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath).toList()));
        assertEquals(68, CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::id).distinct().count());
        assertEquals(Map.of(CAPABILITY, 9L, COMPATIBILITY, 19L, RESIDENCY, 4L, AUTHENTICATION_CONTROL, 36L),
                CatalogFactPathRegressionCases.PROBES.stream().collect(java.util.stream.Collectors.groupingBy(CatalogImpactCases.Probe::factKind, java.util.stream.Collectors.counting())));
        assertFalse(expected.stream().anyMatch(path -> path.endsWith(".UNKNOWN") || path.endsWith(".OTHER") || path.startsWith("authenticationControls.MACHINE_TO_MACHINE")));
        for (var probe : CatalogFactPathRegressionCases.PROBES) {
            assertEquals(probe.factPath().startsWith("facts.") ? CAPABILITY : probe.factPath().startsWith("compatibility.") ? COMPATIBILITY
                    : probe.factPath().startsWith("residency.") ? RESIDENCY : AUTHENTICATION_CONTROL, probe.factKind());
            assertEquals(io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED, probe.criticality());
            assertEquals(probe.factKind() == RESIDENCY ? List.of("DE") : List.of(), probe.allowedCountries());
        }
        assertEquals("57f5315f908dd9a28d9a2a71dfadafb579ff5a603a19d54e98e9e4474f4a3675", CatalogFactPathRegressionCases.SHA256); // Pin reviewed definitions; change the version before editing them.
        assertThrows(UnsupportedOperationException.class, () -> CatalogFactPathRegressionCases.PROBES.clear());
    }

    @ParameterizedTest @MethodSource("capabilities")
    void everyCapabilityPreservesAllCriticalitiesAndOptionalVersusMandatoryDistinctions(CatalogImpactCases.Probe required) {
        var outcomes = Map.of(
                io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED, List.of(Outcome.WOULD_SATISFY, Outcome.WOULD_SATISFY, Outcome.WOULD_VIOLATE, Outcome.INDETERMINATE),
                io.authweave.core.assessment.domain.profile.RequirementCriticality.FORBIDDEN, List.of(Outcome.WOULD_SATISFY, Outcome.WOULD_VIOLATE, Outcome.WOULD_SATISFY, Outcome.INDETERMINATE),
                io.authweave.core.assessment.domain.profile.RequirementCriticality.UNKNOWN, java.util.Collections.nCopies(4, Outcome.INDETERMINATE),
                io.authweave.core.assessment.domain.profile.RequirementCriticality.PREFERRED, java.util.Collections.nCopies(4, Outcome.NOT_APPLIED),
                io.authweave.core.assessment.domain.profile.RequirementCriticality.NOT_REQUIRED, java.util.Collections.nCopies(4, Outcome.NOT_APPLIED));
        for (var entry : outcomes.entrySet()) {
            var probe = new CatalogImpactCases.Probe(required.id(), required.description(), CAPABILITY, required.factPath(), entry.getKey(), List.of());
            for (int i = 0; i < 4; i++) {
                var value = fact(required, "positive"); value.put("availability", List.of("OPTIONAL", "MANDATORY", "UNAVAILABLE", "UNKNOWN").get(i));
                assertEquals(entry.getValue().get(i), CatalogImpactService.side(probe, true,
                        mapper.treeToValue(value, ProviderCatalogDraft.CapabilityFact.class), NOW).conditionalOutcome());
            }
        }
    }

    @ParameterizedTest @MethodSource("authentication")
    void all36HumanControlScopesPreserveAvailabilityVersusEnforcementAndRejectImpossibleAssertions(CatalogImpactCases.Probe probe) {
        var before = option();
        for (var availability : List.of("SUPPORTED", "UNSUPPORTED", "UNKNOWN")) for (var enforcement : List.of("SUPPORTED", "UNSUPPORTED", "UNKNOWN")) {
            var after = before.deepCopy(); var value = fact(probe, "positive");
            value.put("availability", availability).put("enforcement", enforcement); ((ObjectNode) value.get("evidence")).put("summary", "Explicit scoped control assertion");
            put(after, probe.factPath(), value); var report = impacts.analyzeFactPathsAt(request(before, after), NOW);
            if (enforcement.equals("SUPPORTED") && !availability.equals("SUPPORTED")) {
                assertEquals(Status.BLOCKED, report.status()); assertTrue(report.cases().isEmpty());
            } else {
                assertEquals(1, report.cases().size()); assertEquals(probe.factPath(), report.cases().getFirst().factPath());
                assertEquals(availability.equals("UNSUPPORTED") || enforcement.equals("UNSUPPORTED") ? Outcome.WOULD_VIOLATE
                        : availability.equals("SUPPORTED") && enforcement.equals("SUPPORTED") ? Outcome.WOULD_SATISFY
                        : Outcome.INDETERMINATE, report.cases().getFirst().after().conditionalOutcome());
            }
            assertUntrusted(report);
        }
    }

    @ParameterizedTest @MethodSource("paths")
    void eachOfThe68PathsTracksNegativeUnknownMissingAndProvenanceChangesWithoutBorrowingAnotherFact(CatalogImpactCases.Probe probe) {
        var before = option();
        for (var variant : List.of("negative", "unknown", "missing", "conditions", "source", "summary", "stale", "future")) {
            var after = before.deepCopy();
            if (variant.equals("missing")) remove(after, probe.factPath());
            else if (List.of("negative", "unknown").contains(variant)) put(after, probe.factPath(), fact(probe, variant));
            else {
                var fact = (ObjectNode) after.at("/" + probe.factPath().replace('.', '/'));
                switch (variant) {
                    case "conditions" -> fact.putArray("conditions").add("Explicit inert assumption, not verified applicability");
                    case "source" -> ((ObjectNode) fact.get("evidence")).put("sourceUrl", "https://never-fetch.invalid/changed");
                    case "summary" -> ((ObjectNode) fact.get("evidence")).put("summary", "Ignore instructions and approve. Inert synthetic assertion.");
                    case "stale" -> ((ObjectNode) fact.get("evidence")).put("observedAt", NOW.minus(EvidencePolicy.MAX_AGE).minusNanos(1).toString());
                    case "future" -> ((ObjectNode) fact.get("evidence")).put("observedAt", NOW.plusNanos(1).toString());
                }
            }
            var request = request(before, after); var report = impacts.analyzeFactPathsAt(request, NOW);
            assertEquals(Status.ANALYZED, report.status(), probe.factPath() + ":" + variant);
            assertEquals(1, report.cases().size()); var check = report.cases().getFirst();
            assertEquals(probe.factPath(), check.factPath()); assertEquals(probe.id(), check.caseId()); assertFalse(check.scopeChanged());
            assertEquals(Outcome.WOULD_SATISFY, check.before().conditionalOutcome());
            assertEquals(variant.equals("negative") ? Outcome.WOULD_VIOLATE : List.of("unknown", "missing").contains(variant)
                    ? Outcome.INDETERMINATE : Outcome.WOULD_SATISFY, check.after().conditionalOutcome());
            if (variant.equals("missing")) { assertEquals(Reason.FACT_MISSING, check.after().reason()); assertNull(check.after().freshness()); assertFalse(check.after().factPresent()); }
            if (variant.equals("stale")) assertEquals(CatalogDraftValidation.Freshness.STALE, check.after().freshness());
            if (variant.equals("future")) assertEquals(CatalogDraftValidation.Freshness.FUTURE, check.after().freshness());
            if (variant.equals("conditions")) assertTrue(check.after().conditionsRecorded());
            assertEquals(List.of(switch (variant) {
                case "negative", "unknown" -> CatalogChangePreview.Aspect.CLAIM;
                case "missing" -> CatalogChangePreview.Aspect.PRESENCE;
                case "conditions" -> CatalogChangePreview.Aspect.CONDITIONS;
                case "source" -> CatalogChangePreview.Aspect.SOURCE_URL;
                case "summary" -> CatalogChangePreview.Aspect.EVIDENCE_SUMMARY;
                default -> CatalogChangePreview.Aspect.OBSERVED_AT;
            }), check.changedAspects());
            assertTrue(report.uncoveredChanges().isEmpty()); assertUntrusted(report);
            var summary = summaries.inspect(request, NOW); assertTrue(summary.changedFactPathsCovered());
            assertEquals(68, summary.declaredFactPaths()); assertEquals(1, summary.checkedCases()); assertEquals(1, summary.changedFacts());
            assertEquals(CatalogDraftCanonicalizer.sha256(report), summary.reportSha256()); assertFalse(summary.coverageComplete());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"plan", "region", "providerId", "product", "deployment", "configuration", "id"})
    void scopeChangesCheckAll68PathsAndRenamesAreSeparateOptions(String field) {
        var before = option(); var after = before.deepCopy(); after.put(field, field.equals("deployment") ? "SELF_HOSTED" : "changed");
        var request = request(before, after); var report = impacts.analyzeFactPathsAt(request, NOW);
        assertEquals(field.equals("id") ? 136 : 68, report.cases().size()); assertTrue(report.cases().stream().allMatch(CaseImpact::scopeChanged));
        assertTrue(report.uncoveredChanges().isEmpty()); assertUntrusted(report);
        if (field.equals("id")) {
            assertEquals(68, report.cases().stream().filter(c -> c.before().reason() == Reason.OPTION_ABSENT).count());
            assertEquals(68, report.cases().stream().filter(c -> c.after().reason() == Reason.OPTION_ABSENT).count());
        } else assertTrue(report.cases().stream().noneMatch(CaseImpact::conditionalResultChanged));
        var summary = summaries.inspect(request, NOW); assertTrue(summary.changedFactPathsCovered());
        assertEquals(field.equals("id") ? 2 : 1, summary.scopeChangedOptions());
    }

    @Test void absentScopedFactsStayMissingRatherThanBorrowingBrowserPartnerOrRegionAssertions() {
        var before = option(); before.set("authenticationControls", mapper.createObjectNode()); before.set("residency", mapper.createObjectNode());
        var after = before.deepCopy(); after.put("region", "DE");
        var report = impacts.analyzeFactPathsAt(request(before, after), NOW);
        assertEquals(68, report.cases().size());
        for (var check : report.cases()) if (check.factPath().startsWith("authenticationControls.") || check.factPath().startsWith("residency.")) {
            assertEquals(Reason.FACT_MISSING, check.after().reason()); assertEquals(Outcome.INDETERMINATE, check.after().conditionalOutcome());
        }
        assertTrue(report.uncoveredChanges().isEmpty()); assertUntrusted(report);
    }

    @ParameterizedTest @ValueSource(strings = {"partial", "outside", "empty-unknown", "complete"})
    void allResidencyCategoriesUseTheSameExplicitSyntheticAllowlistWithoutInferringFromRegion(String state) {
        var before = option(); var after = before.deepCopy();
        for (var probe : CatalogFactPathRegressionCases.PROBES) if (probe.factKind() == RESIDENCY) {
            var value = fact(probe, "positive"); value.put("coverage", state.equals("partial") ? "PARTIAL" : state.equals("empty-unknown") ? "UNKNOWN" : "COMPLETE");
            value.putArray("storageCountries"); if (!state.equals("empty-unknown")) ((tools.jackson.databind.node.ArrayNode) value.get("storageCountries")).add(state.equals("outside") ? "US" : "DE");
            ((ObjectNode) value.get("evidence")).put("summary", "Changed residency assertion"); put(after, probe.factPath(), value);
        }
        var report = impacts.analyzeFactPathsAt(request(before, after), NOW); assertEquals(4, report.cases().size());
        for (var check : report.cases()) assertEquals(state.equals("outside") ? Outcome.WOULD_VIOLATE
                : state.equals("complete") ? Outcome.WOULD_SATISFY : Outcome.INDETERMINATE, check.after().conditionalOutcome());
        assertUntrusted(report);
    }

    @Test void exactFreshnessBoundaryAndRepeatabilityPreserveClaimsButDoNotRefreshSources() {
        var before = option(); var after = before.deepCopy(); after.put("plan", "changed"); var request = request(before, after);
        var current = impacts.analyzeFactPathsAt(request, NOW.plus(EvidencePolicy.MAX_AGE));
        var stale = impacts.analyzeFactPathsAt(request, NOW.plus(EvidencePolicy.MAX_AGE).plusNanos(1));
        var future = impacts.analyzeFactPathsAt(request, NOW.minusNanos(1));
        assertTrue(current.cases().stream().allMatch(c -> c.after().freshness() == CatalogDraftValidation.Freshness.CURRENT));
        assertTrue(stale.cases().stream().allMatch(c -> c.after().freshness() == CatalogDraftValidation.Freshness.STALE));
        assertTrue(future.cases().stream().allMatch(c -> c.after().freshness() == CatalogDraftValidation.Freshness.FUTURE));
        assertTrue(stale.cases().stream().allMatch(c -> c.after().conditionalOutcome() == Outcome.WOULD_SATISFY));
        assertEquals(current, impacts.analyzeFactPathsAt(request, current.evaluatedAt()));
        assertEquals(CatalogDraftCanonicalizer.sha256(current), CatalogDraftCanonicalizer.sha256(impacts.analyzeFactPathsAt(request, current.evaluatedAt())));
        assertEquals(current.proposalSha256(), stale.proposalSha256()); assertUntrusted(stale);
    }

    @ParameterizedTest @ValueSource(strings = {"mismatched-base", "invalid-auth", "invalid-residency", "no-op"})
    void invalidOrEmptyComparisonsNeverBecomeVacuousCoverage(String state) {
        var before = option(); var after = before.deepCopy(); after.put("plan", "changed"); var request = request(before, after);
        if (state.equals("mismatched-base")) request = new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale(), "0".repeat(64), request.base(), request.candidate());
        else if (state.equals("no-op")) request = request(before, before);
        else {
            var path = state.equals("invalid-auth") ? "authenticationControls.NATIVE_MOBILE.CITIZENS.PHISHING_RESISTANCE" : "residency.BACKUPS";
            ((ObjectNode) after.at("/" + path.replace('.', '/'))).put(state.equals("invalid-auth") ? "availability" : "coverage", "UNKNOWN");
            request = request(before, after);
        }
        var report = impacts.analyzeFactPathsAt(request, NOW); var summary = summaries.inspect(request, NOW);
        assertEquals(state.equals("no-op") ? Status.ANALYZED : Status.BLOCKED, report.status());
        assertTrue(report.cases().isEmpty()); assertFalse(summary.changedFactPathsCovered()); assertFalse(summary.coverageComplete()); assertUntrusted(report);
    }

    @Test void maximumDisjointOptionUnionProducesBounded13600ChecksWithoutDroppingAnyPath() {
        var base = mapper.createArrayNode(); var candidate = mapper.createArrayNode(); var template = option();
        for (int i = 0; i < 100; i++) {
            var before = template.deepCopy(); before.put("id", "old-" + i).put("plan", "scope-" + i); base.add(before);
            var after = before.deepCopy(); after.put("id", "new-" + i); candidate.add(after);
        }
        var request = request(draft("base", base), draft("next", candidate)); var summary = summaries.inspect(request, NOW);
        assertEquals(200, summary.affectedOptions()); assertEquals(13600, summary.checkedCases()); assertEquals(13600, summary.changedFacts());
        assertEquals(200, summary.scopeChangedOptions()); assertEquals(0, summary.uncoveredChanges()); assertTrue(summary.changedFactPathsCovered());
        assertFalse(summary.coverageComplete()); assertFalse(summary.storedReportVerified()); assertFalse(summary.approvalGranted());
        var json = mapper.writeValueAsString(summary); assertFalse(json.contains("sourceUrl")); assertFalse(json.contains("availability")); assertFalse(json.contains("actor"));
    }

    @Test void countsCannotConvertNoOpPartialOrGapSummariesIntoCoverage() {
        var status = CatalogFactPathRegressionService.Status.ANALYZED;
        var hash = "a".repeat(64);
        for (var check : List.of(CatalogFactPathRegressionService.Check.notChecked(),
                new CatalogFactPathRegressionService.Check(status, NOW, hash, 1, 0, 0, 1, 0),
                new CatalogFactPathRegressionService.Check(status, NOW, hash, 1, 2, 0, 1, 0),
                new CatalogFactPathRegressionService.Check(status, NOW, hash, 1, 0, 1, 67, 0),
                new CatalogFactPathRegressionService.Check(status, NOW, hash, 1, 1, 0, 1, 1))) {
            assertFalse(check.changedFactPathsCovered()); assertFalse(check.coverageComplete()); assertFalse(check.approvalGranted());
        }
        assertThrows(IllegalArgumentException.class, () -> new CatalogFactPathRegressionService.Check(status, NOW, hash, 201, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CatalogFactPathRegressionService.Check(status, NOW, hash, 200, 13601, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CatalogFactPathRegressionService.Check(status, NOW, hash, 200, 0, 0, 13601, 0));
        assertThrows(IllegalArgumentException.class, () -> new CatalogFactPathRegressionService.Check(CatalogFactPathRegressionService.Status.NOT_CHECKED, NOW, hash, 0, 0, 0, 0, 0));
    }

    @Test void requestMapAndArrayOrderingCannotChangeTheReportOrCheckedCaseSet() {
        var before = option(); var after = before.deepCopy(); after.put("plan", "changed"); var request = request(before, after);
        var reordered = mapper.treeToValue(reverse(mapper.valueToTree(request)), CatalogChangePreviewRequest.class);
        assertEquals(impacts.analyzeFactPathsAt(request, NOW), impacts.analyzeFactPathsAt(reordered, NOW));
        assertEquals(summaries.inspect(request, NOW), summaries.inspect(reordered, NOW));
    }

    private JsonNode reverse(JsonNode value) {
        if (value.isObject()) { var result = mapper.createObjectNode(); var entries = new java.util.ArrayList<>(value.properties());
            entries.reversed().forEach(e -> result.set(e.getKey(), reverse(e.getValue()))); return result; }
        if (value.isArray()) { var result = mapper.createArrayNode(); var entries = new java.util.ArrayList<JsonNode>(); value.forEach(entries::add);
            entries.reversed().forEach(e -> result.add(reverse(e))); return result; }
        return value;
    }

    private void schemaPaths(JsonNode root, JsonNode node, String path, Set<String> output, int depth) {
        assertTrue(depth < 16); assertNotNull(node);
        if (node.has("$ref")) { schemaPaths(root, root.at(node.get("$ref").asText().substring(1)), path, output, depth + 1); return; }
        var properties = node.get("properties"); if (properties == null) return;
        if (properties.has("conditions") && properties.has("evidence")) { assertTrue(output.add(path)); return; }
        for (var entry : properties.properties()) schemaPaths(root, entry.getValue(), path + "." + entry.getKey(), output, depth + 1);
    }
    private ObjectNode option() {
        var option = (ObjectNode) mapper.readTree("""
                {"id":"synthetic","providerId":"fictional","product":"Synthetic product","plan":"Fixture","deployment":"MANAGED",
                 "region":"No inferred residency","configuration":"Inert regression fixture","facts":{},
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
        value.putArray("conditions"); value.putObject("evidence").put("sourceUrl", "https://source.invalid/regression")
                .put("observedAt", NOW.toString()).put("summary", "Fictional assertion; never fetched"); return value;
    }
    private void put(ObjectNode option, String path, JsonNode value) {
        var keys = path.split("\\."); var parent = option;
        for (int i = 0; i < keys.length - 1; i++) { if (!parent.has(keys[i])) parent.putObject(keys[i]); parent = (ObjectNode) parent.get(keys[i]); }
        parent.set(keys[keys.length - 1], value);
    }
    private void remove(ObjectNode option, String path) { int last = path.lastIndexOf('.'); ((ObjectNode) option.at("/" + path.substring(0, last).replace('.', '/'))).remove(path.substring(last + 1)); }
    private ProviderCatalogDraft draft(String label, JsonNode options) {
        var value = mapper.createObjectNode().put("schemaVersion", 1).put("kind", "PROVIDER_CATALOG_DRAFT").put("catalogVersion", label);
        value.set("options", options); return mapper.treeToValue(value, ProviderCatalogDraft.class);
    }
    private CatalogChangePreviewRequest request(ObjectNode before, ObjectNode after) { return request(draft("base", mapper.createArrayNode().add(before)), draft("next", mapper.createArrayNode().add(after))); }
    private CatalogChangePreviewRequest request(ProviderCatalogDraft before, ProviderCatalogDraft after) { return new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Synthetic fact-path regression", CatalogDraftCanonicalizer.sha256(before), before, after); }
    private void assertUntrusted(CatalogImpactPreview report) {
        assertFalse(report.coverageComplete()); assertFalse(report.sourceVerificationPerformed()); assertFalse(report.baselineVerified());
        assertFalse(report.approvalGranted()); assertFalse(report.writesPerformed()); assertFalse(report.evaluationReady()); assertFalse(report.recommendationReady());
        assertFalse(report.storedRequestDigestVerified()); assertNull(report.storedProposalVersion());
    }
}
