package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.proposal.CatalogProposalException;
import io.authweave.core.evaluation.*;
import static io.authweave.core.catalog.impact.CatalogScenarioImpact.ConditionalStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogScenarioImpactTests {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScenarioCases cases = cases();
    private final CatalogScenarioImpactService service = service(NOW);

    @Test
    void threeGoldenProfilesPreserveUnknownsAndShowScimImpactWithoutInventingReadiness() throws Exception {
        var report = analyze(fixture());
        var golden = mapper.readTree(path("catalog-scenario-impact.expected.json").toFile());
        assertEquals(golden.get("caseSetSha256").asText(), report.caseSetSha256());
        assertEquals(golden.get("caseSetVersion").asText(), report.caseSetVersion());
        assertEquals(3, report.scenarios().size());
        for (int i = 0; i < 3; i++) {
            var expected = golden.get("scenarios").get(i); var actual = report.scenarios().get(i);
            assertEquals(expected.get("id").asText(), actual.scenarioId());
            assertEquals(expected.get("before").asText(), actual.before().conditionalStatus().name());
            assertEquals(expected.get("after").asText(), actual.after().conditionalStatus().name());
            assertEquals(expected.get("checks").asInt(), actual.before().checks().size());
            assertEquals(expected.get("changedCheckIds"), mapper.valueToTree(actual.changedCheckIds()));
            assertEquals(expected.get("affectedFactPaths"), mapper.valueToTree(actual.affectedFactPaths()));
            assertTrue(actual.before().checks().stream().anyMatch(c -> c.reason().equals("COMPLIANCE_SCOPE_UNKNOWN")));
        }
        assertTrue(report.impactAnalysisPerformed()); assertTrue(report.hypotheticalEvaluationPerformed());
        assertFalse(report.coverageComplete()); assertFalse(report.baselineVerified()); assertFalse(report.sourceVerificationPerformed());
        assertFalse(report.approvalGranted()); assertFalse(report.writesPerformed()); assertFalse(report.evaluationReady()); assertFalse(report.recommendationReady());
        assertFalse(report.storedRequestDigestVerified()); assertNull(report.storedProposalVersion());
        assertTrue(report.deferredPaths().contains("operations")); assertTrue(report.deferredPaths().contains("security.assurance"));
        var unusedScim = report.scenarios().get(1).before().checks().stream().filter(c -> "facts.SCIM".equals(c.factPath())).findFirst().orElseThrow();
        assertFalse(unusedScim.usesFact()); assertTrue(unusedScim.factPresent());
        assertEquals(CatalogImpactPreview.Outcome.NOT_APPLIED, unusedScim.conditionalOutcome());
        var original = cases.definitions().getFirst().profile(); ((ObjectNode) original.get("security")).put("assurance", "HIGH");
        assertEquals("BASELINE", cases.definitions().getFirst().profile().at("/security/assurance").asText());
        assertThrows(UnsupportedOperationException.class, () -> report.scenarios().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN", "MACHINE_ONLY", "MIXED_CLIENTS",
            "NO_CLIENTS", "NO_POPULATIONS", "UNKNOWN_CONTEXT", "NO_COUNTRIES", "NO_CATEGORIES", "COMPLIANCE_NONE", "COMPLIANCE_TARGETS", "CHECKED_MATCH"})
    void conditionalOutcomesMatchEveryProductionCheckedDimensionWhenSyntheticEvidenceIsReviewedAndCurrent(String variant) throws Exception {
        // Test-only normalization of existing .invalid synthetic fixtures; never promotes a real draft in production.
        var catalogJson = resource("catalog/synthetic.v4.json"); normalizeSyntheticEvidence(catalogJson);
        var catalog = mapper.treeToValue(catalogJson, ProviderCatalog.class);
        for (var definition : cases.definitions()) {
            var profileJson = (ObjectNode) definition.profile(); var security = (ObjectNode) profileJson.get("security");
            security.put("dataResidency", "REQUIRED");
            ((ObjectNode) security.get("dataResidencyDetails")).putArray("allowedCountries").add("DE").add("NL");
            ((ObjectNode) security.get("dataResidencyDetails")).putArray("dataCategories").add("USER_PROFILES").add("BACKUPS");
            var controls = (ObjectNode) security.get("authenticationControls");
            for (var name : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) controls.put(name, "REQUIRED");
            if (List.of("REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN").contains(variant)) {
                security.put("dataResidency", variant); security.put("multiFactorAuthentication", variant);
                for (var name : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) controls.put(name, variant);
                ((ObjectNode) profileJson.get("provisioning")).put("scim", variant);
            } else switch (variant) {
                case "MACHINE_ONLY" -> { ((ObjectNode) profileJson.get("application")).putArray("clients").add("MACHINE_TO_MACHINE");
                    ((ObjectNode) profileJson.get("audience")).putArray("populations"); }
                case "MIXED_CLIENTS" -> ((ObjectNode) profileJson.get("application")).putArray("clients").add("MACHINE_TO_MACHINE").add("NATIVE_MOBILE").add("BROWSER");
                case "NO_CLIENTS" -> ((ObjectNode) profileJson.get("application")).putArray("clients");
                case "NO_POPULATIONS" -> ((ObjectNode) profileJson.get("audience")).putArray("populations");
                case "UNKNOWN_CONTEXT" -> { ((ObjectNode) profileJson.get("application")).put("type", "OTHER");
                    ((ObjectNode) profileJson.get("audience")).put("tenancy", "UNKNOWN").put("membership", "UNKNOWN"); }
                case "NO_COUNTRIES" -> ((ObjectNode) security.get("dataResidencyDetails")).putArray("allowedCountries");
                case "NO_CATEGORIES" -> ((ObjectNode) security.get("dataResidencyDetails")).putArray("dataCategories");
                case "COMPLIANCE_NONE" -> security.put("complianceScopeStatus", "NONE_IDENTIFIED");
                case "COMPLIANCE_TARGETS" -> { security.put("complianceScopeStatus", "TARGETS_IDENTIFIED"); security.putArray("complianceTargets").add("GDPR"); }
                case "CHECKED_MATCH" -> {
                    security.put("complianceScopeStatus", "NONE_IDENTIFIED").put("dataResidency", "NOT_REQUIRED");
                    for (var name : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) controls.put(name, "NOT_REQUIRED");
                }
            }
            var profile = mapper.treeToValue(profileJson, ApplicationIdentityProfile.class);
            var expected = EligibilityEvaluator.evaluateWithComplianceScope(profile, catalog, NOW);
            if (variant.equals("CHECKED_MATCH") && definition.id().equals("b2b-saas")) {
                assertTrue(expected.stream().anyMatch(c -> c.status() == CapabilityPreflight.Status.MATCHES_CHECKED_REQUIREMENTS));
            }
            for (var candidate : expected) {
                var json = findOption(catalogJson, candidate.optionId());
                var actual = CatalogScenarioImpactService.evaluate(ScenarioRulePlan.from(profile), toDraft(json), NOW);
                var outcomes = Stream.of(candidate.capabilityChecks().stream().map(CapabilityPreflight.Check::outcome),
                        candidate.contextChecks().stream().map(EligibilityPreflight.ContextCheck::outcome),
                        candidate.residencyChecks().stream().map(ResidencyCheck::outcome),
                        candidate.authenticationControlChecks().stream().map(AuthenticationControlCheck::outcome),
                        Stream.of(ComplianceScopeEvaluator.evaluate(profile.security()).outcome())).flatMap(s -> s)
                        .map(CatalogScenarioImpactTests::conditional).toList();
                assertEquals(outcomes, actual.checks().stream().map(CatalogScenarioImpact.Check::conditionalOutcome).toList(), variant + ":" + definition.id());
                assertEquals(switch (candidate.status()) {
                    case MATCHES_CHECKED_REQUIREMENTS -> WOULD_SATISFY_CHECKED_REQUIREMENTS;
                    case DOES_NOT_MATCH -> WOULD_VIOLATE_CHECKED_REQUIREMENTS; case NEEDS_INFORMATION -> INDETERMINATE;
                }, actual.conditionalStatus());
                assertEquals(actual.checks().size(), actual.checks().stream().map(CatalogScenarioImpact.Check::checkId).distinct().count());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "conditions", "scope", "uncovered", "removed", "renamed", "blocked", "no-op"})
    void explainsChangedDependenciesScopeAndCoverageWithoutChangingState(String variant) throws Exception {
        var input = fixture(); input.set("candidate", input.get("base").deepCopy()); ((ObjectNode) input.get("candidate")).put("catalogVersion", "example-proposal-2");
        var option = (ObjectNode) input.at("/candidate/options/0");
        switch (variant) {
            case "source" -> ((ObjectNode) option.at("/facts/SCIM/evidence")).put("sourceUrl", "https://untrusted.example.com/never-fetch");
            case "conditions" -> ((ObjectNode) option.at("/facts/SCIM")).putArray("conditions").add("A changed assumption, not a command");
            case "scope" -> option.put("plan", "Different plan");
            case "uncovered" -> ((ObjectNode) option.at("/residency/USER_PROFILES/evidence")).put("summary", "Changed unconsumed assertion");
            case "removed" -> ((ObjectNode) option.get("facts")).remove("SCIM");
            case "renamed" -> option.put("id", "renamed-option");
            case "blocked" -> input.put("expectedBaseSha256", "0".repeat(64));
        }
        var report = analyze(input); assertEquals(report, analyze(input));
        assertEquals(variant.equals("renamed") ? 6 : List.of("blocked", "no-op").contains(variant) ? 0 : 3, report.scenarios().size());
        if (variant.equals("blocked")) { assertEquals(CatalogImpactPreview.Status.BLOCKED, report.status()); assertFalse(report.impactAnalysisPerformed()); }
        else assertEquals(CatalogImpactPreview.Status.ANALYZED, report.status());
        if (List.of("source", "conditions").contains(variant)) {
            assertEquals(List.of("facts.SCIM"), report.scenarios().getFirst().affectedFactPaths());
            assertTrue(report.scenarios().stream().allMatch(s -> s.changedCheckIds().isEmpty()));
        }
        if (variant.equals("uncovered")) {
            assertEquals(List.of("residency.USER_PROFILES"), report.uncoveredChanges().stream().map(CatalogImpactPreview.UncoveredChange::factPath).toList());
            assertTrue(report.scenarios().stream().allMatch(s -> s.affectedFactPaths().isEmpty()));
        }
        if (variant.equals("scope")) { assertTrue(report.scenarios().stream().allMatch(CatalogScenarioImpact.ScenarioImpact::scopeChanged)); assertFalse(report.uncoveredChanges().isEmpty()); }
        if (variant.equals("removed")) assertTrue(report.scenarios().getFirst().after().checks().stream().anyMatch(c -> c.reason().equals("FACT_MISSING")));
        if (variant.equals("renamed")) assertEquals(3, report.scenarios().stream().filter(s -> s.before().conditionalStatus() == OPTION_ABSENT).count());
        assertFalse(report.coverageComplete()); assertFalse(report.approvalGranted());
    }

    @Test
    void bindsInputAndScenarioDigestsAndTimeWithoutReinterpretingUnknowns() throws Exception {
        var input = fixture(); var report = analyze(input);
        var later = service(NOW.plusSeconds(100 * 86400L)).analyze(request(input));
        assertEquals(report.proposalSha256(), later.proposalSha256()); assertEquals(report.caseSetSha256(), later.caseSetSha256());
        assertEquals(report.scenarios().getFirst().after().conditionalStatus(), later.scenarios().getFirst().after().conditionalStatus());
        assertTrue(later.scenarios().getFirst().after().checks().stream().anyMatch(c -> c.freshness() == CatalogDraftValidation.Freshness.STALE));
        var stored = service.analyze(request(input), 0L, report.proposalSha256()); assertTrue(stored.storedRequestDigestVerified());
        assertEquals(report.scenarios(), stored.scenarios()); assertEquals(0L, stored.storedProposalVersion());
        assertThrows(CatalogProposalException.class, () -> service.analyze(request(input), 0L, "0".repeat(64)));
        assertEquals(report, analyze(reverse(input)));
    }

    private static CatalogImpactPreview.Outcome conditional(CapabilityPreflight.Outcome value) {
        return switch (value) { case PASS -> CatalogImpactPreview.Outcome.WOULD_SATISFY; case FAIL -> CatalogImpactPreview.Outcome.WOULD_VIOLATE;
            case UNKNOWN -> CatalogImpactPreview.Outcome.INDETERMINATE; case NOT_APPLIED -> CatalogImpactPreview.Outcome.NOT_APPLIED; };
    }
    private JsonNode findOption(JsonNode catalog, String id) { for (var o : catalog.get("options")) if (o.get("id").asText().equals(id)) return o; throw new AssertionError(id); }
    private ProviderCatalogDraft.Option toDraft(JsonNode original) {
        var value = (ObjectNode) original.deepCopy(); value.put("providerId", "fictional").put("product", value.remove("displayName").asText())
                .put("deployment", "MANAGED").put("configuration", "Synthetic parity fixture");
        draftEvidence(value); return mapper.treeToValue(value, ProviderCatalogDraft.Option.class);
    }
    private void normalizeSyntheticEvidence(JsonNode node) {
        if (node.isObject()) { if (node.has("evidenceStatus")) { ((ObjectNode) node).put("evidenceStatus", "REVIEWED").put("observedAt", NOW.toString()); }
            else node.properties().forEach(e -> normalizeSyntheticEvidence(e.getValue())); }
        else if (node.isArray()) node.forEach(this::normalizeSyntheticEvidence);
    }
    private void draftEvidence(JsonNode node) {
        if (node.isObject() && node.has("evidenceStatus")) {
            var o = (ObjectNode) node; o.remove("evidenceStatus"); var source = o.remove("sourceUrl"); var date = o.remove("observedAt");
            o.putArray("conditions"); var evidence = o.putObject("evidence"); evidence.set("sourceUrl", source); evidence.set("observedAt", date); evidence.put("summary", "Fictional parity assertion");
        } else if (node.isObject()) new ArrayList<>(node.properties()).forEach(e -> draftEvidence(e.getValue()));
    }
    private CatalogScenarioCases cases() { try { return new CatalogScenarioCases(mapper); } catch (Exception e) { throw new AssertionError(e); } }
    private CatalogScenarioImpactService service(Instant at) { var clock = Clock.fixed(at, ZoneOffset.UTC);
        return new CatalogScenarioImpactService(new CatalogChangePreviewService(new CatalogDraftValidator(clock), clock), cases); }
    private JsonNode resource(String name) throws Exception { try (var input = new ClassPathResource(name).getInputStream()) { return mapper.readTree(input); } }
    private Path path(String name) { return Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures/" + name); }
    private ObjectNode fixture() throws Exception { return (ObjectNode) mapper.readTree(path("catalog-change-preview-request.valid.json").toFile()); }
    private CatalogChangePreviewRequest request(JsonNode input) { return mapper.treeToValue(input, CatalogChangePreviewRequest.class); }
    private CatalogScenarioImpact analyze(JsonNode input) { return service.analyze(request(input)); }
    private JsonNode reverse(JsonNode value) {
        if (value.isObject()) { var result = mapper.createObjectNode(); var entries = new ArrayList<>(value.properties()); entries.reversed().forEach(e -> result.set(e.getKey(), reverse(e.getValue()))); return result; }
        if (value.isArray()) { var result = mapper.createArrayNode(); var entries = new ArrayList<JsonNode>(); value.forEach(entries::add); entries.reversed().forEach(e -> result.add(reverse(e))); return result; }
        return value;
    }
}
