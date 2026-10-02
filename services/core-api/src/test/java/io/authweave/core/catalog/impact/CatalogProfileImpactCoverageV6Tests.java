package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.AuditabilityCatalog;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.impact.CatalogProfileImpactCoverageV6Service.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogProfileImpactCoverageV6Tests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases base = base();
    private final CatalogAuditabilityRegressionCases cases = cases();
    private final CatalogProfileImpactCoverageService legacy = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, mapper));
    private final CatalogAuditabilityRegressionService audit = new CatalogAuditabilityRegressionService(cases,
            resource("synthetic.v4.json", ProviderCatalog.class), resource("auditability-evidence.v1.json", AuditabilityCatalog.class));
    private final CatalogProfileImpactCoverageV6Service service = new CatalogProfileImpactCoverageV6Service(legacy, cases, audit);
    private CatalogScopedProfileCases base() { try { return new CatalogScopedProfileCases(mapper); } catch (Exception e) { throw new AssertionError(e); } }
    private CatalogAuditabilityRegressionCases cases() { try { return new CatalogAuditabilityRegressionCases(mapper, base); } catch (Exception e) { throw new AssertionError(e); } }
    private <T> T resource(String name, Class<T> type) {
        try (var stream = new ClassPathResource("catalog/" + name).getInputStream()) { return mapper.readValue(stream, type); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    static Stream<Dimension> dimensions() { return DIMENSIONS.stream(); }
    @Test void versionedManifestMatchesEveryIndependentV6SemanticInputWithoutChangingV5() {
        var schema = mapper.readTree(Path.of(System.getProperty("basedir", "."), "../../packages/contracts/schemas/application-identity-profile.v6.schema.json").toFile());
        var paths = new TreeSet<String>(); schemaPaths(schema, schema, "", paths);
        assertEquals(34, paths.size()); assertEquals(paths, new TreeSet<>(DIMENSIONS.stream().map(Dimension::profilePath).toList()));
        assertEquals(CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, CatalogDraftCanonicalizer.sha256(schema));
        assertEquals("catalog-profile-impact-coverage-5", POLICY_VERSION);
        assertEquals("eb96cb4962d7a4f2292e39dad8900e5e40d89c0781275fb7347643a4d27ea95b", MANIFEST_SHA256);
        assertEquals("catalog-profile-impact-coverage-4", legacy.inspectAt(AT).policyVersion());
        assertEquals(40, legacy.inspectAt(AT).deferredDimensions()); assertEquals(32, CatalogProfileImpactCoverageService.DIMENSIONS.size());
        assertEquals(40, VERIFICATION_GAPS.size()); assertEquals(3, DIMENSIONS.stream().filter(d -> d.boundary() == CatalogProfileImpactCoverageService.Boundary.AUDITABILITY).count());
        assertThrows(UnsupportedOperationException.class, () -> DIMENSIONS.clear());
        assertThrows(UnsupportedOperationException.class, () -> VERIFICATION_GAPS.clear());
    }
    @Test void sourceBoundCompositionSeparatesStructuralRulesSyntheticCapabilitiesAndVerificationGaps() {
        var old = legacy.inspectAt(AT); var result = service.inspectUsing(old, AT);
        assertEquals(Status.INCOMPLETE, result.status()); assertEquals(136, result.checkedDimensions());
        assertEquals(old.scenarioSetSha256(), result.baseScenarioSetSha256()); assertEquals(cases.sha256(), result.scenarioSetSha256());
        assertEquals(CatalogDraftCanonicalizer.sha256(old), result.catalogCoverageSha256()); assertEquals(audit.inspectAt(AT), result.auditabilityRegression());
        assertEquals("1e3670ab5fdd7e16764fc491385c584fbb89eae1a09abb06855edcdb4af6920a", result.catalogCoverageSha256());
        assertEquals(11, result.auditabilityDimensions()); assertEquals(36, result.deferredDimensions()); assertEquals(4, result.patternDimensions());
        assertEquals(old.conditionalDimensions(), result.conditionalDimensions()); assertEquals(old.scopeOnlyDimensions() + 1, result.scopeOnlyDimensions());
        assertEquals(136, result.conditionalDimensions() + result.patternDimensions() + result.auditabilityDimensions()
                + result.scopeOnlyDimensions() + result.deferredDimensions() + result.missingRules());
        assertEquals(41, result.dimensions().stream().mapToInt(DimensionCheck::activeAuditabilityRuleCount).sum());
        assertTrue(result.unexercisedFactPaths().isEmpty()); assertEquals(result, service.inspectAt(AT));
        assertTrue(result.verificationGaps().containsAll(VERIFICATION_GAPS));
        var json = mapper.valueToTree(result);
        for (String flag : List.of("candidateAuditabilityChangesEvaluated", "coverageComplete", "configurationVerified", "complianceVerified", "storedReportVerified",
                "sourceVerificationPerformed", "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) assertFalse(json.get(flag).asBoolean());
        for (String forbidden : List.of("profile", "actor", "sourceUrl", "observedAt", "facts", "requirements", "minimumRetentionDays"))
            assertFalse(json.toString().contains("\"" + forbidden + "\":"), forbidden);
        assertTrue(base.definitions().stream().allMatch(d -> d.profileSchemaVersion() == 5 && d.profile().at("/security/auditabilityRequirements").isMissingNode()));
    }
    @ParameterizedTest @MethodSource("dimensions")
    void everyV6InputHasExactlyOneBoundDependencyRowForEachScenario(Dimension definition) {
        var result = service.inspectAt(AT); var rows = result.dimensions().stream().filter(d -> d.profilePath().equals(definition.profilePath())).toList();
        assertEquals(4, rows.size()); assertEquals(CatalogScopedProfileCases.IDS, Set.copyOf(rows.stream().map(DimensionCheck::scenarioId).toList()));
        for (var row : rows) {
            assertEquals(definition.boundary(), row.boundary()); assertTrue(definition.permittedFactPaths().containsAll(row.factPaths()));
            assertTrue(definition.permittedEvidenceCriteria().containsAll(row.evidenceCriteria()));
            if (row.boundary() == CatalogProfileImpactCoverageService.Boundary.AUDITABILITY) {
                assertEquals(0, row.activeFactRuleCount()); assertTrue(row.factPaths().isEmpty());
                var selected = cases.definitions().stream().filter(c -> c.scenarioId().equals(row.scenarioId())).findFirst().orElseThrow().requirements().selectedCriteria();
                assertEquals(definition.permittedEvidenceCriteria().stream().filter(selected::contains).toList(), row.evidenceCriteria());
            } else {
                var original = legacy.inspectAt(AT).dimensions().stream().filter(d -> d.scenarioId().equals(row.scenarioId()) && d.profilePath().equals(row.profilePath())).findFirst().orElseThrow();
                assertEquals(original.state().name(), row.state().name()); assertEquals(original.factPaths(), row.factPaths()); assertEquals(0, row.activeAuditabilityRuleCount());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"future", "boundary", "stale"})
    void futureOrStaleEvidenceChangesOutcomesButNeverTurnsRulePresenceIntoVerifiedConfiguration(String variant) {
        var at = variant.equals("future") ? AT.minusNanos(1) : variant.equals("boundary") ? AT.plus(EvidencePolicy.MAX_AGE) : AT.plus(EvidencePolicy.MAX_AGE).plusNanos(1);
        var result = service.inspectAt(at); assertEquals(service.inspectAt(AT).dimensions(), result.dimensions());
        assertEquals(variant.equals("boundary") ? new CatalogAuditabilityRegressionService.OutcomeCounts(27, 8, 22, 15)
                : new CatalogAuditabilityRegressionService.OutcomeCounts(0, 0, 57, 15), result.auditabilityRegression().outcomes());
        assertEquals(service.inspectAt(AT).auditabilityRegression().evidenceSha256(), result.auditabilityRegression().evidenceSha256());
        assertEquals(Status.INCOMPLETE, result.status()); assertFalse(result.coverageComplete()); assertFalse(result.configurationVerified());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "time", "digest", "gap", "extra-gap", "unexercised", "not-checked"})
    void incompleteMatricesForgedBindingsAndHiddenVerificationGapsAreRejected(String variant) {
        var good = service.inspectAt(AT); var rows = new ArrayList<>(good.dimensions()); var gaps = new ArrayList<>(good.verificationGaps());
        if (variant.equals("missing")) rows.removeLast(); if (variant.equals("duplicate")) rows.set(1, rows.getFirst());
        if (variant.equals("gap")) gaps.removeLast(); if (variant.equals("extra-gap")) gaps.add(new BoundaryGap("b2b-saas-scoped", "security.auditability", "unreviewed"));
        assertThrows(RuntimeException.class, () -> new Check(variant.equals("not-checked") ? Status.NOT_CHECKED : Status.INCOMPLETE,
                variant.equals("time") ? AT.plusNanos(1) : AT, good.baseScenarioSetSha256(), variant.equals("digest") ? "0".repeat(64) : good.scenarioSetSha256(),
                good.catalogCoverageSha256(), good.auditabilityRegression(), rows, gaps, variant.equals("unexercised") ? List.of("facts.SCIM") : List.of()));
    }
    @Test void auditabilityRowsCannotBorrowCatalogClaimsOrOtherCriteriaOrPretendTheyAreVerifiedRules() {
        for (String variant : List.of("id", "path", "state", "fact", "criterion", "count")) {
            assertThrows(RuntimeException.class, () -> new DimensionCheck(variant.equals("id") ? "foreign" : "b2b-saas-scoped",
                    variant.equals("path") ? "security.unreviewed" : "security.auditabilityRequirements.minimumRetentionDays",
                    CatalogProfileImpactCoverageService.Boundary.AUDITABILITY, variant.equals("state") ? State.CONDITIONAL_RULE_PRESENT : State.SYNTHETIC_AUDITABILITY_RULE_PRESENT,
                    1, variant.equals("fact") ? 1 : 0, variant.equals("count") ? 0 : 1, variant.equals("fact") ? List.of("facts.SCIM") : List.of(),
                    List.of(variant.equals("criterion") ? Criterion.AUDIT_LOG_EXPORT : Criterion.AUDIT_LOG_RETENTION)));
        }
        var good = service.inspectAt(AT); var rows = new ArrayList<>(good.dimensions());
        int index = java.util.stream.IntStream.range(0, rows.size()).filter(i -> rows.get(i).scenarioId().equals("b2b-saas-scoped")
                && rows.get(i).profilePath().equals("security.auditabilityRequirements.selectedCriteria")).findFirst().orElseThrow();
        rows.set(index, new DimensionCheck("b2b-saas-scoped", "security.auditabilityRequirements.selectedCriteria",
                CatalogProfileImpactCoverageService.Boundary.AUDITABILITY, State.SYNTHETIC_AUDITABILITY_RULE_PRESENT, 6, 0, 1, List.of(), List.of(Criterion.AUDIT_LOG_RETENTION)));
        assertThrows(IllegalArgumentException.class, () -> new Check(good.status(), AT, good.baseScenarioSetSha256(), good.scenarioSetSha256(), good.catalogCoverageSha256(),
                good.auditabilityRegression(), rows, good.verificationGaps(), good.unexercisedFactPaths()));
    }
    @Test void legacyCoverageMustBeFreshAndCurrentAndNotCheckedCannotMasqueradeAsAnAnalysis() {
        assertThrows(IllegalArgumentException.class, () -> service.inspectUsing(legacy.inspectAt(AT.plusNanos(1)), AT));
        assertThrows(IllegalArgumentException.class, () -> service.inspectUsing(CatalogProfileImpactCoverageService.Check.notChecked(), AT));
        var empty = Check.notChecked(); assertEquals(0, empty.checkedDimensions()); assertNull(empty.auditabilityRegression());
        assertFalse(empty.coverageComplete()); assertEquals(34, empty.declaredProfileInputs());
    }
    @Test void rowsCannotClaimMoreAppliedAuditabilityRulesThanTheBoundRegressionActuallyExercised() {
        var good = service.inspectAt(AT);
        var rows = good.dimensions().stream().map(d -> {
            if (d.boundary() != CatalogProfileImpactCoverageService.Boundary.AUDITABILITY) return d;
            var criteria = DIMENSIONS.stream().filter(m -> m.profilePath().equals(d.profilePath())).findFirst().orElseThrow().permittedEvidenceCriteria();
            return new DimensionCheck(d.scenarioId(), d.profilePath(), d.boundary(), State.SYNTHETIC_AUDITABILITY_RULE_PRESENT,
                    d.ruleCount(), 0, criteria.size(), List.of(), criteria);
        }).toList();
        assertThrows(IllegalArgumentException.class, () -> new Check(good.status(), AT, good.baseScenarioSetSha256(), good.scenarioSetSha256(), good.catalogCoverageSha256(),
                good.auditabilityRegression(), rows, good.verificationGaps(), good.unexercisedFactPaths()));
    }
    private void schemaPaths(JsonNode root, JsonNode node, String path, Set<String> paths) {
        var document = root; var schema = node;
        while (schema.has("$ref")) {
            String ref = schema.get("$ref").asText(); int fragment = ref.indexOf('#');
            if (fragment > 0) {
                assertEquals("./application-identity-profile.v5.schema.json", ref.substring(0, fragment));
                document = mapper.readTree(Path.of(System.getProperty("basedir", "."), "../../packages/contracts/schemas", ref.substring(0, fragment)).toFile());
            }
            schema = document.at(ref.substring(fragment + 1));
        }
        var resolvedRoot = document;
        if (schema.has("properties") && !path.equals("operations.usagePlanning.volumes"))
            schema.get("properties").properties().forEach(e -> schemaPaths(resolvedRoot, e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), paths));
        else paths.add(path);
    }
}
