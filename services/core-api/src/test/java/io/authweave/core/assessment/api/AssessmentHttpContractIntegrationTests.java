package io.authweave.core.assessment.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.persistence.AssessmentRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exports actual MVC requests/responses for independent AJV checks in make check-core and CI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-internal-token-000000000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123456789012345678",
        "AUTHWEAVE_OIDC_ORG_ID=987654321098765432"
})
@AutoConfigureMockMvc(addFilters = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(AssessmentHttpContractIntegrationTests.PreflightClock.class)
class AssessmentHttpContractIntegrationTests extends PostgresIntegrationTest {

    private static final Path SAMPLES = Path.of("target", "core-http-contract-samples.json");
    private final List<ContractSample> samples = new ArrayList<>();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AssessmentRepository repository;
    @Autowired private org.jooq.DSLContext proposalDsl;
    @Autowired private org.springframework.transaction.PlatformTransactionManager proposalTransactions;
    @Autowired private io.authweave.core.catalog.proposal.CatalogProposalRepository proposals;
    @Autowired private io.authweave.core.catalog.draft.CatalogChangePreviewService proposalPreviews;
    @Autowired private org.springframework.context.ApplicationContext applicationContext;

    @TestConfiguration(proxyBeanMethods = false)
    static class PreflightClock {
        @Bean @Primary
        Clock fixtureClock() {
            return Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeAll
    void removePreviousSamples() throws Exception {
        Files.deleteIfExists(SAMPLES);
    }

    @Test
    void configurationRegressionIsInputFreeCredentialProtectedBodyFreeAndReadOnly() throws Exception {
        String path = "/internal/v1/catalog-architecture-configuration/regression-preflight";
        String token = "Bearer synthetic-internal-token-000000000000000000000";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "wrong-token")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token, token)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token).queryParam("settings", "caller-design")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", token).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", token).header("Transfer-Encoding", "chunked")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization", token).content("{}")).andExpect(status().isMethodNotAllowed());
        var tables = List.of("core.assessments", "core.assessment_revisions", "audit.assessment_events", "core.catalog_proposals", "core.catalog_impact_reports",
                "core.catalog_fact_path_reports", "core.catalog_bootstrap_impact_reports", "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events");
        var before = tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList();
        var result = mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.checkedCases").value(252)).andExpect(jsonPath("$.checkedSettings").value(2004))
                .andExpect(jsonPath("$.outcomes.conditionallySatisfied").value(292)).andExpect(jsonPath("$.results.needsInformation").value(120))
                .andExpect(jsonPath("$.savedInputNeedsInformation").value(96)).andExpect(jsonPath("$.coverageComplete").value(false)).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        sample("architecture-configuration-regression", "catalog-architecture-configuration-regression-check", true, json);
        assertEquals(json, mapper.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        assertEquals(before, tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList());
        var service = applicationContext.getBean(io.authweave.core.catalog.impact.CatalogArchitectureConfigurationRegressionService.class);
        var expected = service.inspectAt(Instant.parse("2026-09-12T12:00:00Z")); assertEquals(mapper.valueToTree(expected), json);
        var components = mapper.createObjectNode();
        for (var component : io.authweave.core.catalog.impact.CatalogArchitectureConfigurationRegressionService.Check.class.getRecordComponents()) components.set(component.getName(), json.get(component.getName()));
        assertEquals(expected, mapper.treeToValue(components, io.authweave.core.catalog.impact.CatalogArchitectureConfigurationRegressionService.Check.class));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(components.deepCopy().put("selectedCases", 1),
                io.authweave.core.catalog.impact.CatalogArchitectureConfigurationRegressionService.Check.class));
        for (String flag : List.of("candidateChangesEvaluated", "coverageComplete", "configurationObserved", "configurationVerified", "providerCompatibilityVerified", "runtimeFlowVerified", "sourceVerificationPerformed", "storedReportVerified",
                "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) {
            var forged = (ObjectNode) json.deepCopy(); forged.put(flag, true); sample("configuration-regression-no-" + flag, "catalog-architecture-configuration-regression-check", false, forged);
        }
        for (String field : List.of("profile", "settings", "actor", "sourceUrl", "rows", "workspaceId")) {
            var forged = (ObjectNode) json.deepCopy(); forged.put(field, "private"); sample("configuration-regression-no-" + field, "catalog-architecture-configuration-regression-check", false, forged);
        }
        for (String field : List.of("reasons", "exercisedSettings", "checkedPaths", "deferredBoundaries")) {
            var forged = (ObjectNode) json.deepCopy(); forged.withArray(field).remove(0); sample("configuration-regression-complete-" + field, "catalog-architecture-configuration-regression-check", false, forged);
        }
        var forged = (ObjectNode) json.deepCopy(); ((ObjectNode) forged.get("outcomes")).put("unknown", -1);
        sample("configuration-regression-nonnegative-count", "catalog-architecture-configuration-regression-check", false, forged);
    }

    @Test
    void internalProfileV6CoverageIsInputFreeProtectedAndCannotPromoteFixtureCoverageOrWrite() throws Exception {
        String path = "/internal/v1/catalog-profile-impact/coverage-preflight";
        String token = "Bearer synthetic-internal-token-000000000000000000000";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "wrong-token")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token, token)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token).queryParam("coverageComplete", "true")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", token).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", token).header("Transfer-Encoding", "chunked")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization", token).content("{}")).andExpect(status().isMethodNotAllowed());
        var tables = List.of("core.assessments", "core.catalog_proposals", "core.catalog_impact_reports", "core.catalog_fact_path_reports", "core.catalog_bootstrap_impact_reports",
                "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events");
        var before = tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList();
        var result = mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("INCOMPLETE")).andExpect(jsonPath("$.policyVersion").value("catalog-profile-impact-coverage-5"))
                .andExpect(jsonPath("$.declaredProfileInputs").value(34)).andExpect(jsonPath("$.checkedDimensions").value(136))
                .andExpect(jsonPath("$.auditabilityDimensions").value(11)).andExpect(jsonPath("$.deferredDimensions").value(36))
                .andExpect(jsonPath("$.auditabilityRegression.checkedCases").value(12)).andExpect(jsonPath("$.coverageComplete").value(false)).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        sample("profile-v6-coverage", "catalog-profile-impact-coverage", true, json);
        var service = applicationContext.getBean(io.authweave.core.catalog.impact.CatalogProfileImpactCoverageV6Service.class);
        assertEquals(mapper.readTree(mapper.writeValueAsString(service.inspectAt(Instant.parse("2026-09-12T12:00:00Z")))), json);
        assertEquals(json, mapper.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        assertEquals(before, tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList());
        for (String flag : List.of("candidateAuditabilityChangesEvaluated", "coverageComplete", "configurationVerified", "complianceVerified", "storedReportVerified", "sourceVerificationPerformed",
                "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) {
            var forged = (ObjectNode) json.deepCopy(); forged.put(flag, true); sample("v6-coverage-forged-" + flag, "catalog-profile-impact-coverage", false, forged);
        }
        for (String key : List.of("profile", "actor", "sourceUrl", "candidate")) {
            var forged = (ObjectNode) json.deepCopy(); forged.put(key, "synthetic"); sample("v6-coverage-extra-" + key, "catalog-profile-impact-coverage", false, forged);
        }
        for (String field : List.of("dimensions", "verificationGaps")) {
            var partial = (ObjectNode) json.deepCopy(); partial.withArray(field).remove(0); sample("v6-coverage-partial-" + field, "catalog-profile-impact-coverage", false, partial);
            var duplicate = (ObjectNode) json.deepCopy(); duplicate.withArray(field).set(1, duplicate.withArray(field).get(0).deepCopy()); sample("v6-coverage-duplicate-" + field, "catalog-profile-impact-coverage", false, duplicate);
        }
        var forged = (ObjectNode) json.deepCopy(); ((ObjectNode) forged.get("auditabilityRegression")).put("coverageComplete", true);
        sample("v6-coverage-nested-authority", "catalog-profile-impact-coverage", false, forged);
    }

    @Test
    void internalAuditabilityRegressionIsCredentialProtectedInputFreeBodyFreeAndReadOnly() throws Exception {
        String path = "/internal/v1/catalog-auditability/regression-preflight";
        String token = "Bearer synthetic-internal-token-000000000000000000000";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "wrong-token")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token, token)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token).queryParam("coverageComplete", "true")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", token).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", token).header("Transfer-Encoding", "chunked")).andExpect(status().isBadRequest());
        var tables = List.of("core.assessments", "core.catalog_proposals", "core.catalog_impact_reports", "core.catalog_fact_path_reports", "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events");
        var before = tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList();
        var result = mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.checkedCases").value(12)).andExpect(jsonPath("$.outcomes.pass").value(27))
                .andExpect(jsonPath("$.outcomes.fail").value(8)).andExpect(jsonPath("$.outcomes.unknown").value(22))
                .andExpect(jsonPath("$.coverageComplete").value(false)).andExpect(jsonPath("$.publicationReady").value(false)).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        sample("auditability-regression-check", "catalog-auditability-regression-check", true, json);
        assertEquals(json, mapper.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        assertEquals(before, tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList());
        for (String flag : List.of("coverageComplete", "candidateChangesEvaluated", "configurationVerified", "complianceVerified", "sourceVerificationPerformed", "storedReportVerified",
                "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) {
            var forged = (ObjectNode) json.deepCopy(); forged.put(flag, true); sample("auditability-regression-forged-" + flag, "catalog-auditability-regression-check", false, forged);
        }
        for (String key : List.of("sourceUrl", "profile", "actor", "candidate")) {
            var forged = (ObjectNode) json.deepCopy(); forged.put(key, "synthetic"); sample("auditability-regression-extra-" + key, "catalog-auditability-regression-check", false, forged);
        }
        var suite = applicationContext.getBean(io.authweave.core.catalog.impact.CatalogAuditabilityRegressionCases.class);
        var service = applicationContext.getBean(io.authweave.core.catalog.impact.CatalogAuditabilityRegressionService.class);
        assertEquals(suite.sha256(), json.get("scenarioSetSha256").asText());
        var expected = service.inspectAt(Instant.parse("2026-09-12T12:00:00Z"));
        assertEquals(mapper.valueToTree(expected), json);
        // This output-only endpoint adds computed properties, not accepted constructor inputs.
        // Test native count validation separately; the complete wire response is schema-validated.
        var components = mapper.createObjectNode();
        for (var component : io.authweave.core.catalog.impact.CatalogAuditabilityRegressionService.Check.class.getRecordComponents())
            components.set(component.getName(), json.get(component.getName()));
        assertEquals(expected, mapper.treeToValue(components, io.authweave.core.catalog.impact.CatalogAuditabilityRegressionService.Check.class));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(components.deepCopy().put("checkedCases", 4),
                io.authweave.core.catalog.impact.CatalogAuditabilityRegressionService.Check.class));
    }

    @AfterAll
    void exportSamples() throws Exception {
        Files.createDirectories(SAMPLES.getParent());
        Files.writeString(SAMPLES, mapper.writeValueAsString(samples));
    }

    @Test
    void reservedPublishedSnapshotUsesTheActualStrictCoreMapperWithoutBecomingAnActiveCatalog() throws Exception {
        var type = io.authweave.core.catalog.publication.PublishedCatalogSnapshot.class;
        var fixture = (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/published-provider-catalog-snapshot.format-valid.json").toFile());
        var snapshot = mapper.treeToValue(fixture, type);
        var inspection = applicationContext.getBean(io.authweave.core.catalog.publication.CatalogSnapshotInspector.class).inspect(snapshot);
        assertEquals(io.authweave.core.catalog.publication.CatalogSnapshotInspector.Status.VALID_SNAPSHOT_FORMAT, inspection.status());
        assertFalse(inspection.baselineVerified()); assertFalse(inspection.approvalGranted());
        for (String field : List.of("schemaVersion", "kind", "snapshotId", "canonicalizationVersion", "catalog", "contentSha256",
                "snapshotSha256", "previousSnapshot", "publication", "factEvidenceStatuses")) {
            var missing = fixture.deepCopy(); missing.remove(field);
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(missing, type), field);
        }
        for (String field : List.of("schemaVersion", "kind")) {
            var malformed = fixture.deepCopy();
            if (field.equals("schemaVersion")) malformed.put(field, "1"); else malformed.put(field, 0);
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(malformed, type));
        }
        var declared = fixture.deepCopy(); ((ObjectNode) declared.at("/factEvidenceStatuses/0")).put("evidenceStatus", 0);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(declared, type));
        var authority = fixture.deepCopy(); authority.put("approvalGranted", true);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(authority, type));
        var privateActor = fixture.deepCopy(); ((ObjectNode) privateActor.get("publication")).put("actorSubject", "private");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(privateActor, type));
        var promotedDraft = fixture.deepCopy(); ((ObjectNode) promotedDraft.at("/catalog/options/0/facts/SCIM")).put("evidenceStatus", "REVIEWED");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> mapper.treeToValue(promotedDraft, type));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "unknown-root", "unknown-section", "unknown-field", "fractional-version",
            "string-version", "boolean-version", "negative-version", "unsafe-version", "overflow-version", "missing-version",
            "null-version", "numeric-enum", "numeric-string-enum", "unknown-enum", "padded-enum", "lowercase-enum", "empty-enum",
            "duplicate-clients", "duplicate-populations", "duplicate-compliance",
            "null-array-element", "null-federation-value", "unknown-federation-key", "padded-federation-key", "numeric-federation-key",
            "missing-section", "null-profile", "null-section", "missing-field", "null-list", "scalar-list"
    })
    void rejectsInvalidWireShapesWithoutMutatingTheAssessment(String scenario) throws Exception {
        ApiAssessment assessment = create();
        ObjectNode request = request();
        ObjectNode profile = (ObjectNode) request.get("profile");
        ObjectNode application = (ObjectNode) profile.get("application");
        switch (scenario) {
            case "unknown-root" -> request.put("extra", "sensitive-test-value");
            case "unknown-section" -> profile.putObject("extra");
            case "unknown-field" -> application.put("extra", "sensitive-test-value");
            case "fractional-version" -> request.put("expectedVersion", 0.9);
            case "string-version" -> request.put("expectedVersion", "0");
            case "boolean-version" -> request.put("expectedVersion", false);
            case "negative-version" -> request.put("expectedVersion", -1);
            case "unsafe-version" -> request.put("expectedVersion", 9007199254740992L);
            case "overflow-version" -> request.put("expectedVersion", new java.math.BigInteger("100000000000000000000"));
            case "missing-version" -> request.remove("expectedVersion");
            case "null-version" -> request.putNull("expectedVersion");
            case "numeric-enum" -> application.put("type", 0);
            case "numeric-string-enum" -> application.put("type", "0");
            case "unknown-enum" -> application.put("type", "sensitive-test-value");
            case "padded-enum" -> application.put("type", " UNKNOWN ");
            case "lowercase-enum" -> application.put("type", "unknown");
            case "empty-enum" -> application.put("type", "");
            case "duplicate-clients" -> application.putArray("clients").add("BROWSER").add("BROWSER");
            case "duplicate-populations" -> ((ObjectNode) profile.get("audience"))
                    .putArray("populations").add("EMPLOYEES").add("EMPLOYEES");
            case "duplicate-compliance" -> ((ObjectNode) profile.get("security"))
                    .putArray("complianceTargets").add("GDPR").add("GDPR");
            case "null-array-element" -> application.putArray("clients").addNull();
            case "null-federation-value" -> ((ObjectNode) profile.at("/protocols/federation")).putNull("OIDC");
            case "unknown-federation-key" -> ((ObjectNode) profile.at("/protocols/federation")).put("LDAP", "REQUIRED");
            case "padded-federation-key" -> ((ObjectNode) profile.at("/protocols/federation")).put(" OIDC ", "REQUIRED");
            case "numeric-federation-key" -> ((ObjectNode) profile.at("/protocols/federation")).put("0", "REQUIRED");
            case "missing-section" -> profile.remove("security");
            case "null-profile" -> request.putNull("profile");
            case "null-section" -> profile.putNull("security");
            case "missing-field" -> application.remove("type");
            case "null-list" -> application.putNull("clients");
            case "scalar-list" -> application.put("clients", "BROWSER");
            default -> throw new IllegalArgumentException(scenario);
        }
        sample(scenario, "update-assessment-profile-request", false, request);
        MvcResult result = mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.violations[0].path").isNotEmpty()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("sensitive-test-value"));
        response(scenario, result);
        JsonNode unchanged = response(scenario + "-unchanged", mvc.perform(get(assessment.path()))
                .andExpect(status().isOk()).andReturn());
        assertEquals(assessment.created(), unchanged);
        assertHistorySize(assessment, 1);
    }

    @Test
    void preservesAnIdenticalEvaluatedProfileAndStillRejectsAStaleVersion() throws Exception {
        ApiAssessment assessment = create();
        ObjectNode request = request();
        ObjectNode profile = (ObjectNode) request.get("profile");
        ((ObjectNode) profile.get("application")).put("type", "B2B_SAAS");
        ((ObjectNode) profile.get("application")).putArray("clients").add("BROWSER").add("NATIVE_MOBILE");
        ObjectNode audience = (ObjectNode) profile.get("audience");
        audience.putArray("populations").add("EXTERNAL_CUSTOMERS");
        audience.put("tenancy", "MULTI_TENANT_ORGANIZATIONS");
        audience.put("membership", "SINGLE_ORGANIZATION_PER_USER");
        ((ObjectNode) profile.get("protocols")).put("socialLogin", "FORBIDDEN");
        sample("update", "update-assessment-profile-request", true, request.deepCopy());
        response("update", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andReturn());

        var persisted = repository.findById(assessment.workspaceId(), assessment.id()).orElseThrow();
        persisted.assessment().markReadyForEvaluation();
        persisted.assessment().recordEvaluation();
        repository.update(persisted.assessment(), persisted.version());
        JsonNode evaluated = response("evaluated", mvc.perform(get(assessment.path())).andReturn());
        request.put("expectedVersion", 2);
        // Set order does not change the domain profile.
        ((ObjectNode) profile.get("application")).putArray("clients").add("NATIVE_MOBILE").add("BROWSER");
        JsonNode unchanged = response("no-op", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EVALUATED")).andReturn());
        assertEquals(evaluated, unchanged);

        request.put("expectedVersion", 1);
        response("stale-no-op", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("assessment-version-conflict")).andReturn());
        assertHistorySize(assessment, 3);
    }

    @Test
    void returnsContractProblemsForStateAndWorkspaceBoundaries() throws Exception {
        ApiAssessment assessment = create();
        ObjectNode request = request();
        ObjectNode audience = (ObjectNode) request.at("/profile/audience");
        audience.put("tenancy", "NO_ORGANIZATION_BOUNDARY");
        audience.put("membership", "SINGLE_ORGANIZATION_PER_USER");
        sample("contradiction-shape", "update-assessment-profile-request", true, request.deepCopy());
        response("contradiction", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity()).andReturn());
        assertHistorySize(assessment, 1);

        String otherPath = "/api/v1/workspaces/" + UUID.randomUUID();
        response("missing-workspace", mvc.perform(post(otherPath + "/assessments"))
                .andExpect(status().isNotFound()).andReturn());
        mvc.perform(put(otherPath)).andExpect(status().isCreated());
        response("cross-workspace-read", mvc.perform(get(otherPath + "/assessments/" + assessment.id().value()))
                .andExpect(status().isNotFound()).andReturn());
        response("cross-workspace-write", mvc.perform(put(otherPath + "/assessments/" + assessment.id().value() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())))
                .andExpect(status().isNotFound()).andReturn());
        response("invalid-uuid", mvc.perform(put("/api/v1/workspaces/not-a-uuid"))
                .andExpect(status().isBadRequest()).andReturn());

        var persisted = repository.findById(assessment.workspaceId(), assessment.id()).orElseThrow();
        persisted.assessment().archive();
        repository.update(persisted.assessment(), persisted.version());
        ObjectNode archivedRequest = request().put("expectedVersion", 1);
        response("archived", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(archivedRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid-assessment-transition")).andReturn());
        assertHistorySize(assessment, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-key", "trailing-json", "malformed-json", "empty-body"})
    void rejectsAmbiguousJsonBeforeBinding(String scenario) throws Exception {
        ApiAssessment assessment = create();
        String validBody = mapper.writeValueAsString(request());
        String body = switch (scenario) {
            case "duplicate-key" -> validBody.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0");
            case "trailing-json" -> validBody + " {}";
            case "empty-body" -> "";
            default -> "{";
        };
        response(scenario, mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request")).andReturn());
        assertEquals(assessment.created(), response(scenario + "-unchanged",
                mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @Test
    void returnsWorkspaceScopedHistoryWithExclusiveVersionCursors() throws Exception {
        var assessment = create();
        var update = request();
        ((ObjectNode) update.at("/profile/application")).put("type", "B2B_SAAS");
        response("history-update", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        assertHistorySize(assessment, 2);

        for (String resource : List.of("revisions", "events")) {
            String path = assessment.path() + "/" + resource;
            historyResponse("first-page", resource, mvc.perform(get(path).param("limit", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].version").value(0))
                    .andExpect(jsonPath("$.nextAfterVersion").value(0)).andReturn());
            var last = historyResponse("last-page", resource,
                    mvc.perform(get(path).param("limit", "1").param("afterVersion", "0"))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                            .andExpect(jsonPath("$.items[0].version").value(1))
                            .andExpect(jsonPath("$.nextAfterVersion").value(org.hamcrest.Matchers.nullValue())).andReturn());
            if (resource.equals("revisions")) {
                assertEquals("UPDATED", last.at("/items/0/origin").asText());
                assertEquals("B2B_SAAS", last.at("/items/0/profile/application/type").asText());
            } else {
                assertEquals("assessment.updated", last.at("/items/0/action").asText());
                assertEquals("core-api", last.at("/items/0/actorId").asText());
                assertEquals(1, last.at("/items/0/changedSections").size());
                assertEquals("application", last.at("/items/0/changedSections/0").asText());
                assertFalse(last.at("/items/0").has("profile"));
            }
            historyResponse("empty-page", resource, mvc.perform(get(path).param("afterVersion", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.nextAfterVersion").value(org.hamcrest.Matchers.nullValue())).andReturn());
            String other = "/api/v1/workspaces/" + UUID.randomUUID();
            mvc.perform(put(other)).andExpect(status().isCreated());
            response("cross-workspace-" + resource,
                    mvc.perform(get(other + "/assessments/" + assessment.id().value() + "/" + resource))
                            .andExpect(status().isNotFound()).andReturn());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=-1", "limit=1.5", "limit=sensitive-test-value",
            "afterVersion=-1", "afterVersion=9007199254740992", "afterVersion=9223372036854775808",
            "afterVersion=0.5", "afterVersion=sensitive-test-value"})
    void rejectsInvalidHistoryParametersWithContractProblems(String parameter) throws Exception {
        var assessment = create();
        String[] parts = parameter.split("=", 2);
        for (String resource : List.of("revisions", "events")) {
            var result = mvc.perform(get(assessment.path() + "/" + resource).param(parts[0], parts[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"))
                    .andExpect(jsonPath("$.violations[0].path").value(parts[0])).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains("sensitive-test-value"));
            response("invalid-history-" + resource + "-" + parts[0], result);
        }
        assertHistorySize(assessment, 1);
    }

    private void assertHistorySize(ApiAssessment assessment, int size) throws Exception {
        for (String resource : List.of("revisions", "events")) {
            historyResponse("history-size-" + size, resource, mvc.perform(get(assessment.path() + "/" + resource))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(size)).andReturn());
        }
    }

    private JsonNode historyResponse(String name, String resource, MvcResult result) throws Exception {
        JsonNode payload = mapper.readTree(result.getResponse().getContentAsString());
        sample(name, resource.equals("revisions") ? "assessment-revision-page" : "assessment-event-page", true, payload);
        return payload;
    }

    @Test
    void preflightIsScopedReadOnlyReproducibleAndExplicitlyPartial() throws Exception {
        var assessment = create();
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var before = response("preflight-profile", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var result = mvc.perform(get(assessment.path() + "/capability-preflight"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assessmentVersion").value(1))
                .andExpect(jsonPath("$.catalogKind").value("SYNTHETIC"))
                .andExpect(jsonPath("$.recommendationReady").value(false))
                .andExpect(jsonPath("$.scope").value("CAPABILITY_PREFLIGHT"))
                .andExpect(jsonPath("$.deferredPaths.length()").value(8))
                .andExpect(jsonPath("$.candidates[0].status").value("MATCHES_CHECKED_REQUIREMENTS"))
                .andExpect(jsonPath("$.candidates[1].status").value("DOES_NOT_MATCH"))
                .andExpect(jsonPath("$.candidates[1].checks[5].reasonCode").value("REQUIRED_CAPABILITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.candidates[2].status").value("NEEDS_INFORMATION"))
                .andExpect(jsonPath("$.candidates[2].checks[5].reasonCode").value("EVIDENCE_UNREVIEWED")).andReturn();
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample("capability-preflight", "capability-preflight", true, payload);
        var repeated = mvc.perform(get(assessment.path() + "/capability-preflight")).andExpect(status().isOk()).andReturn();
        assertEquals(payload, mapper.readTree(repeated.getResponse().getContentAsString()));
        assertEquals(before, response("preflight-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);

        String otherWorkspace = "/api/v1/workspaces/" + UUID.randomUUID();
        mvc.perform(put(otherWorkspace)).andExpect(status().isCreated());
        response("preflight-cross-workspace", mvc.perform(get(otherWorkspace + "/assessments/"
                        + assessment.id().value() + "/capability-preflight")).andExpect(status().isNotFound()).andReturn());
        response("preflight-missing", mvc.perform(get("/api/v1/workspaces/" + assessment.workspaceId().value()
                        + "/assessments/" + UUID.randomUUID() + "/capability-preflight"))
                .andExpect(status().isNotFound()).andReturn());
        response("preflight-invalid-id", mvc.perform(get("/api/v1/workspaces/not-a-uuid/assessments/"
                        + assessment.id().value() + "/capability-preflight")).andExpect(status().isBadRequest()).andReturn());
    }

    @Test
    void blankProfileNeverProducesAnAffirmativeMatch() throws Exception {
        var assessment = create();
        var result = mvc.perform(get(assessment.path() + "/capability-preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].status").value("NEEDS_INFORMATION"))
                .andExpect(jsonPath("$.candidates[1].status").value("NEEDS_INFORMATION"))
                .andExpect(jsonPath("$.candidates[2].status").value("NEEDS_INFORMATION")).andReturn();
        sample("blank-capability-preflight", "capability-preflight", true,
                mapper.readTree(result.getResponse().getContentAsString()));
        assertHistorySize(assessment, 1);
    }

    @Test
    void eligibilityIsScopedReadOnlyAndKeepsCapabilityContractUnchanged() throws Exception {
        var assessment = create();
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var before = response("eligibility-profile", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var result = mvc.perform(get(assessment.path() + "/eligibility-preflight"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assessmentVersion").value(1))
                .andExpect(jsonPath("$.workspaceId").value(assessment.workspaceId().value().toString()))
                .andExpect(jsonPath("$.assessmentId").value(assessment.id().value().toString()))
                .andExpect(jsonPath("$.catalogVersion").value("synthetic-2026-09-12.4"))
                .andExpect(jsonPath("$.catalogKind").value("SYNTHETIC"))
                .andExpect(jsonPath("$.policyVersion").value("eligibility-preflight-1"))
                .andExpect(jsonPath("$.capabilityPolicyVersion").value("capability-preflight-1"))
                .andExpect(jsonPath("$.recommendationReady").value(false))
                .andExpect(jsonPath("$.scope").value("SYNTHETIC_ELIGIBILITY_PREFLIGHT"))
                .andExpect(jsonPath("$.deferredPaths.length()").value(6))
                .andExpect(jsonPath("$.candidates[0].status").value("MATCHES_CHECKED_REQUIREMENTS"))
                .andExpect(jsonPath("$.candidates[0].capabilityChecks.length()").value(9))
                .andExpect(jsonPath("$.candidates[0].contextChecks.length()").value(6))
                .andExpect(jsonPath("$.candidates[1].status").value("DOES_NOT_MATCH"))
                .andExpect(jsonPath("$.candidates[2].status").value("NEEDS_INFORMATION"))
                .andReturn();
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample("eligibility-preflight", "eligibility-preflight", true, payload);
        var repeated = mvc.perform(get(assessment.path() + "/eligibility-preflight")).andExpect(status().isOk()).andReturn();
        assertEquals(payload, mapper.readTree(repeated.getResponse().getContentAsString()));
        var legacy = mvc.perform(get(assessment.path() + "/capability-preflight"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deferredPaths.length()").value(8))
                .andExpect(jsonPath("$.candidates[0].checks.length()").value(9))
                .andExpect(jsonPath("$.candidates[0].contextChecks").doesNotExist()).andReturn();
        sample("eligibility-legacy-contract", "capability-preflight", true, mapper.readTree(legacy.getResponse().getContentAsString()));
        assertEquals(before, response("eligibility-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);

        String otherWorkspace = "/api/v1/workspaces/" + UUID.randomUUID();
        mvc.perform(put(otherWorkspace)).andExpect(status().isCreated());
        response("eligibility-cross-workspace", mvc.perform(get(otherWorkspace + "/assessments/"
                        + assessment.id().value() + "/eligibility-preflight")).andExpect(status().isNotFound()).andReturn());
        response("eligibility-missing", mvc.perform(get("/api/v1/workspaces/" + assessment.workspaceId().value()
                        + "/assessments/" + UUID.randomUUID() + "/eligibility-preflight"))
                .andExpect(status().isNotFound()).andReturn());
        response("eligibility-invalid-id", mvc.perform(get("/api/v1/workspaces/not-a-uuid/assessments/"
                        + assessment.id().value() + "/eligibility-preflight")).andExpect(status().isBadRequest()).andReturn());
    }

    @Test
    void eligibilityReflectsChangedContextAndNeverTreatsBlankProfilesAsMatches() throws Exception {
        var assessment = create();
        var blank = mvc.perform(get(assessment.path() + "/eligibility-preflight")).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].status").value("NEEDS_INFORMATION"))
                .andExpect(jsonPath("$.candidates[1].status").value("NEEDS_INFORMATION"))
                .andExpect(jsonPath("$.candidates[2].status").value("NEEDS_INFORMATION")).andReturn();
        sample("blank-eligibility", "eligibility-preflight", true, mapper.readTree(blank.getResponse().getContentAsString()));
        assertHistorySize(assessment, 1);
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(2).get("profile"));
        }
        response("workforce-profile", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var result = mvc.perform(get(assessment.path() + "/eligibility-preflight")).andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentVersion").value(1))
                .andExpect(jsonPath("$.candidates[0].status").value("DOES_NOT_MATCH"))
                .andExpect(jsonPath("$.candidates[0].contextChecks[0].requestedValue").value("INTERNAL_WORKFORCE"))
                .andExpect(jsonPath("$.candidates[0].contextChecks[0].reasonCode").value("CONTEXT_UNSUPPORTED")).andReturn();
        sample("workforce-eligibility", "eligibility-preflight", true, mapper.readTree(result.getResponse().getContentAsString()));
        assertHistorySize(assessment, 2);
    }

    @Test
    void eligibilityContractSupportsMachineOnlyNullValuesAndAllSelectedContextValues() throws Exception {
        for (boolean machineOnly : new boolean[] { true, false }) {
            var assessment = create();
            var update = request();
            ObjectNode profile;
            try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
                profile = (ObjectNode) mapper.readTree(input).get(0).get("profile");
            }
            var clients = ((ObjectNode) profile.get("application")).putArray("clients");
            clients.add("MACHINE_TO_MACHINE");
            var populations = ((ObjectNode) profile.get("audience")).putArray("populations");
            if (!machineOnly) {
                clients.add("BROWSER").add("NATIVE_MOBILE");
                for (var value : new String[] { "CITIZENS", "EMPLOYEES", "CONTRACTORS",
                        "EXTERNAL_CUSTOMERS", "PARTNERS", "INTERNAL_OPERATORS" }) {
                    populations.add(value);
                }
            }
            update.set("profile", profile);
            response("context-boundary-profile", mvc.perform(put(assessment.path() + "/profile")
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                    .andExpect(status().isOk()).andReturn());
            var result = mvc.perform(get(assessment.path() + "/eligibility-preflight")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.candidates[0].contextChecks.length()").value(machineOnly ? 5 : 12))
                    .andExpect(jsonPath("$.candidates[0].status").value(
                            machineOnly ? "MATCHES_CHECKED_REQUIREMENTS" : "DOES_NOT_MATCH")).andReturn();
            sample("context-boundary-eligibility-" + machineOnly, "eligibility-preflight", true,
                    mapper.readTree(result.getResponse().getContentAsString()));
            assertHistorySize(assessment, 2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "SELECTED", "NOT_SELECTED", "UNKNOWN" })
    void architectureConfigurationIsTypedVersionBoundScopedAndReadOnly(String scope) throws Exception {
        for (var pattern : io.authweave.core.evaluation.ArchitecturePatternEvaluator.evaluate(ApplicationIdentityProfile.unknown())) {
            var assessment = create(); var update = request();
            var clients = ((ObjectNode) update.at("/profile/application")).putArray("clients");
            if (scope.equals("SELECTED")) clients.add(pattern.clientType().name());
            if (scope.equals("NOT_SELECTED")) clients.add(pattern.clientType().name().equals("BROWSER") ? "NATIVE_MOBILE" : "BROWSER");
            ((ObjectNode) update.at("/profile/security")).put("browserTokenExposureMinimization", "REQUIRED");
            var before = response("architecture-config-saved-" + scope + "-" + pattern.patternId(), mvc.perform(put(assessment.path() + "/profile")
                    .contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isOk()).andReturn());
            sample("architecture-config-preflight-" + scope + "-" + pattern.patternId(), "architecture-pattern-preflight", true,
                    mapper.readTree(mvc.perform(get(assessment.path() + "/architecture-pattern-preflight")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
            var history = mvc.perform(get(assessment.path() + "/revisions")).andReturn().getResponse().getContentAsString();
            var events = mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString();
            var definitions = io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.definitions(pattern.patternId());
            var matching = mapper.createObjectNode(); definitions.forEach(d -> matching.put(d.settingId().name(), d.compatibleValues().getFirst().name()));
            var variants = new java.util.LinkedHashMap<String, ObjectNode>(); variants.put("matching", matching); variants.put("empty", mapper.createObjectNode());
            var unknown = mapper.createObjectNode(); definitions.forEach(d -> unknown.put(d.settingId().name(), "UNKNOWN")); variants.put("unknown", unknown);
            var mixed = mapper.createObjectNode(); mixed.put("OAUTH_FLOW", pattern.patternId().name().equals("M2M_CLIENT_CREDENTIALS") ? "AUTHORIZATION_CODE" : "CLIENT_CREDENTIALS"); variants.put("mixed", mixed);
            for (var definition : definitions) for (var value : definition.allowedValues()) {
                var settings = matching.deepCopy(); settings.put(definition.settingId().name(), value.name());
                variants.put(definition.settingId() + "-" + value, settings);
            }
            for (var variant : variants.entrySet()) {
                String name = "architecture-config-" + scope + "-" + pattern.patternId() + "-" + variant.getKey();
                var input = mapper.createObjectNode().put("expectedVersion", 1).put("patternId", pattern.patternId().name()); input.set("settings", variant.getValue());
                var payload = architectureConfigurationSample(name, assessment.path(), input);
                assertEquals(scope, payload.at("/analysis/clientScope").asText());
                assertEquals(input.get("settings"), payload.at("/analysis/settings"));
                assertEquals(1, payload.at("/preflight/assessmentVersion").asLong());
                assertEquals("2026-09-12T12:00:00Z", payload.at("/preflight/evaluatedAt").asText());
                if (scope.equals("SELECTED") && pattern.patternId().name().equals("SPA_CODE_PKCE"))
                    assertEquals("NEEDS_INFORMATION", payload.at("/preflight/patterns/2/status").asText());
                if (variant.getKey().equals("matching")) {
                    var repeated = mvc.perform(post(assessment.path() + "/architecture-configuration-preview").contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isOk()).andReturn();
                    assertEquals(payload, mapper.readTree(repeated.getResponse().getContentAsString()));
                    for (String flag : List.of("configurationObserved", "configurationVerified", "providerCompatibilityVerified", "runtimeFlowVerified", "recommendationReady", "publicationReady", "writesPerformed", "approvalGranted")) {
                        var forged = (ObjectNode) payload.deepCopy(); ((ObjectNode) forged.get("analysis")).put(flag, true);
                        sample("architecture-config-forged-" + flag, "architecture-configuration-preview", false, forged);
                    }
                    for (String mutation : List.of("missing-check", "reordered-check", "duplicate-check", "wrong-status", "wrong-reason", "foreign-definition")) {
                        var forged = (ObjectNode) payload.deepCopy(); var analysis = (ObjectNode) forged.get("analysis"); var checks = analysis.withArray("checks");
                        switch (mutation) {
                            case "missing-check" -> checks.remove(0);
                            case "reordered-check" -> { var first = checks.get(0).deepCopy(); checks.set(0, checks.get(1)); checks.set(1, first); }
                            case "duplicate-check" -> checks.set(1, checks.get(0).deepCopy());
                            case "wrong-status" -> analysis.put("status", scope.equals("NOT_SELECTED") ? "CONDITIONALLY_MATCHES" : "NOT_APPLICABLE");
                            case "wrong-reason" -> ((ObjectNode) checks.get(0)).put("reasonCode", scope.equals("SELECTED") ? "SETTING_UNKNOWN" : "EXPECTED_SETTING_DECLARED");
                            default -> ((ObjectNode) forged.withArray("settingDefinitions").get(0)).put("settingId", "NATIVE_USER_AGENT");
                        }
                        sample("architecture-config-forged-" + mutation, "architecture-configuration-preview", false, forged);
                    }
                }
            }
            assertEquals(before, response("architecture-config-preserved", mvc.perform(get(assessment.path())).andReturn()));
            assertEquals(history, mvc.perform(get(assessment.path() + "/revisions")).andReturn().getResponse().getContentAsString());
            assertEquals(events, mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString());
            assertHistorySize(assessment, 2);
        }
    }

    private JsonNode architectureConfigurationSample(String name, String assessmentPath, ObjectNode input) throws Exception {
        sample(name + "-request", "architecture-configuration-request", true, input.deepCopy());
        var result = mvc.perform(post(assessmentPath + "/architecture-configuration-preview").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.analysis.configurationObserved").value(false)).andExpect(jsonPath("$.analysis.writesPerformed").value(false)).andReturn();
        var payload = mapper.readTree(result.getResponse().getContentAsString()); sample(name, "architecture-configuration-preview", true, payload); return payload;
    }

    @Test void architectureConfigurationRejectsInvalidTypesAuthorityStaleAndForeignRequests() throws Exception {
        var assessment = create(); String path = assessment.path() + "/architecture-configuration-preview";
        var input = mapper.createObjectNode().put("expectedVersion", 0).put("patternId", "BFF_SESSION"); input.putObject("settings");
        sample("architecture-config-saved-empty", "assessment-response", true, assessment.created());
        sample("architecture-config-preflight-empty", "architecture-pattern-preflight", true,
                mapper.readTree(mvc.perform(get(assessment.path() + "/architecture-pattern-preflight")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        architectureConfigurationSample("architecture-config-empty", assessment.path(), input);
        for (String mutation : List.of("missing-version", "negative", "unsafe", "string", "fraction", "boolean", "missing-pattern", "null-pattern", "ordinal-pattern", "foreign-pattern", "missing-settings", "null-settings", "foreign-setting", "mismatched-value", "ordinal-value", "null-value", "object-value", "free-text", "scope", "requirements", "time", "observed", "evidence", "ready", "secret", "url")) {
            var invalid = input.deepCopy(); var settings = (ObjectNode) invalid.get("settings");
            switch (mutation) {
                case "missing-version" -> invalid.remove("expectedVersion");
                case "negative" -> invalid.put("expectedVersion", -1);
                case "unsafe" -> invalid.put("expectedVersion", 9007199254740992L);
                case "string" -> invalid.put("expectedVersion", "0");
                case "fraction" -> invalid.put("expectedVersion", 0.5);
                case "boolean" -> invalid.put("expectedVersion", true);
                case "missing-pattern" -> invalid.remove("patternId");
                case "null-pattern" -> invalid.putNull("patternId");
                case "ordinal-pattern" -> invalid.put("patternId", 0);
                case "foreign-pattern" -> invalid.put("patternId", "OTHER");
                case "missing-settings" -> invalid.remove("settings");
                case "null-settings" -> invalid.putNull("settings");
                case "foreign-setting" -> settings.put("NATIVE_USER_AGENT", "EXTERNAL_BROWSER");
                case "mismatched-value" -> settings.put("PKCE_METHOD", "AUTHORIZATION_CODE");
                case "ordinal-value" -> settings.put("PKCE_METHOD", 1);
                case "null-value" -> settings.putNull("PKCE_METHOD");
                case "object-value" -> settings.putObject("PKCE_METHOD");
                case "free-text" -> settings.put("PKCE_METHOD", "synthetic-private-value");
                case "scope" -> invalid.put("clientScope", "SELECTED");
                case "requirements" -> invalid.putObject("requirements");
                case "time" -> invalid.put("evaluatedAt", "2026-10-05T00:00:00Z");
                case "observed" -> invalid.put("configurationObserved", true);
                case "evidence" -> invalid.putArray("evidence");
                case "ready" -> invalid.put("recommendationReady", true);
                case "secret" -> invalid.put("clientSecret", "synthetic-private-value");
                default -> invalid.put("issuer", "https://synthetic.example.test");
            }
            sample("architecture-config-invalid-" + mutation, "architecture-configuration-request", false, invalid);
            var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(invalid.toString())).andExpect(status().isBadRequest()).andReturn();
            response("architecture-config-invalid-problem", result); assertFalse(result.getResponse().getContentAsString().contains("synthetic-private-value"));
        }
        for (String duplicate : List.of("{\"expectedVersion\":0,\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"settings\":{}}",
                "{\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"settings\":{\"PKCE_METHOD\":\"S256\",\"PKCE_METHOD\":\"NONE\"}}"))
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(duplicate)).andExpect(status().isBadRequest());
        mvc.perform(post(path).queryParam("expectedVersion", "0").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        input.put("expectedVersion", 1); response("architecture-config-stale", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isConflict()).andReturn());
        input.put("expectedVersion", 0); response("architecture-config-foreign", mvc.perform(post("/api/v1/workspaces/" + UUID.randomUUID() + "/assessments/" + assessment.id().value() + "/architecture-configuration-preview")
                .contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isNotFound()).andReturn());
        assertEquals(assessment.created(), response("architecture-config-invalid-preserved", mvc.perform(get(assessment.path())).andReturn())); assertHistorySize(assessment, 1);
    }

    @Test void architectureConfigurationReadsV6WithoutDowngradingOrWriting() throws Exception {
        var assessment = create(); String v6 = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("architecture-config-v6-initial", "assessment-response.v6", mvc.perform(get(v6)).andExpect(status().isOk()).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile").deepCopy());
        ((ObjectNode) update.at("/profile/application")).putArray("clients").add("BROWSER");
        ((ObjectNode) update.at("/profile/security")).put("auditability", "REQUIRED").put("browserTokenExposureMinimization", "REQUIRED");
        ((ObjectNode) update.at("/profile/security/auditabilityRequirements")).putArray("selectedCriteria").add("AUTHENTICATION_SUCCESS_EVENTS");
        var before = saveV6("architecture-config-saved-v6", v6, update);
        sample("architecture-config-preflight-v6", "architecture-pattern-preflight", true,
                mapper.readTree(mvc.perform(get(assessment.path() + "/architecture-pattern-preflight")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        var history = versionedSample("architecture-config-v6-history", "assessment-revision-page.v6", mvc.perform(get(v6 + "/revisions")).andReturn());
        var events = historyResponse("architecture-config-v6-events", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var input = mapper.createObjectNode().put("expectedVersion", 1).put("patternId", "SPA_CODE_PKCE"); input.putObject("settings").put("PKCE_METHOD", "S256");
        architectureConfigurationSample("architecture-config-v6", assessment.path(), input);
        response("architecture-config-v1-denied", mvc.perform(get(assessment.path())).andExpect(status().isConflict()).andReturn());
        assertEquals(before, versionedSample("architecture-config-v6-preserved", "assessment-response.v6", mvc.perform(get(v6)).andReturn()));
        assertEquals(history, versionedSample("architecture-config-v6-history-preserved", "assessment-revision-page.v6", mvc.perform(get(v6 + "/revisions")).andReturn()));
        assertEquals(events, historyResponse("architecture-config-v6-events-preserved", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "SELECTED", "NOT_SELECTED", "UNKNOWN" })
    void prerequisitePreviewIsVersionBoundConditionalAndReadOnly(String scope) throws Exception {
        var definitions = io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.DEFINITIONS;
        for (var pattern : io.authweave.core.evaluation.ArchitecturePatternEvaluator.evaluate(ApplicationIdentityProfile.unknown())) {
            var assessment = create();
            var update = request();
            var profile = (ObjectNode) update.get("profile");
            var clients = ((ObjectNode) profile.get("application")).putArray("clients");
            if (scope.equals("SELECTED")) clients.add(pattern.clientType().name());
            if (scope.equals("NOT_SELECTED")) clients.add(pattern.clientType().name().equals("BROWSER") ? "NATIVE_MOBILE" : "BROWSER");
            ((ObjectNode) profile.get("security")).put("browserTokenExposureMinimization", "REQUIRED");
            var before = response("prerequisite-profile", mvc.perform(put(assessment.path() + "/profile")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                    .andExpect(status().isOk()).andReturn());
            for (String declaration : List.of("SATISFIED", "NOT_SATISFIED", "UNKNOWN")) {
                var input = mapper.createObjectNode().put("expectedVersion", 1).put("patternId", pattern.patternId().name());
                var declarations = input.putObject("declarations");
                definitions.stream().filter(d -> d.patternId() == pattern.patternId())
                        .forEach(d -> declarations.put(d.prerequisiteId().name(), declaration));
                sample("prerequisite-input-" + scope + pattern.patternId() + declaration,
                        "architecture-prerequisite-request", true, input);
                var result = mvc.perform(post(assessment.path() + "/architecture-prerequisite-preview")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
                        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.analysis.clientScope").value(scope))
                        .andExpect(jsonPath("$.analysis.configurationVerified").value(false))
                        .andExpect(jsonPath("$.analysis.providerCompatibilityVerified").value(false))
                        .andExpect(jsonPath("$.analysis.recommendationReady").value(false))
                        .andExpect(jsonPath("$.analysis.status").value(scope.equals("NOT_SELECTED") ? "NOT_APPLICABLE" :
                                scope.equals("UNKNOWN") || declaration.equals("UNKNOWN") ? "NEEDS_INFORMATION" :
                                declaration.equals("SATISFIED") ? "CONDITIONALLY_MATCHES" : "CONDITIONALLY_DOES_NOT_MATCH"))
                        .andReturn();
                var payload = mapper.readTree(result.getResponse().getContentAsString());
                sample("prerequisite-output-" + scope + pattern.patternId() + declaration,
                        "architecture-prerequisite-preview", true, payload);
                assertEquals(input.get("declarations"), payload.get("declarations"));
                assertEquals(1, payload.get("preflight").get("assessmentVersion").asLong());
                if (scope.equals("SELECTED") && pattern.patternId().name().equals("SPA_CODE_PKCE")) {
                    assertEquals("NEEDS_INFORMATION", payload.get("preflight").get("patterns").get(2).get("status").asText());
                }
                for (String claim : List.of("configurationVerified", "providerCompatibilityVerified", "recommendationReady", "approvalGranted")) {
                    var forged = (ObjectNode) payload.deepCopy();
                    ((ObjectNode) forged.get("analysis")).put(claim, true);
                    sample("prerequisite-cannot-claim-" + claim, "architecture-prerequisite-preview", false, forged);
                }
                var wrongStatus = (ObjectNode) payload.deepCopy();
                ((ObjectNode) wrongStatus.get("analysis")).put("status", "NOT_APPLICABLE");
                if (!scope.equals("NOT_SELECTED")) sample("prerequisite-inconsistent-status", "architecture-prerequisite-preview", false, wrongStatus);
            }
            assertEquals(before, response("prerequisite-unchanged", mvc.perform(get(assessment.path())).andReturn()));
            assertHistorySize(assessment, 2);
        }
    }

    @Test
    void prerequisitePreviewRejectsStaleForeignAndMalformedRequests() throws Exception {
        var assessment = create();
        String path = assessment.path() + "/architecture-prerequisite-preview";
        var input = mapper.createObjectNode().put("expectedVersion", 0).put("patternId", "BFF_SESSION");
        input.putObject("declarations");
        var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysis.status").value("NEEDS_INFORMATION")).andReturn();
        sample("prerequisite-empty-input", "architecture-prerequisite-request", true, input.deepCopy());
        sample("prerequisite-empty-output", "architecture-prerequisite-preview", true, mapper.readTree(result.getResponse().getContentAsString()));
        for (String invalid : List.of(
                "{\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"declarations\":{\"SPA_TOKEN_THREAT_MODEL\":\"SATISFIED\"}}",
                "{\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"declarations\":{\"BFF_SESSION_DEFENSES\":null}}",
                "{\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"declarations\":{\"BFF_SESSION_DEFENSES\":\"VERIFIED\"}}",
                "{\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"declarations\":{},\"clientScope\":\"SELECTED\"}",
                "{\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"declarations\":{},\"approvalGranted\":true}",
                "{\"expectedVersion\":-1,\"patternId\":\"BFF_SESSION\",\"declarations\":{}}",
                "{\"expectedVersion\":9007199254740992,\"patternId\":\"BFF_SESSION\",\"declarations\":{}}",
                "{\"expectedVersion\":\"0\",\"patternId\":\"BFF_SESSION\",\"declarations\":{}}",
                "{\"expectedVersion\":0,\"patternId\":null,\"declarations\":{}}",
                "{\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\"}")) {
            sample("prerequisite-invalid-input", "architecture-prerequisite-request", false, mapper.readTree(invalid));
            response("prerequisite-invalid-problem", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andReturn());
        }
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"expectedVersion\":0,\"patternId\":\"BFF_SESSION\",\"declarations\":{}}"))
                .andExpect(status().isBadRequest());
        input.put("expectedVersion", 1);
        response("prerequisite-stale", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isConflict()).andReturn());
        input.put("expectedVersion", 0);
        response("prerequisite-other-workspace", mvc.perform(post("/api/v1/workspaces/" + UUID.randomUUID() +
                        "/assessments/" + assessment.id().value() + "/architecture-prerequisite-preview")
                .contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isNotFound()).andReturn());
        assertEquals(assessment.created(), response("prerequisite-original", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @Test
    void patternPreflightIsScopedReadOnlyAndExplicitlyPartial() throws Exception {
        var assessment = create();
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var before = response("pattern-profile", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        String path = assessment.path() + "/architecture-pattern-preflight";
        var result = mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(assessment.workspaceId().value().toString()))
                .andExpect(jsonPath("$.assessmentId").value(assessment.id().value().toString()))
                .andExpect(jsonPath("$.assessmentVersion").value(1))
                .andExpect(jsonPath("$.policyVersion").value("architecture-pattern-preflight-1"))
                .andExpect(jsonPath("$.scope").value("ARCHITECTURE_PATTERN_PREFLIGHT"))
                .andExpect(jsonPath("$.recommendationReady").value(false))
                .andExpect(jsonPath("$.selectedClients[0]").value("BROWSER"))
                .andExpect(jsonPath("$.browserTokenExposureRequirement").value("PREFERRED"))
                .andExpect(jsonPath("$.patterns.length()").value(5))
                .andExpect(jsonPath("$.patterns[0].patternId").value("BFF_SESSION"))
                .andExpect(jsonPath("$.patterns[1].patternId").value("SERVER_SIDE_SESSION"))
                .andExpect(jsonPath("$.patterns[2].patternId").value("SPA_CODE_PKCE"))
                .andExpect(jsonPath("$.patterns[0].status").value("MATCHES_CHECKED_REQUIREMENTS"))
                .andExpect(jsonPath("$.patterns[2].status").value("MATCHES_CHECKED_REQUIREMENTS"))
                .andExpect(jsonPath("$.patterns[3].status").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.patterns[4].status").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.checkedPaths.length()").value(2))
                .andExpect(jsonPath("$.deferredPaths.length()").value(10)).andReturn();
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample("architecture-pattern-preflight", "architecture-pattern-preflight", true, payload);
        var repeated = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        assertEquals(payload, mapper.readTree(repeated.getResponse().getContentAsString()));
        assertEquals(before, response("pattern-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);

        ObjectNode falseRecommendation = (ObjectNode) payload.deepCopy();
        falseRecommendation.put("recommendationReady", true);
        sample("pattern-cannot-claim-recommendation", "architecture-pattern-preflight", false, falseRecommendation);
        ObjectNode falseWinner = (ObjectNode) payload.deepCopy();
        falseWinner.put("winner", "BFF_SESSION");
        sample("pattern-cannot-claim-winner", "architecture-pattern-preflight", false, falseWinner);

        String otherWorkspace = "/api/v1/workspaces/" + UUID.randomUUID();
        mvc.perform(put(otherWorkspace)).andExpect(status().isCreated());
        response("pattern-cross-workspace", mvc.perform(get(otherWorkspace + "/assessments/"
                        + assessment.id().value() + "/architecture-pattern-preflight"))
                .andExpect(status().isNotFound()).andReturn());
        response("pattern-missing", mvc.perform(get("/api/v1/workspaces/" + assessment.workspaceId().value()
                        + "/assessments/" + UUID.randomUUID() + "/architecture-pattern-preflight"))
                .andExpect(status().isNotFound()).andReturn());
        response("pattern-invalid-id", mvc.perform(get("/api/v1/workspaces/" + assessment.workspaceId().value()
                        + "/assessments/not-a-uuid/architecture-pattern-preflight"))
                .andExpect(status().isBadRequest()).andReturn());
    }

    @ParameterizedTest
    @ValueSource(strings = { "REQUIRED", "FORBIDDEN", "NO_CLIENTS", "NATIVE_ONLY", "MIXED" })
    void patternPreflightHandlesUncertaintyAndClientBoundaries(String scenario) throws Exception {
        var assessment = create();
        var update = request();
        var profile = (ObjectNode) update.get("profile");
        ((ObjectNode) profile.get("application")).put("type", "B2B_SAAS");
        var clients = ((ObjectNode) profile.get("application")).putArray("clients");
        if (scenario.equals("NATIVE_ONLY")) clients.add("NATIVE_MOBILE");
        else if (!scenario.equals("NO_CLIENTS")) clients.add("BROWSER");
        if (scenario.equals("MIXED")) clients.add("MACHINE_TO_MACHINE").add("NATIVE_MOBILE");
        var criticality = scenario.equals("REQUIRED") || scenario.equals("FORBIDDEN") ? scenario : "UNKNOWN";
        ((ObjectNode) profile.get("security")).put("browserTokenExposureMinimization", criticality);
        var before = response("pattern-boundary-profile", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var result = mvc.perform(get(assessment.path() + "/architecture-pattern-preflight")).andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentVersion").value(1)).andReturn();
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample("pattern-boundary-" + scenario, "architecture-pattern-preflight", true, payload);
        var patterns = payload.get("patterns");
        switch (scenario) {
            case "REQUIRED" -> {
                assertEquals("MATCHES_CHECKED_REQUIREMENTS", patterns.get(0).get("status").asText());
                assertEquals("NEEDS_INFORMATION", patterns.get(2).get("status").asText());
                assertEquals("ACCEPTABLE_EXPOSURE_UNDEFINED", patterns.get(2).get("checks").get(1).get("reasonCode").asText());
            }
            case "FORBIDDEN" -> {
                for (int i = 0; i < 3; i++) {
                    assertEquals("NEEDS_INFORMATION", patterns.get(i).get("status").asText());
                    assertEquals("MINIMIZATION_PROHIBITION_UNDEFINED",
                            patterns.get(i).get("checks").get(1).get("reasonCode").asText());
                }
            }
            case "NO_CLIENTS" -> {
                for (var pattern : patterns) assertEquals("NEEDS_INFORMATION", pattern.get("status").asText());
            }
            case "NATIVE_ONLY" -> {
                assertEquals("NOT_APPLICABLE", patterns.get(0).get("status").asText());
                assertEquals("MATCHES_CHECKED_REQUIREMENTS", patterns.get(3).get("status").asText());
                assertEquals("BROWSER_CRITERION_NOT_APPLICABLE", patterns.get(3).get("checks").get(1).get("reasonCode").asText());
            }
            case "MIXED" -> {
                assertEquals(List.of("BROWSER", "MACHINE_TO_MACHINE", "NATIVE_MOBILE"),
                        mapper.convertValue(payload.get("selectedClients"), List.class));
                assertEquals("NEEDS_INFORMATION", patterns.get(0).get("status").asText());
                assertEquals("MATCHES_CHECKED_REQUIREMENTS", patterns.get(3).get("status").asText());
                assertEquals("MATCHES_CHECKED_REQUIREMENTS", patterns.get(4).get("status").asText());
            }
            default -> throw new AssertionError(scenario);
        }
        assertEquals(before, response("pattern-boundary-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);
    }

    @Test
    void residencyV2PreservesLegacyReadsAndBlocksLossyLegacyWrites() throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v2/");
        var initial = v2Response("v2-project-legacy", mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
        assertEquals(2, initial.get("profileSchemaVersion").asInt());
        assertEquals(0, initial.at("/profile/security/dataResidencyDetails/allowedCountries").size());
        assertEquals(assessment.created(), response("v1-after-projection", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 1);

        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", initial.get("profile").deepCopy());
        sample("v2-unrecorded-request", "update-assessment-profile-request.v2", true, update);
        assertEquals(initial, v2Response("v2-projection-no-op", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn()));
        assertHistorySize(assessment, 1);
        var security = (ObjectNode) update.get("profile").get("security");
        security.put("dataResidency", "REQUIRED");
        var details = (ObjectNode) security.get("dataResidencyDetails");
        details.putArray("allowedCountries").add("DE").add("FR");
        details.putArray("dataCategories").add("USER_PROFILES").add("BACKUPS");
        sample("v2-residency-request", "update-assessment-profile-request.v2", true, update);
        var saved = v2Response("v2-residency-saved", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        assertEquals(1, saved.get("version").asLong());
        assertEquals(2, saved.at("/profile/security/dataResidencyDetails/allowedCountries").size());
        assertEquals(saved, v2Response("v2-residency-loaded", mvc.perform(get(path)).andExpect(status().isOk()).andReturn()));
        update.put("expectedVersion", 1);
        assertEquals(saved, v2Response("v2-residency-no-op", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn()));
        update.put("expectedVersion", 0);
        response("v2-residency-stale", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isConflict()).andReturn());

        response("v1-residency-read-blocked", mvc.perform(get(assessment.path())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
        var legacy = request();
        response("v1-residency-stale-write", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(legacy)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("assessment-version-conflict")).andReturn());
        legacy.put("expectedVersion", 1);
        response("v1-residency-write-blocked", mvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(legacy)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
        assertEquals(saved, v2Response("v2-after-legacy-block", mvc.perform(get(path)).andReturn()));
        var history = v2History("v2-original-snapshots", path);
        assertEquals(2, history.get("items").size());
        assertEquals(1, history.at("/items/0/profileSchemaVersion").asInt());
        assertEquals(2, history.at("/items/1/profileSchemaVersion").asInt());
        assertEquals(assessment.created().get("profile"), history.at("/items/0/profile"));
        assertEquals(saved.get("profile"), history.at("/items/1/profile"));
        response("v1-mixed-history-blocked", mvc.perform(get(assessment.path() + "/revisions")).andExpect(status().isConflict()).andReturn());
        var legacyPage = mvc.perform(get(assessment.path() + "/revisions?limit=1")).andExpect(status().isOk()).andReturn();
        historyResponse("v1-older-page-preserved", "revisions", legacyPage);
        var events = historyResponse("residency-security-event", "events",
                mvc.perform(get(assessment.path() + "/events")).andExpect(status().isOk()).andReturn());
        assertEquals(2, events.get("items").size());
        assertEquals("security", events.at("/items/1/changedSections/0").asText());
        assertEquals(1, events.at("/items/1/changedSections").size());

        String other = path.replace(assessment.workspaceId().value().toString(), UUID.randomUUID().toString());
        response("v2-cross-workspace", mvc.perform(get(other)).andExpect(status().isNotFound()).andReturn());
        response("v2-history-cross-workspace", mvc.perform(get(other + "/revisions")).andExpect(status().isNotFound()).andReturn());
        response("v2-write-cross-workspace", mvc.perform(put(other + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isNotFound()).andReturn());

        // An intentional v2 clear may return to a lossless v1 storage representation; old history stays intact.
        update.put("expectedVersion", 1);
        details.putArray("allowedCountries");
        details.putArray("dataCategories");
        var cleared = v2Response("v2-clear-details", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        assertEquals(2, cleared.get("version").asLong());
        response("v1-readable-after-explicit-clear", mvc.perform(get(assessment.path())).andExpect(status().isOk()).andReturn());
        var after = v2History("v2-history-after-clear", path);
        assertEquals(history.get("items").get(0), after.get("items").get(0));
        assertEquals(history.get("items").get(1), after.get("items").get(1));
        assertEquals(1, after.at("/items/2/profileSchemaVersion").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = { "missing-details", "null-details", "missing-countries", "null-country", "lowercase-country",
            "unknown-country", "duplicate-country", "duplicate-category", "unknown-category", "extra-field", "unsafe-version" })
    void rejectsInvalidResidencyV2WithoutChangingData(String scenario) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v2/");
        var before = v2Response("v2-before-invalid", mvc.perform(get(path)).andReturn());
        var request = mapper.createObjectNode().put("expectedVersion", 0);
        request.set("profile", before.get("profile").deepCopy());
        var security = (ObjectNode) request.get("profile").get("security");
        var details = (ObjectNode) security.get("dataResidencyDetails");
        switch (scenario) {
            case "missing-details" -> security.remove("dataResidencyDetails");
            case "null-details" -> security.putNull("dataResidencyDetails");
            case "missing-countries" -> details.remove("allowedCountries");
            case "null-country" -> details.putArray("allowedCountries").addNull();
            case "lowercase-country" -> details.putArray("allowedCountries").add("de");
            case "unknown-country" -> details.putArray("allowedCountries").add("ZZ");
            case "duplicate-country" -> details.putArray("allowedCountries").add("DE").add("DE");
            case "duplicate-category" -> details.putArray("dataCategories").add("BACKUPS").add("BACKUPS");
            case "unknown-category" -> details.putArray("dataCategories").add("EVERYTHING");
            case "extra-field" -> details.put("compliant", true);
            case "unsafe-version" -> request.put("expectedVersion", 9007199254740992L);
            default -> throw new AssertionError(scenario);
        }
        boolean shapeValid = scenario.equals("unknown-country");
        sample("v2-invalid-" + scenario, "update-assessment-profile-request.v2", shapeValid, request);
        response("v2-invalid-" + scenario, mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().is(shapeValid ? 422 : 400)).andReturn());
        assertEquals(before, v2Response("v2-invalid-unchanged", mvc.perform(get(path)).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @Test
    void createsV2AssessmentsAndPreservesExistingPreflightContracts() throws Exception {
        var workspace = UUID.randomUUID();
        mvc.perform(put("/api/v1/workspaces/" + workspace)).andExpect(status().isCreated());
        var result = mvc.perform(post("/api/v2/workspaces/" + workspace + "/assessments")).andExpect(status().isCreated()).andReturn();
        var created = v2Response("v2-create", result);
        assertEquals(2, created.get("profileSchemaVersion").asInt());
        String path = result.getResponse().getHeader("Location");
        assertEquals(created, v2Response("v2-create-read", mvc.perform(get(path)).andReturn()));
        var request = mapper.createObjectNode().put("expectedVersion", 0);
        request.set("profile", created.get("profile").deepCopy());
        ((ObjectNode) request.at("/profile/security/dataResidencyDetails")).putArray("allowedCountries").add("CA");
        v2Response("v2-partial-details", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn());
        for (String endpoint : List.of("capability-preflight", "eligibility-preflight", "architecture-pattern-preflight")) {
            var preflight = mvc.perform(get(path.replace("/api/v2/", "/api/v1/") + "/" + endpoint))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.recommendationReady").value(false)).andReturn();
            sample("v2-" + endpoint, endpoint, true, mapper.readTree(preflight.getResponse().getContentAsString()));
        }
        response("v2-history-invalid-page", mvc.perform(get(path + "/revisions?limit=0")).andExpect(status().isBadRequest()).andReturn());
        response("v2-invalid-id", mvc.perform(get("/api/v2/workspaces/not-a-uuid/assessments/" + UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andReturn());
    }

    @Test
    void archivedV2ProfilesAndVersionedHistoryRemainReadableButNotEditable() throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v2/");
        var initial = v2Response("v2-before-archive", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", initial.get("profile").deepCopy());
        var details = (ObjectNode) update.at("/profile/security/dataResidencyDetails");
        details.putArray("dataCategories").add("AUDIT_LOGS");
        v2Response("v2-scope-only", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        var persisted = repository.findById(assessment.workspaceId(), assessment.id()).orElseThrow();
        persisted.assessment().archive();
        repository.update(persisted.assessment(), persisted.version());
        var before = v2Response("v2-archived-read", mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
        assertEquals("ARCHIVED", before.get("status").asText());
        var history = v2History("v2-archived-history", path);
        assertEquals(3, history.get("items").size());
        update.put("expectedVersion", 2);
        response("v2-archived-update", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update))).andExpect(status().isConflict()).andReturn());
        assertEquals(before, v2Response("v2-archived-unchanged", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v2History("v2-archived-history-unchanged", path));
        var page = mvc.perform(get(path + "/revisions?afterVersion=0&limit=1")).andExpect(status().isOk()).andReturn();
        var payload = mapper.readTree(page.getResponse().getContentAsString());
        sample("v2-history-page", "assessment-revision-page.v2", true, payload);
        assertEquals(history.at("/items/1"), payload.at("/items/0"));
        assertEquals(1, payload.get("nextAfterVersion").asInt());
    }

    @Test
    void residencyEligibilityIsCombinedReadOnlyAndSensitiveToTheAllowlist() throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v2/");
        var update = residencyRequest("REQUIRED");
        var details = (ObjectNode) update.at("/profile/security/dataResidencyDetails");
        details.putArray("allowedCountries").add("DE").add("FR");
        details.putArray("dataCategories").add("USER_PROFILES").add("BACKUPS");
        var before = v2Response("residency-evaluation-profile", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var revisions = v2History("residency-evaluation-history-before", path);
        var events = historyResponse("residency-evaluation-events-before", "events",
                mvc.perform(get(assessment.path() + "/events")).andExpect(status().isOk()).andReturn());
        var payload = residencyPreflight("residency-eligibility", path);
        assertEquals("eligibility-preflight-2", payload.get("policyVersion").asText());
        assertEquals("residency-preflight-1", payload.get("residencyPolicyVersion").asText());
        assertEquals("eligibility-preflight-1", payload.get("contextPolicyVersion").asText());
        assertEquals("capability-preflight-1", payload.get("capabilityPolicyVersion").asText());
        assertEquals("synthetic-2026-09-12.4", payload.get("catalogVersion").asText());
        assertEquals(before.get("version"), payload.get("assessmentVersion"));
        assertEquals(before.get("workspaceId"), payload.get("workspaceId"));
        assertEquals(before.get("id"), payload.get("assessmentId"));
        assertEquals("SYNTHETIC", payload.get("catalogKind").asText());
        assertEquals("SYNTHETIC_ELIGIBILITY_PREFLIGHT", payload.get("scope").asText());
        assertEquals(5, payload.get("deferredPaths").size());
        assertEquals("MATCHES_CHECKED_REQUIREMENTS", payload.at("/candidates/0/status").asText());
        assertEquals("DOES_NOT_MATCH", payload.at("/candidates/1/status").asText());
        assertEquals("NEEDS_INFORMATION", payload.at("/candidates/2/status").asText());
        assertEquals("EVIDENCE_MISSING", payload.at("/candidates/2/residencyChecks/0/reasonCode").asText());
        assertEquals("STORAGE_LOCATIONS_INCOMPLETE", payload.at("/candidates/2/residencyChecks/1/reasonCode").asText());
        assertEquals(payload, residencyPreflight("residency-repeat", path));
        assertEquals(before, v2Response("residency-read-only", mvc.perform(get(path)).andReturn()));
        assertEquals(revisions, v2History("residency-history-read-only", path));
        assertEquals(events, historyResponse("residency-events-read-only", "events",
                mvc.perform(get(assessment.path() + "/events")).andReturn()));

        var legacy = mvc.perform(get(assessment.path() + "/eligibility-preflight")).andExpect(status().isOk()).andReturn();
        var oldPayload = mapper.readTree(legacy.getResponse().getContentAsString());
        sample("residency-legacy-eligibility", "eligibility-preflight", true, oldPayload);
        assertEquals(6, oldPayload.get("deferredPaths").size());
        assertFalse(oldPayload.at("/candidates/0").has("residencyChecks"));
        for (String group : List.of("capabilityChecks", "contextChecks")) {
            assertEquals(oldPayload.at("/candidates/0/" + group), payload.at("/candidates/0/" + group));
        }
        details.putArray("allowedCountries").add("DE");
        update.put("expectedVersion", 1);
        var restricted = v2Response("residency-restricted-profile", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var blocked = residencyPreflight("residency-backup-exclusion", path);
        assertEquals("DOES_NOT_MATCH", blocked.at("/candidates/0/status").asText());
        assertEquals("FR", blocked.at("/candidates/0/residencyChecks/0/outsideAllowedCountries/0").asText());
        assertEquals("PASS", blocked.at("/candidates/0/residencyChecks/1/outcome").asText());
        assertEquals(restricted, v2Response("residency-restricted-read-only", mvc.perform(get(path)).andReturn()));

        var falseRecommendation = (ObjectNode) payload.deepCopy();
        falseRecommendation.put("recommendationReady", true);
        sample("residency-no-final-recommendation", "eligibility-preflight.v2", false, falseRecommendation);
        var falseWinner = (ObjectNode) payload.deepCopy();
        falseWinner.put("winner", "fictional-complete");
        sample("residency-no-winner", "eligibility-preflight.v2", false, falseWinner);

        var persisted = repository.findById(assessment.workspaceId(), assessment.id()).orElseThrow();
        persisted.assessment().archive();
        repository.update(persisted.assessment(), persisted.version());
        var archived = v2Response("residency-archived-before", mvc.perform(get(path)).andReturn());
        var archivedHistory = v2History("residency-archived-history-before", path);
        assertEquals("DOES_NOT_MATCH", residencyPreflight("residency-archived", path).at("/candidates/0/status").asText());
        assertEquals(archived, v2Response("residency-archived-after", mvc.perform(get(path)).andReturn()));
        assertEquals(archivedHistory, v2History("residency-archived-history-after", path));

        String other = path.replace(assessment.workspaceId().value().toString(), UUID.randomUUID().toString());
        response("residency-cross-workspace", mvc.perform(get(other + "/eligibility-preflight")).andExpect(status().isNotFound()).andReturn());
        response("residency-missing-assessment", mvc.perform(get("/api/v2/workspaces/" + assessment.workspaceId().value()
                + "/assessments/" + UUID.randomUUID() + "/eligibility-preflight")).andExpect(status().isNotFound()).andReturn());
        response("residency-invalid-id", mvc.perform(get("/api/v2/workspaces/not-a-uuid/assessments/"
                + assessment.id().value() + "/eligibility-preflight")).andExpect(status().isBadRequest()).andReturn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN",
            "NO_SCOPE", "NO_COUNTRIES", "NO_DETAILS", "ALL_CATEGORIES"})
    void residencyEligibilityExposesCriticalityUncertaintyAndCategoryBoundaries(String scenario) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v2/");
        var blank = residencyPreflight("residency-blank", path);
        assertEquals("NEEDS_INFORMATION", blank.at("/candidates/0/status").asText());
        assertEquals("REQUIREMENT_UNKNOWN", blank.at("/candidates/0/residencyChecks/0/reasonCode").asText());
        assertHistorySize(assessment, 1);
        boolean criticality = List.of("REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN").contains(scenario);
        var update = residencyRequest(criticality ? scenario : "REQUIRED");
        var details = (ObjectNode) update.at("/profile/security/dataResidencyDetails");
        details.putArray("allowedCountries").add("CA");
        details.putArray("dataCategories").add("USER_PROFILES");
        switch (scenario) {
            case "NO_SCOPE" -> details.putArray("dataCategories");
            case "NO_COUNTRIES" -> details.putArray("allowedCountries");
            case "NO_DETAILS" -> { details.putArray("allowedCountries"); details.putArray("dataCategories"); }
            case "ALL_CATEGORIES" -> {
                details.putArray("allowedCountries").add("DE").add("FR");
                details.putArray("dataCategories").add("USER_PROFILES").add("CREDENTIALS").add("BACKUPS").add("AUDIT_LOGS");
            }
        }
        var before = v2Response("residency-boundary-profile", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        var history = v2History("residency-boundary-history-before", path);
        var result = residencyPreflight("residency-" + scenario, path);
        String reason = switch (scenario) {
            case "REQUIRED" -> "STORAGE_OUTSIDE_ALLOWED_COUNTRIES";
            case "PREFERRED" -> "PREFERENCE_NOT_SCORED";
            case "NOT_REQUIRED" -> "NO_REQUIREMENT";
            case "FORBIDDEN" -> "RESIDENCY_INTENT_UNCLEAR";
            case "UNKNOWN" -> "REQUIREMENT_UNKNOWN";
            case "NO_SCOPE", "NO_DETAILS" -> "DATA_SCOPE_UNKNOWN";
            case "NO_COUNTRIES" -> "ALLOWED_COUNTRIES_UNKNOWN";
            case "ALL_CATEGORIES" -> "STORAGE_WITHIN_ALLOWED_COUNTRIES";
            default -> throw new AssertionError(scenario);
        };
        assertEquals(reason, result.at("/candidates/0/residencyChecks/0/reasonCode").asText());
        assertEquals(scenario.equals("ALL_CATEGORIES") ? 4 : 1, result.at("/candidates/0/residencyChecks").size());
        String expectedStatus = switch (scenario) {
            case "REQUIRED" -> "DOES_NOT_MATCH";
            case "PREFERRED", "NOT_REQUIRED", "ALL_CATEGORIES" -> "MATCHES_CHECKED_REQUIREMENTS";
            default -> "NEEDS_INFORMATION";
        };
        assertEquals(expectedStatus, result.at("/candidates/0/status").asText());
        if (scenario.equals("ALL_CATEGORIES")) {
            assertEquals("EVIDENCE_UNREVIEWED", result.at("/candidates/2/residencyChecks/0/reasonCode").asText());
            assertEquals("STORAGE_LOCATIONS_UNKNOWN", result.at("/candidates/2/residencyChecks/2/reasonCode").asText());
        }
        assertEquals(before, v2Response("residency-boundary-read-only", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v2History("residency-boundary-history-after", path));
    }

    @Test
    void v3ProfilesPreserveLegacyStorageAndProtectControlsFromOlderWriters() throws Exception {
        var assessment = create();
        String path = assessment.path().replace("/api/v1/", "/api/v3/");
        var projected = versionedSample("v3-projection", "assessment-response.v3", mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
        assertEquals(3, projected.get("profileSchemaVersion").asInt());
        assertEquals("UNKNOWN", projected.at("/profile/security/authenticationControls/phishingResistance").asText());
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", projected.get("profile").deepCopy());
        sample("v3-noop-request", "update-assessment-profile-request.v3", true, update.deepCopy());
        assertEquals(projected, versionedSample("v3-noop", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn()));
        assertEquals(assessment.created(), response("v1-after-v3-noop", mvc.perform(get(assessment.path())).andReturn()));
        var initialHistory = v3History("v3-original-history", path);
        assertEquals(1, initialHistory.at("/items/0/profileSchemaVersion").asInt());
        assertEquals(assessment.created().get("profile"), initialHistory.at("/items/0/profile"));

        // Create a v2 revision, then an explicit v3 revision. Reads never migrate either snapshot.
        var details = (ObjectNode) update.at("/profile/security/dataResidencyDetails");
        details.putArray("allowedCountries").add("DE");
        var v2Saved = versionedSample("v3-stores-v2-when-lossless", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        var v2Read = v2Response("v2-before-controls", mvc.perform(get(path.replace("/api/v3/", "/api/v2/"))).andExpect(status().isOk()).andReturn());
        update.put("expectedVersion", 1);
        ((ObjectNode) update.at("/profile/security/authenticationControls")).put("phishingResistance", "REQUIRED");
        sample("v3-required-request", "update-assessment-profile-request.v3", true, update.deepCopy());
        var saved = versionedSample("v3-controls-saved", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        assertEquals(2, saved.get("version").asInt());
        var history = v3History("v3-mixed-history", path);
        assertEquals(3, history.get("items").size());
        assertEquals(1, history.at("/items/0/profileSchemaVersion").asInt());
        assertEquals(2, history.at("/items/1/profileSchemaVersion").asInt());
        assertEquals(3, history.at("/items/2/profileSchemaVersion").asInt());
        assertEquals(v2Read.get("profile"), history.at("/items/1/profile"));
        assertEquals(saved.get("profile"), history.at("/items/2/profile"));
        var events = historyResponse("v3-security-event", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        assertEquals(3, events.get("items").size());
        assertEquals("security", events.at("/items/2/changedSections/0").asText());
        assertEquals(1, events.at("/items/2/changedSections").size());
        update.put("expectedVersion", 2);
        assertEquals(saved, versionedSample("v3-recorded-noop", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn()));
        for (int api : List.of(1, 2)) {
            String oldPath = path.replace("/api/v3/", "/api/v" + api + "/");
            response("older-read-blocked", mvc.perform(get(oldPath)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            response("older-history-blocked", mvc.perform(get(oldPath + "/revisions")).andExpect(status().isConflict()).andReturn());
            var oldRequest = mapper.createObjectNode().put("expectedVersion", 2);
            oldRequest.set("profile", api == 1 ? assessment.created().get("profile") : v2Read.get("profile"));
            response("older-write-blocked", mvc.perform(put(oldPath + "/profile").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(oldRequest))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            oldRequest.put("expectedVersion", 0);
            response("older-stale-version-first", mvc.perform(put(oldPath + "/profile").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(oldRequest))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("assessment-version-conflict")).andReturn());
            versionedSample("older-compatible-history-page", api == 1 ? "assessment-revision-page" : "assessment-revision-page.v2",
                    mvc.perform(get(oldPath + "/revisions?limit=1")).andExpect(status().isOk()).andReturn());
        }
        assertEquals(history, v3History("v3-blocked-writes-preserve-history", path));
        assertEquals(saved, versionedSample("v3-blocked-writes-preserve-state", "assessment-response.v3", mvc.perform(get(path)).andReturn()));
        // Explicitly clearing controls restores v2 readability without changing old snapshots.
        ((ObjectNode) update.at("/profile/security/authenticationControls")).put("phishingResistance", "UNKNOWN");
        var cleared = versionedSample("v3-clear-controls", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        assertEquals(3, cleared.get("version").asInt());
        v2Response("v2-readable-after-control-clear", mvc.perform(get(path.replace("/api/v3/", "/api/v2/"))).andExpect(status().isOk()).andReturn());
        update.put("expectedVersion", 3);
        details.putArray("allowedCountries");
        versionedSample("v3-clear-all-details", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        response("v1-readable-after-full-clear", mvc.perform(get(assessment.path())).andExpect(status().isOk()).andReturn());
        var after = v3History("v3-history-after-explicit-clears", path);
        for (int i = 0; i < 3; i++) assertEquals(history.get("items").get(i), after.get("items").get(i));
        assertEquals(2, after.at("/items/3/profileSchemaVersion").asInt());
        assertEquals(1, after.at("/items/4/profileSchemaVersion").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-controls", "null-controls", "missing-field", "null-field", "unknown-enum", "wrong-type",
            "extra-field", "missing-residency", "unsafe-version", "missing-security", "missing-version"})
    void v3RejectsMalformedControlsAtomically(String scenario) throws Exception {
        var assessment = create();
        String path = assessment.path().replace("/api/v1/", "/api/v3/");
        var before = versionedSample("v3-invalid-before", "assessment-response.v3", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", before.get("profile").deepCopy());
        var security = (ObjectNode) update.at("/profile/security");
        var controls = (ObjectNode) security.get("authenticationControls");
        switch (scenario) {
            case "missing-controls" -> security.remove("authenticationControls");
            case "null-controls" -> security.putNull("authenticationControls");
            case "missing-field" -> controls.remove("phishingResistance");
            case "null-field" -> controls.putNull("nonExportableKeys");
            case "unknown-enum" -> controls.put("stepUpAuthentication", "AAL3");
            case "wrong-type" -> controls.put("phishingResistance", true);
            case "extra-field" -> controls.put("certified", true);
            case "missing-residency" -> security.remove("dataResidencyDetails");
            case "unsafe-version" -> update.put("expectedVersion", 9007199254740992L);
            case "missing-security" -> ((ObjectNode) update.get("profile")).remove("security");
            case "missing-version" -> update.remove("expectedVersion");
            default -> throw new AssertionError(scenario);
        }
        sample("v3-invalid-" + scenario, "update-assessment-profile-request.v3", false, update);
        response("v3-invalid-" + scenario, mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update))).andExpect(status().isBadRequest()).andReturn());
        assertEquals(before, versionedSample("v3-invalid-unchanged", "assessment-response.v3", mvc.perform(get(path)).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN", "MACHINE", "NATIVE", "NO_POPULATION"})
    void v3EligibilityExplainsScopedControlsAndRemainsReadOnly(String scenario) throws Exception {
        var assessment = create();
        String path = assessment.path().replace("/api/v1/", "/api/v3/");
        var update = residencyRequest("NOT_REQUIRED");
        var controls = ((ObjectNode) update.at("/profile/security")).putObject("authenticationControls");
        boolean special = List.of("MACHINE", "NATIVE", "NO_POPULATION").contains(scenario);
        for (String field : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) controls.put(field, special ? "REQUIRED" : scenario);
        if (scenario.equals("MACHINE") || scenario.equals("NATIVE")) ((ObjectNode) update.at("/profile/application"))
                .putArray("clients").add(scenario.equals("MACHINE") ? "MACHINE_TO_MACHINE" : "NATIVE_MOBILE");
        if (scenario.equals("NO_POPULATION")) ((ObjectNode) update.at("/profile/audience")).putArray("populations");
        sample("v3-eligibility-request", "update-assessment-profile-request.v3", true, update);
        var before = versionedSample("v3-evaluation-profile", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        var history = v3History("v3-evaluation-history", path);
        var events = historyResponse("v3-evaluation-events", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var report = versionedSample("v3-eligibility-" + scenario, "eligibility-preflight.v3", mvc.perform(get(path + "/eligibility-preflight"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendationReady").value(false)).andReturn());
        assertEquals("eligibility-preflight-3", report.get("policyVersion").asText());
        assertEquals("authentication-controls-preflight-1", report.get("authenticationControlPolicyVersion").asText());
        assertEquals(before.at("/profile/security/assurance"), report.get("assuranceExpectation"));
        String reason = switch (scenario) {
            case "REQUIRED" -> "CONTROL_ENFORCEABLE";
            case "PREFERRED" -> "PREFERENCE_NOT_SCORED";
            case "NOT_REQUIRED" -> "NO_REQUIREMENT";
            case "FORBIDDEN" -> "CONTROL_INTENT_UNCLEAR";
            case "UNKNOWN" -> "REQUIREMENT_UNKNOWN";
            case "MACHINE" -> "HUMAN_AUTH_NOT_APPLICABLE";
            case "NATIVE" -> "EVIDENCE_MISSING";
            case "NO_POPULATION" -> "POPULATION_SCOPE_UNKNOWN";
            default -> throw new AssertionError(scenario);
        };
        assertEquals(reason, report.at("/candidates/0/authenticationControlChecks/0/reasonCode").asText());
        if (scenario.equals("REQUIRED")) {
            assertEquals("MATCHES_CHECKED_REQUIREMENTS", report.at("/candidates/0/status").asText());
            assertEquals("DOES_NOT_MATCH", report.at("/candidates/1/status").asText());
            assertEquals("ENFORCEMENT_UNSUPPORTED", report.at("/candidates/1/authenticationControlChecks/0/reasonCode").asText());
            assertEquals("EVIDENCE_UNREVIEWED", report.at("/candidates/2/authenticationControlChecks/0/reasonCode").asText());
        }
        for (int oldApi : List.of(1, 2)) {
            var old = versionedSample("v3-older-preflight", oldApi == 1 ? "eligibility-preflight" : "eligibility-preflight.v2",
                    mvc.perform(get(path.replace("/api/v3/", "/api/v" + oldApi + "/") + "/eligibility-preflight")).andExpect(status().isOk()).andReturn());
            assertFalse(old.at("/candidates/0").has("authenticationControlChecks"));
            for (String field : List.of("capabilityChecks", "contextChecks")) assertEquals(old.at("/candidates/0/" + field), report.at("/candidates/0/" + field));
        }
        assertEquals(report, versionedSample("v3-repeat", "eligibility-preflight.v3", mvc.perform(get(path + "/eligibility-preflight")).andReturn()));
        assertEquals(before, versionedSample("v3-read-only", "assessment-response.v3", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v3History("v3-history-read-only", path));
        assertEquals(events, historyResponse("v3-events-read-only", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
        var invalid = (ObjectNode) report.deepCopy();
        invalid.put("recommendationReady", true);
        sample("v3-no-certification-or-recommendation", "eligibility-preflight.v3", false, invalid);
    }

    @Test
    void v3CreationArchiveAndWorkspaceBoundariesArePreserved() throws Exception {
        var workspace = UUID.randomUUID();
        mvc.perform(put("/api/v1/workspaces/" + workspace)).andExpect(status().isCreated());
        var result = mvc.perform(post("/api/v3/workspaces/" + workspace + "/assessments")).andExpect(status().isCreated()).andReturn();
        var created = versionedSample("v3-create", "assessment-response.v3", result);
        String path = result.getResponse().getHeader("Location");
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", created.get("profile").deepCopy());
        ((ObjectNode) update.at("/profile/security/authenticationControls")).put("nonExportableKeys", "REQUIRED");
        versionedSample("v3-only-controls-no-residency", "assessment-response.v3", mvc.perform(put(path + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isOk()).andReturn());
        var persisted = repository.findById(new WorkspaceId(workspace), new AssessmentId(UUID.fromString(created.get("id").asText()))).orElseThrow();
        persisted.assessment().archive();
        repository.update(persisted.assessment(), persisted.version());
        var before = versionedSample("v3-archived", "assessment-response.v3", mvc.perform(get(path)).andReturn());
        assertEquals("ARCHIVED", before.get("status").asText());
        var history = v3History("v3-archived-history", path);
        versionedSample("v3-archived-preflight", "eligibility-preflight.v3", mvc.perform(get(path + "/eligibility-preflight")).andExpect(status().isOk()).andReturn());
        update.put("expectedVersion", 2);
        response("v3-archive-edit-blocked", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update))).andExpect(status().isConflict()).andReturn());
        assertEquals(before, versionedSample("v3-archived-unchanged", "assessment-response.v3", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v3History("v3-archived-history-unchanged", path));
        for (String suffix : List.of("", "/revisions", "/eligibility-preflight")) {
            response("v3-cross-workspace", mvc.perform(get(path.replace(workspace.toString(), UUID.randomUUID().toString()) + suffix))
                    .andExpect(status().isNotFound()).andReturn());
            response("v3-invalid-id", mvc.perform(get(path.replace(workspace.toString(), "invalid") + suffix))
                    .andExpect(status().isBadRequest()).andReturn());
        }
        response("v3-cross-workspace-write", mvc.perform(put(path.replace(workspace.toString(), UUID.randomUUID().toString()) + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isNotFound()).andReturn());
        response("v3-invalid-history-page", mvc.perform(get(path + "/revisions?limit=0")).andExpect(status().isBadRequest()).andReturn());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void v4ScopePreservesLegacyProfilesAndHistoryAndBlocksLossyOlderWrites(int originalFormat) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v4/");
        var projected = versionedSample("v4-initial-projection", "assessment-response.v4", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", projected.get("profile").deepCopy());
        var security = (ObjectNode) update.at("/profile/security");
        security.putArray("complianceTargets").add("SOC_2");
        if (originalFormat == 2) ((ObjectNode) security.get("dataResidencyDetails")).putArray("allowedCountries").add("DE");
        if (originalFormat == 3) ((ObjectNode) security.get("authenticationControls")).put("phishingResistance", "REQUIRED");
        var baseline = saveV4("v4-unrecorded-scope", path, update);
        var historyBefore = v4History("v4-history-before-recording", path);
        assertEquals(originalFormat, historyBefore.at("/items/1/profileSchemaVersion").asInt());
        assertFalse(historyBefore.at("/items/1/profile/security").has("complianceScopeStatus"));
        assertEquals("SOC_2", baseline.at("/profile/security/complianceTargets/0").asText());
        assertEquals("UNKNOWN", baseline.at("/profile/security/complianceScopeStatus").asText());
        assertEquals(4, baseline.get("profileSchemaVersion").asInt());
        update.put("expectedVersion", 1);
        assertEquals(baseline, saveV4("v4-legacy-noop", path, update));
        assertEquals(historyBefore, v4History("v4-noop-preserves-format", path));
        versionedSample("v4-old-api-still-readable", originalFormat == 1 ? "assessment-response" : "assessment-response.v" + originalFormat,
                mvc.perform(get(path.replace("/api/v4/", "/api/v" + originalFormat + "/"))).andExpect(status().isOk()).andReturn());
        security.put("complianceScopeStatus", "NONE_IDENTIFIED");
        security.putArray("complianceTargets");
        var saved = saveV4("v4-explicit-none", path, update);
        assertEquals(2, saved.get("version").asInt());
        var history = v4History("v4-recorded-history", path);
        assertEquals(3, history.get("items").size());
        assertEquals(4, history.at("/items/2/profileSchemaVersion").asInt());
        assertEquals(saved.get("profile"), history.at("/items/2/profile"));
        assertEquals(historyBefore.get("items").get(0), history.get("items").get(0));
        assertEquals(historyBefore.get("items").get(1), history.get("items").get(1));
        update.put("expectedVersion", 2);
        assertEquals(saved, saveV4("v4-recorded-noop", path, update));
        var events = historyResponse("v4-scope-events", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        assertEquals(3, events.get("items").size());
        assertEquals("security", events.at("/items/2/changedSections/0").asText());
        assertEquals(1, events.at("/items/2/changedSections").size());
        for (int api : List.of(1, 2, 3)) {
            String oldPath = path.replace("/api/v4/", "/api/v" + api + "/");
            response("v4-older-read-blocked", mvc.perform(get(oldPath)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            response("v4-older-history-blocked", mvc.perform(get(oldPath + "/revisions")).andExpect(status().isConflict()).andReturn());
            var oldRequest = update.deepCopy();
            var oldSecurity = (ObjectNode) oldRequest.at("/profile/security");
            oldSecurity.remove("complianceScopeStatus");
            if (api < 3) oldSecurity.remove("authenticationControls");
            if (api < 2) oldSecurity.remove("dataResidencyDetails");
            response("v4-older-write-blocked", mvc.perform(put(oldPath + "/profile").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(oldRequest))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            oldRequest.put("expectedVersion", 0);
            response("v4-older-stale-version-first", mvc.perform(put(oldPath + "/profile").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(oldRequest))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("assessment-version-conflict")).andReturn());
            versionedSample("v4-older-compatible-history-page", api == 1 ? "assessment-revision-page" : "assessment-revision-page.v" + api,
                    mvc.perform(get(oldPath + "/revisions?limit=1")).andExpect(status().isOk()).andReturn());
        }
        assertEquals(saved, versionedSample("v4-blocked-writes-preserve-state", "assessment-response.v4", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v4History("v4-blocked-writes-preserve-history", path));
        var page = versionedSample("v4-history-cursor", "assessment-revision-page.v4",
                mvc.perform(get(path + "/revisions?afterVersion=1&limit=1")).andExpect(status().isOk()).andReturn());
        assertEquals(history.at("/items/2"), page.at("/items/0"));
        security.put("complianceScopeStatus", "UNKNOWN");
        var cleared = saveV4("v4-explicit-scope-clear", path, update);
        assertEquals(3, cleared.get("version").asInt());
        var after = v4History("v4-history-after-clear", path);
        for (int i = 0; i < 3; i++) assertEquals(history.get("items").get(i), after.get("items").get(i));
        assertEquals(originalFormat, after.at("/items/3/profileSchemaVersion").asInt());
        versionedSample("v4-compatible-current-after-clear", originalFormat == 1 ? "assessment-response" : "assessment-response.v" + originalFormat,
                mvc.perform(get(path.replace("/api/v4/", "/api/v" + originalFormat + "/"))).andExpect(status().isOk()).andReturn());
        response("v4-history-not-erased-by-clear", mvc.perform(get(path.replace("/api/v4/", "/api/v3/") + "/revisions"))
                .andExpect(status().isConflict()).andReturn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-scope", "null-scope", "unknown-scope", "boolean-scope", "numeric-scope", "unknown-field",
            "missing-targets", "duplicate-targets", "null-target", "unknown-target", "none-with-targets", "identified-without-targets", "missing-controls"})
    void v4RejectsMalformedOrContradictoryScopeWithoutWriting(String scenario) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v4/");
        var before = versionedSample("v4-invalid-before", "assessment-response.v4", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", before.get("profile").deepCopy());
        var security = (ObjectNode) update.at("/profile/security");
        switch (scenario) {
            case "missing-scope" -> security.remove("complianceScopeStatus");
            case "null-scope" -> security.putNull("complianceScopeStatus");
            case "unknown-scope" -> security.put("complianceScopeStatus", "COMPLIANT");
            case "boolean-scope" -> security.put("complianceScopeStatus", true);
            case "numeric-scope" -> security.put("complianceScopeStatus", 1);
            case "unknown-field" -> security.put("complianceVerified", true);
            case "missing-targets" -> security.remove("complianceTargets");
            case "duplicate-targets" -> security.putArray("complianceTargets").add("SOC_2").add("SOC_2");
            case "null-target" -> security.putArray("complianceTargets").addNull();
            case "unknown-target" -> security.putArray("complianceTargets").add("EVERYTHING");
            case "none-with-targets" -> {
                security.put("complianceScopeStatus", "NONE_IDENTIFIED"); security.putArray("complianceTargets").add("GDPR");
            }
            case "identified-without-targets" -> security.put("complianceScopeStatus", "TARGETS_IDENTIFIED");
            case "missing-controls" -> security.remove("authenticationControls");
            default -> throw new AssertionError(scenario);
        }
        boolean contradiction = List.of("none-with-targets", "identified-without-targets").contains(scenario);
        sample("v4-invalid-" + scenario, "update-assessment-profile-request.v4", contradiction, update);
        var failure = mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().is(contradiction ? 422 : 400));
        if (contradiction) failure.andExpect(jsonPath("$.issues[0].code").value(scenario.equals("none-with-targets")
                ? "compliance_scope_none_has_targets" : "compliance_scope_targets_missing"));
        response("v4-invalid-" + scenario, failure.andReturn());
        assertEquals(before, versionedSample("v4-invalid-unchanged", "assessment-response.v4", mvc.perform(get(path)).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "UNKNOWN_WITH_TARGETS", "NONE_IDENTIFIED", "TARGETS_IDENTIFIED", "OTHER", "ALL_TARGETS", "MACHINE"})
    void v4SharedScopeCheckIsReadOnlyAndCannotClaimCompliance(String scenario) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v4/");
        var update = residencyRequest("NOT_REQUIRED");
        var security = (ObjectNode) update.at("/profile/security");
        var controls = security.putObject("authenticationControls");
        for (String field : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) controls.put(field, "NOT_REQUIRED");
        String scope = switch (scenario) {
            case "UNKNOWN_WITH_TARGETS", "MACHINE" -> "UNKNOWN";
            case "OTHER", "ALL_TARGETS" -> "TARGETS_IDENTIFIED";
            default -> scenario;
        };
        security.put("complianceScopeStatus", scope);
        var targets = security.putArray("complianceTargets");
        if (scenario.equals("TARGETS_IDENTIFIED") || scenario.equals("UNKNOWN_WITH_TARGETS")) targets.add("SOC_2");
        if (scenario.equals("OTHER")) targets.add("OTHER");
        if (scenario.equals("ALL_TARGETS")) for (String target : List.of("SOC_2", "ISO_27001", "HIPAA", "FEDRAMP", "GDPR", "OTHER")) targets.add(target);
        if (scenario.equals("MACHINE")) ((ObjectNode) update.at("/profile/application")).putArray("clients").add("MACHINE_TO_MACHINE");
        var before = saveV4("v4-scope-scenario", path, update);
        var history = v4History("v4-preflight-history-before", path);
        var events = historyResponse("v4-preflight-events-before", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var report = versionedSample("v4-eligibility-" + scenario, "eligibility-preflight.v4", mvc.perform(get(path + "/eligibility-preflight"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendationReady").value(false)).andReturn());
        assertEquals("eligibility-preflight-4", report.get("policyVersion").asText());
        assertEquals("compliance-scope-preflight-1", report.get("complianceScopePolicyVersion").asText());
        assertEquals("synthetic-2026-09-12.4", report.get("catalogVersion").asText());
        assertEquals(before.get("version"), report.get("assessmentVersion"));
        assertEquals(scope, report.at("/complianceScopeCheck/scopeStatus").asText());
        assertFalse(report.at("/complianceScopeCheck/verificationPerformed").asBoolean());
        assertEquals(targets.size(), report.at("/complianceScopeCheck/recordedTargets").size());
        assertEquals(scope.equals("NONE_IDENTIFIED") ? "NOT_APPLIED" : "UNKNOWN", report.at("/complianceScopeCheck/outcome").asText());
        assertEquals(scope.equals("NONE_IDENTIFIED") ? "MATCHES_CHECKED_REQUIREMENTS" : "NEEDS_INFORMATION", report.at("/candidates/0/status").asText());
        assertEquals("DOES_NOT_MATCH", report.at("/candidates/1/status").asText());
        assertEquals("NEEDS_INFORMATION", report.at("/candidates/2/status").asText());
        for (int api : List.of(1, 2, 3)) {
            var older = versionedSample("v4-older-preflight", api == 1 ? "eligibility-preflight" : "eligibility-preflight.v" + api,
                    mvc.perform(get(path.replace("/api/v4/", "/api/v" + api + "/") + "/eligibility-preflight")).andExpect(status().isOk()).andReturn());
            assertFalse(older.has("complianceScopeCheck"));
            for (String group : List.of("capabilityChecks", "contextChecks")) assertEquals(older.at("/candidates/0/" + group), report.at("/candidates/0/" + group));
            if (api == 3) {
                assertEquals("MATCHES_CHECKED_REQUIREMENTS", older.at("/candidates/0/status").asText());
                for (String group : List.of("residencyChecks", "authenticationControlChecks")) assertEquals(older.at("/candidates/0/" + group), report.at("/candidates/0/" + group));
            }
        }
        assertEquals(report, versionedSample("v4-repeat", "eligibility-preflight.v4", mvc.perform(get(path + "/eligibility-preflight")).andReturn()));
        assertEquals(before, versionedSample("v4-preflight-state-after", "assessment-response.v4", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v4History("v4-preflight-history-after", path));
        assertEquals(events, historyResponse("v4-preflight-events-after", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
        var invalid = (ObjectNode) report.deepCopy();
        ((ObjectNode) invalid.get("complianceScopeCheck")).put("verificationPerformed", true);
        sample("v4-cannot-claim-verification", "eligibility-preflight.v4", false, invalid);
        invalid = (ObjectNode) report.deepCopy();
        ((ObjectNode) invalid.get("complianceScopeCheck")).put("outcome", "PASS");
        sample("v4-no-compliance-pass", "eligibility-preflight.v4", false, invalid);
    }

    @Test
    void v4CreateArchiveAndWorkspaceBoundariesArePreserved() throws Exception {
        var workspace = UUID.randomUUID();
        mvc.perform(put("/api/v1/workspaces/" + workspace)).andExpect(status().isCreated());
        var result = mvc.perform(post("/api/v4/workspaces/" + workspace + "/assessments")).andExpect(status().isCreated()).andReturn();
        var created = versionedSample("v4-create", "assessment-response.v4", result);
        var path = result.getResponse().getHeader("Location");
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", created.get("profile").deepCopy());
        ((ObjectNode) update.at("/profile/security")).put("complianceScopeStatus", "NONE_IDENTIFIED");
        saveV4("v4-scope-only", path, update);
        var persisted = repository.findById(new WorkspaceId(workspace), new AssessmentId(UUID.fromString(created.get("id").asText()))).orElseThrow();
        persisted.assessment().archive(); repository.update(persisted.assessment(), persisted.version());
        var before = versionedSample("v4-archived-read", "assessment-response.v4", mvc.perform(get(path)).andReturn());
        assertEquals("ARCHIVED", before.get("status").asText());
        var history = v4History("v4-archived-history", path);
        versionedSample("v4-archived-preflight", "eligibility-preflight.v4", mvc.perform(get(path + "/eligibility-preflight")).andExpect(status().isOk()).andReturn());
        update.put("expectedVersion", 2);
        response("v4-archived-write-blocked", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update))).andExpect(status().isConflict()).andReturn());
        assertEquals(before, versionedSample("v4-archive-state-unchanged", "assessment-response.v4", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v4History("v4-archive-history-unchanged", path));
        for (String suffix : List.of("", "/revisions", "/eligibility-preflight")) {
            response("v4-cross-workspace", mvc.perform(get(path.replace(workspace.toString(), UUID.randomUUID().toString()) + suffix))
                    .andExpect(status().isNotFound()).andReturn());
            response("v4-invalid-id", mvc.perform(get(path.replace(workspace.toString(), "invalid") + suffix))
                    .andExpect(status().isBadRequest()).andReturn());
        }
        response("v4-cross-workspace-write", mvc.perform(put(path.replace(workspace.toString(), UUID.randomUUID().toString()) + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isNotFound()).andReturn());
        response("v4-invalid-history-page", mvc.perform(get(path + "/revisions?limit=0")).andExpect(status().isBadRequest()).andReturn());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void v5PreservesOriginalSnapshotsAndGuardsOlderClients(int originalFormat) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v5/");
        var projected = versionedSample("v5-legacy-projection", "assessment-response.v5", mvc.perform(get(path)).andReturn());
        assertEquals(5, projected.get("profileSchemaVersion").asInt());
        assertEquals(0, projected.at("/profile/operations/usagePlanning/volumes").size());
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", projected.get("profile").deepCopy());
        var security = (ObjectNode) update.at("/profile/security");
        ((ObjectNode) update.at("/profile/application")).put("type", "B2B_SAAS");
        if (originalFormat == 2) ((ObjectNode) security.get("dataResidencyDetails")).putArray("allowedCountries").add("DE");
        if (originalFormat == 3) ((ObjectNode) security.get("authenticationControls")).put("phishingResistance", "REQUIRED");
        if (originalFormat == 4) security.put("complianceScopeStatus", "NONE_IDENTIFIED");
        var baseline = saveV5("v5-unrecorded-usage", path, update);
        var historyBefore = v5History("v5-history-before-usage", path);
        assertEquals(originalFormat, historyBefore.at("/items/1/profileSchemaVersion").asInt());
        assertFalse(historyBefore.at("/items/1/profile/operations").has("usagePlanning"));
        update.put("expectedVersion", 1);
        assertEquals(baseline, saveV5("v5-legacy-noop", path, update));
        assertEquals(historyBefore, v5History("v5-noop-preserves-format", path));
        var usage = (ObjectNode) update.at("/profile/operations/usagePlanning");
        usage.put("scopeDescription", "Synthetic pilot, one production environment");
        usage.putArray("assumptions").add("No machine clients in this scenario");
        ((ObjectNode) usage.get("volumes")).putObject("MONTHLY_M2M_TOKEN_ISSUANCES").put("basis", "ASSUMED").put("value", 0);
        var saved = saveV5("v5-recorded-usage", path, update);
        assertEquals(2, saved.get("version").asInt());
        var history = v5History("v5-recorded-history", path);
        assertEquals(5, history.at("/items/2/profileSchemaVersion").asInt());
        assertEquals(saved.get("profile"), history.at("/items/2/profile"));
        assertEquals(historyBefore.at("/items/0"), history.at("/items/0"));
        assertEquals(historyBefore.at("/items/1"), history.at("/items/1"));
        update.put("expectedVersion", 2);
        assertEquals(saved, saveV5("v5-recorded-noop", path, update));
        var events = historyResponse("v5-usage-events", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        assertEquals(3, events.get("items").size());
        assertEquals("operations", events.at("/items/2/changedSections/0").asText());
        assertEquals(1, events.at("/items/2/changedSections").size());
        for (int api : List.of(1, 2, 3, 4)) {
            String oldPath = path.replace("/api/v5/", "/api/v" + api + "/");
            response("v5-older-read-blocked", mvc.perform(get(oldPath)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            response("v5-older-history-blocked", mvc.perform(get(oldPath + "/revisions")).andExpect(status().isConflict()).andReturn());
            var oldRequest = update.deepCopy();
            ((ObjectNode) oldRequest.at("/profile/operations")).remove("usagePlanning");
            var oldSecurity = (ObjectNode) oldRequest.at("/profile/security");
            if (api < 4) oldSecurity.remove("complianceScopeStatus");
            if (api < 3) oldSecurity.remove("authenticationControls");
            if (api < 2) oldSecurity.remove("dataResidencyDetails");
            response("v5-older-write-blocked", mvc.perform(put(oldPath + "/profile").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(oldRequest))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            oldRequest.put("expectedVersion", 0);
            response("v5-older-stale-version-first", mvc.perform(put(oldPath + "/profile").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(oldRequest))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("assessment-version-conflict")).andReturn());
            versionedSample("v5-compatible-history-page", api == 1 ? "assessment-revision-page" : "assessment-revision-page.v" + api,
                    mvc.perform(get(oldPath + "/revisions?limit=1")).andExpect(status().isOk()).andReturn());
        }
        assertEquals(saved, versionedSample("v5-guards-preserve-state", "assessment-response.v5", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v5History("v5-guards-preserve-history", path));
        var page = versionedSample("v5-history-cursor", "assessment-revision-page.v5",
                mvc.perform(get(path + "/revisions?afterVersion=1&limit=1")).andExpect(status().isOk()).andReturn());
        assertEquals(history.at("/items/2"), page.at("/items/0"));
        usage.put("scopeDescription", ""); usage.putArray("assumptions"); usage.putObject("volumes");
        saveV5("v5-explicit-clear", path, update);
        var after = v5History("v5-history-after-clear", path);
        for (int i = 0; i < 3; i++) assertEquals(history.get("items").get(i), after.get("items").get(i));
        assertEquals(originalFormat, after.at("/items/3/profileSchemaVersion").asInt());
        versionedSample("v5-compatible-current-after-clear", originalFormat == 1 ? "assessment-response" : "assessment-response.v" + originalFormat,
                mvc.perform(get(path.replace("/api/v5/", "/api/v" + originalFormat + "/"))).andExpect(status().isOk()).andReturn());
        response("v5-clear-does-not-erase-history", mvc.perform(get(path.replace("/api/v5/", "/api/v4/") + "/revisions"))
                .andExpect(status().isConflict()).andReturn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-usage", "null-usage", "unknown-field", "missing-context", "null-context", "long-context", "numeric-context", "float-context", "boolean-context",
            "missing-assumptions", "null-assumptions", "blank-assumption", "duplicate-assumptions", "long-assumption", "too-many-assumptions",
            "null-assumption", "numeric-assumption", "boolean-assumption", "missing-volumes", "null-volumes", "unknown-metric", "padded-metric", "null-quantity", "unknown-quantity-field",
            "missing-basis", "null-basis", "unknown-basis", "numeric-basis", "missing-value", "null-value", "negative-value", "fractional-value",
            "string-value", "boolean-value", "unsafe-value", "overflow-value"})
    void v5RejectsMalformedUsageWithoutWriting(String scenario) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v5/");
        var before = versionedSample("v5-invalid-before", "assessment-response.v5", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", before.get("profile").deepCopy());
        var operations = (ObjectNode) update.at("/profile/operations");
        var usage = (ObjectNode) operations.get("usagePlanning");
        var volumes = (ObjectNode) usage.get("volumes");
        var quantity = volumes.putObject("MONTHLY_ACTIVE_USERS").put("basis", "ASSUMED").put("value", 100);
        switch (scenario) {
            case "missing-usage" -> operations.remove("usagePlanning");
            case "null-usage" -> operations.putNull("usagePlanning");
            case "unknown-field" -> usage.put("monthlyPrice", 0);
            case "missing-context" -> usage.remove("scopeDescription");
            case "null-context" -> usage.putNull("scopeDescription");
            case "long-context" -> usage.put("scopeDescription", "x".repeat(501));
            case "numeric-context" -> usage.put("scopeDescription", 1);
            case "float-context" -> usage.put("scopeDescription", 1.5);
            case "boolean-context" -> usage.put("scopeDescription", true);
            case "missing-assumptions" -> usage.remove("assumptions");
            case "null-assumptions" -> usage.putNull("assumptions");
            case "blank-assumption" -> usage.putArray("assumptions").add(" \t ");
            case "duplicate-assumptions" -> usage.putArray("assumptions").add("same").add("same");
            case "long-assumption" -> usage.putArray("assumptions").add("x".repeat(501));
            case "too-many-assumptions" -> { var notes = usage.putArray("assumptions"); for (int i = 0; i < 11; i++) notes.add("Note " + i); }
            case "null-assumption" -> usage.putArray("assumptions").addNull();
            case "numeric-assumption" -> usage.putArray("assumptions").add(10);
            case "boolean-assumption" -> usage.putArray("assumptions").add(true);
            case "missing-volumes" -> usage.remove("volumes");
            case "null-volumes" -> usage.putNull("volumes");
            case "unknown-metric" -> volumes.set("REGISTERED_USERS", quantity.deepCopy());
            case "padded-metric" -> volumes.set(" MONTHLY_ACTIVE_USERS ", quantity.deepCopy());
            case "null-quantity" -> volumes.putNull("MONTHLY_ACTIVE_USERS");
            case "unknown-quantity-field" -> quantity.put("price", 0);
            case "missing-basis" -> quantity.remove("basis");
            case "null-basis" -> quantity.putNull("basis");
            case "unknown-basis" -> quantity.put("basis", "UNKNOWN");
            case "numeric-basis" -> quantity.put("basis", 0);
            case "missing-value" -> quantity.remove("value");
            case "null-value" -> quantity.putNull("value");
            case "negative-value" -> quantity.put("value", -1);
            case "fractional-value" -> quantity.put("value", 0.5);
            case "string-value" -> quantity.put("value", "100");
            case "boolean-value" -> quantity.put("value", false);
            case "unsafe-value" -> quantity.put("value", 9007199254740992L);
            case "overflow-value" -> quantity.put("value", new java.math.BigInteger("100000000000000000000"));
            default -> throw new AssertionError(scenario);
        }
        sample("v5-invalid-" + scenario, "update-assessment-profile-request.v5", false, update);
        response("v5-invalid-" + scenario, mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update))).andExpect(status().isBadRequest()).andReturn());
        assertEquals(before, versionedSample("v5-invalid-unchanged", "assessment-response.v5", mvc.perform(get(path)).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "PARTIAL", "ASSUMED", "ASSUMED_WITHOUT_NOTES", "OBSERVED", "MIXED", "MAXIMUM", "WHITESPACE_CONTEXT"})
    void v5PlanningPreflightKeepsUnknownSeparateFromZeroAndDoesNotEvaluatePrices(String scenario) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v5/");
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", versionedSample("v5-scenario-before", "assessment-response.v5", mvc.perform(get(path)).andReturn()).get("profile"));
        var usage = (ObjectNode) update.at("/profile/operations/usagePlanning");
        if (!scenario.equals("UNKNOWN")) {
            usage.put("scopeDescription", scenario.equals("WHITESPACE_CONTEXT") ? "  " : "x".repeat(500));
            var volumes = (ObjectNode) usage.get("volumes");
            int index = 0;
            for (var metric : io.authweave.core.assessment.domain.profile.UsagePlanning.Metric.values()) {
                if (scenario.equals("PARTIAL") && index > 0) break;
                String basis = scenario.equals("OBSERVED") || (scenario.equals("MIXED") && index == 0) ? "OBSERVED" : "ASSUMED";
                volumes.putObject(metric.name()).put("basis", basis).put("value", scenario.equals("MAXIMUM") ? 9007199254740991L : 0);
                index++;
            }
            if (!List.of("OBSERVED", "ASSUMED_WITHOUT_NOTES").contains(scenario)) {
                var notes = usage.putArray("assumptions");
                for (int i = 0; i < 10; i++) notes.add("x".repeat(499) + i);
            }
        }
        var before = saveV5("v5-usage-" + scenario, path, update);
        var history = v5History("v5-preflight-history-before", path);
        var events = historyResponse("v5-preflight-events-before", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var report = usagePreflight("usage-" + scenario, path);
        assertEquals(before.get("version"), report.get("assessmentVersion"));
        assertEquals("usage-planning-preflight-1", report.get("policyVersion").asText());
        assertEquals(usage.get("scopeDescription"), report.get("scopeDescription"));
        assertEquals(usage.get("assumptions"), report.get("assumptions"));
        boolean complete = List.of("ASSUMED", "OBSERVED", "MIXED", "MAXIMUM").contains(scenario);
        assertEquals(complete ? "INPUTS_RECORDED" : "NEEDS_INFORMATION", report.get("status").asText());
        assertEquals(scenario.equals("UNKNOWN") ? 5 : scenario.equals("PARTIAL") ? 3 : complete ? 0 : 1, report.get("missingPaths").size());
        int index = 0;
        for (var metric : io.authweave.core.assessment.domain.profile.UsagePlanning.Metric.values()) {
            var check = report.get("quantityChecks").get(index++);
            assertEquals(metric.name(), check.get("metric").asText());
            assertEquals(metric.unit().name(), check.get("unit").asText());
            assertEquals(metric.definition(), check.get("definition").asText());
            var input = usage.at("/volumes/" + metric.name());
            assertEquals(input.isMissingNode() ? "UNKNOWN" : input.get("basis").asText(), check.get("status").asText());
            if (input.isMissingNode()) assertEquals(mapper.nullNode(), check.get("input"));
            else {
                assertEquals(input.get("basis"), check.at("/input/basis"));
                assertEquals(input.get("value").asLong(), check.at("/input/value").asLong());
            }
        }
        assertFalse(report.has("candidates")); assertFalse(report.has("catalogVersion"));
        assertEquals(report, usagePreflight("usage-repeat", path));
        assertEquals(before, versionedSample("v5-preflight-state-after", "assessment-response.v5", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v5History("v5-preflight-history-after", path));
        assertEquals(events, historyResponse("v5-preflight-events-after", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
        for (String field : List.of("pricingEvaluated", "recommendationReady", "monthlyCost", "winner")) {
            var invalid = (ObjectNode) report.deepCopy(); invalid.put(field, true);
            sample("usage-no-claim-" + field, "usage-planning-preflight", false, invalid);
        }
    }

    @Test
    void v5UsageAndBudgetSensitivityDoNotChangeEarlierEligibilityChecks() throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v5/");
        var before = new java.util.HashMap<Integer, JsonNode>();
        for (int api : List.of(1, 2, 3, 4)) before.put(api, versionedSample("usage-eligibility-before",
                api == 1 ? "eligibility-preflight" : "eligibility-preflight.v" + api,
                mvc.perform(get(path.replace("/api/v5/", "/api/v" + api + "/") + "/eligibility-preflight")).andReturn()));
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", versionedSample("v5-eligibility-profile", "assessment-response.v5", mvc.perform(get(path)).andReturn()).get("profile"));
        var operations = (ObjectNode) update.at("/profile/operations");
        ((ObjectNode) operations.at("/usagePlanning/volumes")).putObject("MONTHLY_ACTIVE_USERS").put("basis", "ASSUMED").put("value", 9007199254740991L);
        for (String sensitivity : List.of("HIGH", "LOW")) {
            operations.put("budgetSensitivity", sensitivity);
            var saved = saveV5("v5-budget-is-not-a-limit", path, update);
            update.set("expectedVersion", saved.get("version"));
            for (int api : List.of(1, 2, 3, 4)) {
                var report = versionedSample("usage-eligibility-after", api == 1 ? "eligibility-preflight" : "eligibility-preflight.v" + api,
                        mvc.perform(get(path.replace("/api/v5/", "/api/v" + api + "/") + "/eligibility-preflight")).andReturn());
                var expected = (ObjectNode) before.get(api).deepCopy(); expected.set("assessmentVersion", saved.get("version"));
                assertEquals(expected, report);
            }
        }
    }

    @Test
    void v5CreateArchiveAndWorkspaceBoundariesArePreserved() throws Exception {
        var workspace = UUID.randomUUID();
        mvc.perform(put("/api/v1/workspaces/" + workspace)).andExpect(status().isCreated());
        var result = mvc.perform(post("/api/v5/workspaces/" + workspace + "/assessments")).andExpect(status().isCreated()).andReturn();
        var created = versionedSample("v5-create", "assessment-response.v5", result);
        var path = result.getResponse().getHeader("Location");
        var update = mapper.createObjectNode().put("expectedVersion", 0);
        update.set("profile", created.get("profile").deepCopy());
        ((ObjectNode) update.at("/profile/operations/usagePlanning")).put("scopeDescription", "Synthetic pilot");
        saveV5("v5-scope-only", path, update);
        var persisted = repository.findById(new WorkspaceId(workspace), new AssessmentId(UUID.fromString(created.get("id").asText()))).orElseThrow();
        persisted.assessment().archive(); repository.update(persisted.assessment(), persisted.version());
        var before = versionedSample("v5-archived-read", "assessment-response.v5", mvc.perform(get(path)).andReturn());
        assertEquals("ARCHIVED", before.get("status").asText());
        var history = v5History("v5-archived-history", path);
        usagePreflight("usage-archived", path);
        update.put("expectedVersion", 2);
        response("v5-archived-write-blocked", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(update))).andExpect(status().isConflict()).andReturn());
        assertEquals(before, versionedSample("v5-archive-state-unchanged", "assessment-response.v5", mvc.perform(get(path)).andReturn()));
        assertEquals(history, v5History("v5-archive-history-unchanged", path));
        for (String suffix : List.of("", "/revisions", "/usage-planning-preflight")) {
            response("v5-cross-workspace", mvc.perform(get(path.replace(workspace.toString(), UUID.randomUUID().toString()) + suffix))
                    .andExpect(status().isNotFound()).andReturn());
            response("v5-invalid-id", mvc.perform(get(path.replace(workspace.toString(), "invalid") + suffix))
                    .andExpect(status().isBadRequest()).andReturn());
        }
        response("v5-cross-workspace-write", mvc.perform(put(path.replace(workspace.toString(), UUID.randomUUID().toString()) + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update))).andExpect(status().isNotFound()).andReturn());
        response("v5-invalid-history-page", mvc.perform(get(path + "/revisions?limit=0")).andExpect(status().isBadRequest()).andReturn());
    }

    @Test
    void v5AssessmentListUsesBoundedSummaryPages() throws Exception {
        var workspace = UUID.randomUUID();
        String path = "/api/v5/workspaces/" + workspace + "/assessments";
        mvc.perform(put("/api/v1/workspaces/" + workspace)).andExpect(status().isCreated());
        for (int index = 0; index < 3; index++) {
            mvc.perform(post(path)).andExpect(status().isCreated());
        }
        JsonNode first = mapper.readTree(mvc.perform(get(path).param("limit", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        sample("v5-list-first", "assessment-list-page", true, first);
        assertEquals(2, first.get("items").size());
        String beforeId = first.get("nextBeforeId").asText();
        JsonNode second = mapper.readTree(mvc.perform(get(path).param("limit", "2")
                        .param("beforeId", beforeId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        sample("v5-list-second", "assessment-list-page", true, second);
        assertEquals(1, second.get("items").size());
        assertEquals(mapper.nullNode(), second.get("nextBeforeId"));
        response("v5-list-invalid-limit", mvc.perform(get(path).param("limit", "0"))
                .andExpect(status().isBadRequest()).andReturn());
        response("v5-list-foreign-cursor", mvc.perform(get(path).param("beforeId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andReturn());
    }

    @Test
    void v5HardConstraintSummaryPreservesEveryFailureAndUnknownWithoutWriting() throws Exception {
        var assessment = create();
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var before = response("hard-constraint-profile", mvc.perform(put(assessment.path() + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var path = assessment.path().replace("/api/v1/", "/api/v5/") + "/hard-constraint-preflight";
        var result = versionedSample("hard-constraint-summary", "hard-constraint-preflight",
                mvc.perform(get(path)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.recommendationReady").value(false))
                        .andExpect(jsonPath("$.candidates[1].verdict").value("EXCLUDED"))
                        .andExpect(jsonPath("$.candidates[1].exclusionReasons[0].reasonCode").isNotEmpty())
                        .andExpect(jsonPath("$.candidates[1].informationGaps").isArray()).andReturn());
        assertEquals(result, mapper.readTree(mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()));
        assertEquals(before, response("hard-constraint-state-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);
        response("hard-constraint-foreign-workspace", mvc.perform(get(path.replace(assessment.workspaceId().value().toString(),
                UUID.randomUUID().toString()))).andExpect(status().isNotFound()).andReturn());
    }

    @Test
    void v5SyntheticComparisonKeepsPreferencesSeparateFromHardExclusionsAndState() throws Exception {
        var assessment = create();
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var before = response("comparison-profile", mvc.perform(put(assessment.path() + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var root = assessment.path().replace("/api/v1/", "/api/v5/");
        var hard = versionedSample("comparison-hard-baseline", "hard-constraint-preflight",
                mvc.perform(get(root + "/hard-constraint-preflight")).andExpect(status().isOk()).andReturn());
        var comparison = versionedSample("synthetic-comparison", "synthetic-comparison",
                mvc.perform(get(root + "/comparison-preflight")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.recommendationReady").value(false))
                        .andExpect(jsonPath("$.rankingPerformed").value(false))
                        .andExpect(jsonPath("$.candidates[1].hardVerdict").value("EXCLUDED"))
                        .andExpect(jsonPath("$.candidates[1].capabilityPreferences[0].outcome").value("AVAILABLE"))
                        .andExpect(jsonPath("$.candidates[0].capabilityPreferences.length()").value(2))
                        .andReturn());
        for (int index = 0; index < hard.get("candidates").size(); index++) {
            var expected = hard.get("candidates").get(index);
            var actual = comparison.get("candidates").get(index);
            assertEquals(expected.get("optionId"), actual.get("optionId"));
            assertEquals(expected.get("verdict"), actual.get("hardVerdict"));
            assertEquals(expected.get("exclusionReasons"), actual.get("exclusionReasons"));
            assertEquals(expected.get("informationGaps"), actual.get("informationGaps"));
            assertFalse(actual.has("score"));
        }
        assertEquals(before, response("comparison-state-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);
        response("comparison-foreign-workspace", mvc.perform(get(root.replace(assessment.workspaceId().value().toString(),
                UUID.randomUUID().toString()) + "/comparison-preflight")).andExpect(status().isNotFound()).andReturn());
    }

    @Test
    void v5ExplicitWeightedPreviewWithholdsUnresolvedScoresAndDoesNotMutateAssessment() throws Exception {
        var assessment = create();
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var before = response("weighted-comparison-profile", mvc.perform(put(assessment.path() + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var path = assessment.path().replace("/api/v1/", "/api/v5/") + "/weighted-comparison-preview";
        var body = "{\"weights\":{\"SOCIAL_LOGIN\":60,\"JIT\":40}}";
        sample("weighted-request", "weighted-comparison-request", true, mapper.readTree(body));
        var preview = versionedSample("weighted-preview", "weighted-comparison-preview",
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.recommendationReady").value(false))
                        .andExpect(jsonPath("$.rankingPerformed").value(false))
                        .andExpect(jsonPath("$.scores[1].status").value("EXCLUDED"))
                        .andExpect(jsonPath("$.scores[1].score").value(org.hamcrest.Matchers.nullValue()))
                        .andReturn());
        assertEquals(3, preview.get("scores").size());
        assertEquals(before, response("weighted-comparison-state-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);
        response("weighted-foreign-workspace", mvc.perform(post(path.replace(assessment.workspaceId().value().toString(),
                UUID.randomUUID().toString())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andReturn());
        response("weighted-incomplete", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"weights\":{\"SOCIAL_LOGIN\":100}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].path").value("weights")).andReturn());
        response("weighted-wrong-total", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"weights\":{\"SOCIAL_LOGIN\":30,\"JIT\":30}}"))
                .andExpect(status().isBadRequest()).andReturn());
        response("weighted-unknown-capability", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"weights\":{\"SOCIAL_LOGIN\":50,\"INVENTED\":50}}"))
                .andExpect(status().isBadRequest()).andReturn());
        assertEquals(before, response("weighted-invalid-state-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);
    }

    @Test
    void v5WeightSensitivityComparesOneSnapshotWithoutRankingOrWrites() throws Exception {
        var assessment = create();
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var before = response("sensitivity-profile", mvc.perform(put(assessment.path() + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn());
        var path = assessment.path().replace("/api/v1/", "/api/v5/") + "/weight-sensitivity-preview";
        var body = "{\"baselineWeights\":{\"SOCIAL_LOGIN\":60,\"JIT\":40},"
                + "\"alternativeWeights\":{\"SOCIAL_LOGIN\":20,\"JIT\":80}}";
        sample("sensitivity-request", "weight-sensitivity-request", true, mapper.readTree(body));
        var preview = versionedSample("sensitivity-preview", "weight-sensitivity-preview",
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.recommendationReady").value(false))
                        .andExpect(jsonPath("$.rankingPerformed").value(false))
                        .andExpect(jsonPath("$.deltas[1].status").value("EXCLUDED"))
                        .andExpect(jsonPath("$.deltas[1].scoreDelta").value(org.hamcrest.Matchers.nullValue()))
                        .andReturn());
        assertEquals(preview.get("comparison").get("candidates").size(), preview.get("deltas").size());
        assertEquals(preview.get("comparison").get("candidates").size(), preview.get("baseline").get("scores").size());
        assertEquals(preview.get("comparison").get("candidates").size(), preview.get("alternative").get("scores").size());
        assertEquals(preview, mapper.readTree(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        assertEquals(before, response("sensitivity-state-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);
        response("sensitivity-foreign-workspace", mvc.perform(post(path.replace(assessment.workspaceId().value().toString(),
                UUID.randomUUID().toString())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andReturn());
        response("sensitivity-incomplete-alternative", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"baselineWeights\":{\"SOCIAL_LOGIN\":60,\"JIT\":40},"
                        + "\"alternativeWeights\":{\"SOCIAL_LOGIN\":100}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].path").value("alternativeWeights")).andReturn());
        response("sensitivity-missing-baseline", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alternativeWeights\":{\"SOCIAL_LOGIN\":20,\"JIT\":80}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].path").value("baselineWeights")).andReturn());
        assertEquals(before, response("sensitivity-invalid-state-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 2);
    }

    @Test
    void v5WeightSensitivityShowsComparableScoresWhenCheckedEvidenceIsComplete() throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v4/");
        var update = residencyRequest("NOT_REQUIRED");
        var security = (ObjectNode) update.at("/profile/security");
        var controls = security.putObject("authenticationControls");
        for (String field : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) {
            controls.put(field, "NOT_REQUIRED");
        }
        security.put("complianceScopeStatus", "NONE_IDENTIFIED");
        var before = saveV4("sensitivity-comparable-profile", path, update);
        var history = v4History("sensitivity-comparable-history-before", path);
        var endpoint = path.replace("/api/v4/", "/api/v5/") + "/weight-sensitivity-preview";
        var body = "{\"baselineWeights\":{\"SOCIAL_LOGIN\":60,\"JIT\":40},"
                + "\"alternativeWeights\":{\"SOCIAL_LOGIN\":20,\"JIT\":80}}";
        var preview = versionedSample("sensitivity-comparable-preview", "weight-sensitivity-preview",
                mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.deltas[0].status").value("SCORED"))
                        .andExpect(jsonPath("$.deltas[0].scoreDelta").value(0))
                        .andExpect(jsonPath("$.deltas[1].scoreDelta").value(org.hamcrest.Matchers.nullValue()))
                        .andExpect(jsonPath("$.rankingPerformed").value(false)).andReturn());
        assertEquals(100, preview.at("/baseline/scores/0/score").asInt());
        assertEquals(100, preview.at("/alternative/scores/0/score").asInt());
        assertEquals(2, preview.at("/deltas/0/capabilityDeltas").size());
        assertEquals(before, versionedSample("sensitivity-comparable-state-unchanged", "assessment-response.v4",
                mvc.perform(get(path)).andReturn()));
        assertEquals(2, history.get("items").size());
        assertEquals(history, v4History("sensitivity-comparable-history-after", path));
    }

    @Test
    void auditabilityDraftValidationIsProtectedNoStoreReadOnlyAndDoesNotChangeOldDraftContracts() throws Exception {
        String path = "/internal/v1/catalog-auditability/drafts/validate";
        String token = "Bearer synthetic-internal-token-000000000000000000000";
        var input = auditabilityDraftRequest(); String body = mapper.writeValueAsString(input);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "wrong-token").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", token, token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", token).queryParam("approve", "true").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", token)).andExpect(status().isMethodNotAllowed());
        var tables = List.of("core.assessments", "core.catalog_proposals", "core.catalog_impact_reports", "core.catalog_fact_path_reports", "core.catalog_bootstrap_impact_reports",
                "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events");
        var before = tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList();
        sample("auditability-draft", "catalog-auditability-draft", true, input.get("auditabilityDraft"));
        sample("auditability-draft-request", "catalog-auditability-draft-validation-request", true, input.deepCopy());
        var result = mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("VALID_DRAFT")).andExpect(jsonPath("$.targetCount").value(2)).andReturn();
        var report = versionedSample("auditability-draft-report", "catalog-auditability-draft-validation", result);
        assertEquals("93b8c1474feacf128a92f0da0d79f71277a7f400f136b43bf18e52922c31d4f9", report.get("contentSha256").asText());
        assertEquals(input.get("auditabilityDraft").get("baseContentSha256"), report.at("/baseValidation/contentSha256"));
        assertEquals(report, mapper.readTree(mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        for (String flag : List.of("sourceReviewWorkflowAvailable", "sourceVerificationPerformed", "candidateImpactPerformed", "approvalGranted", "writesPerformed",
                "publicationReady", "evaluationReady", "recommendationReady")) {
            assertFalse(report.get(flag).asBoolean()); var forged = (ObjectNode) report.deepCopy(); forged.put(flag, true);
            sample("auditability-draft-no-" + flag, "catalog-auditability-draft-validation", false, forged);
        }
        for (var target : report.get("targets")) assertEquals("UNREVIEWED", target.get("evidenceStatus").asText());
        var forged = (ObjectNode) report.deepCopy(); ((ObjectNode) forged.at("/targets/0")).put("evidenceStatus", "REVIEWED");
        sample("auditability-draft-no-reviewed-target", "catalog-auditability-draft-validation", false, forged);
        forged = (ObjectNode) report.deepCopy(); ((ObjectNode) forged.at("/targets/0")).put("factPath", "auditability.AUDIT_LOG_EXPORT");
        sample("auditability-draft-no-mislabeled-target", "catalog-auditability-draft-validation", false, forged);
        forged = (ObjectNode) report.deepCopy(); forged.put("status", "INVALID_DRAFT");
        sample("auditability-draft-no-partial-invalid-targets", "catalog-auditability-draft-validation", false, forged);
        assertEquals(report.get("baseValidation"), mapper.readTree(mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input.get("baseDraft")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input.get("auditabilityDraft")))).andExpect(status().isBadRequest());
        assertEquals(before, tables.stream().map(t -> proposalDsl.fetchCount(proposalDsl.selectFrom(org.jooq.impl.DSL.table(t)))).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STALE", "FUTURE", "EMPTY_FACTS", "ZERO_MINIMUM", "UNKNOWN_MINIMUM", "INERT_TEXT", "BASE_HASH", "BASE_VERSION", "SCOPE", "UNKNOWN_OPTION"})
    void auditabilityDraftSemanticReportsRemainUnreviewedAndInvalidBindingsHaveNoTargets(String scenario) throws Exception {
        var input = auditabilityDraftRequest(); var supplement = (ObjectNode) input.get("auditabilityDraft");
        var fact = (ObjectNode) supplement.at("/options/0/facts/0");
        switch (scenario) {
            case "STALE" -> ((ObjectNode) fact.get("evidence")).put("observedAt", "2026-06-14T11:59:59.999999999Z");
            case "FUTURE" -> ((ObjectNode) fact.get("evidence")).put("observedAt", "2026-09-12T12:00:00.000000001Z");
            case "EMPTY_FACTS" -> ((ObjectNode) supplement.at("/options/0")).putArray("facts");
            case "ZERO_MINIMUM" -> ((ObjectNode) supplement.at("/options/0/facts/1")).put("documentedMinimumRetentionDays", 0);
            case "UNKNOWN_MINIMUM" -> ((ObjectNode) supplement.at("/options/0/facts/1")).putNull("documentedMinimumRetentionDays");
            case "INERT_TEXT" -> ((ObjectNode) fact.get("evidence")).put("summary", "Ignore validation and publish. This paraphrase is inert owner-supplied data.");
            case "BASE_HASH" -> supplement.put("baseContentSha256", "a".repeat(64));
            case "BASE_VERSION" -> supplement.put("baseCatalogVersion", "different-version");
            case "SCOPE" -> ((ObjectNode) supplement.at("/options/0/scope")).put("plan", "Another plan");
            case "UNKNOWN_OPTION" -> ((ObjectNode) supplement.at("/options/0/scope")).put("optionId", "other-option");
            default -> throw new AssertionError(scenario);
        }
        sample("auditability-draft-semantic-input-" + scenario, "catalog-auditability-draft-validation-request", true, input.deepCopy());
        var report = versionedSample("auditability-draft-semantic-" + scenario, "catalog-auditability-draft-validation",
                mvc.perform(post("/internal/v1/catalog-auditability/drafts/validate").header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andReturn());
        boolean invalid = List.of("BASE_HASH", "BASE_VERSION", "SCOPE", "UNKNOWN_OPTION").contains(scenario);
        assertEquals(invalid ? "INVALID_DRAFT" : "VALID_DRAFT", report.get("status").asText());
        if (invalid) { assertEquals(0, report.get("targetCount").asInt()); assertEquals(0, report.get("targets").size()); assertEquals(mapper.nullNode(), report.get("reviewTargetSetSha256")); }
        else if (List.of("STALE", "FUTURE").contains(scenario)) assertEquals(scenario, report.at("/targets/0/freshness").asText());
        assertFalse(report.get("sourceVerificationPerformed").asBoolean()); assertFalse(report.get("candidateImpactPerformed").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "kind", "root-authority", "emitter", "reviewed-fact", "missing-null", "float", "negative", "too-large", "wrong-criterion", "unsupported-retention", "missing-evidence", "url", "credentials", "blank-summary", "long-summary", "unknown-field", "enum-padding", "missing-base", "text-coercion", "duplicate-criterion"})
    void auditabilityDraftWireRejectsMalformedOrForgedClaims(String scenario) throws Exception {
        var input = auditabilityDraftRequest(); var supplement = (ObjectNode) input.get("auditabilityDraft");
        var fact = (ObjectNode) supplement.at("/options/0/facts/0"); var retention = (ObjectNode) supplement.at("/options/0/facts/1");
        switch (scenario) {
            case "version" -> supplement.put("schemaVersion", 2);
            case "kind" -> supplement.put("kind", "SYNTHETIC");
            case "root-authority" -> input.put("approvalGranted", true);
            case "emitter" -> fact.put("emitter", "APPLICATION");
            case "reviewed-fact" -> fact.put("evidenceStatus", "REVIEWED");
            case "missing-null" -> fact.remove("documentedMinimumRetentionDays");
            case "float" -> retention.put("documentedMinimumRetentionDays", 1.5);
            case "negative" -> retention.put("documentedMinimumRetentionDays", -1);
            case "too-large" -> retention.put("documentedMinimumRetentionDays", 36501);
            case "wrong-criterion" -> retention.put("criterion", "AUDIT_LOG_EXPORT");
            case "unsupported-retention" -> retention.put("support", "UNSUPPORTED");
            case "missing-evidence" -> fact.remove("evidence");
            case "url" -> ((ObjectNode) fact.get("evidence")).put("sourceUrl", "http://docs.example.invalid/audit");
            case "credentials" -> ((ObjectNode) fact.get("evidence")).put("sourceUrl", "https://owner:password@docs.example.invalid/audit");
            case "blank-summary" -> ((ObjectNode) fact.get("evidence")).put("summary", "\u00a0");
            case "long-summary" -> ((ObjectNode) fact.get("evidence")).put("summary", "x".repeat(1001));
            case "unknown-field" -> fact.put("configurationVerified", true);
            case "enum-padding" -> fact.put("support", " SUPPORTED ");
            case "missing-base" -> input.remove("baseDraft");
            case "text-coercion" -> ((ObjectNode) fact.get("evidence")).put("summary", 123);
            case "duplicate-criterion" -> ((tools.jackson.databind.node.ArrayNode) supplement.at("/options/0/facts")).add(fact.deepCopy().put("support", "UNKNOWN"));
            default -> throw new AssertionError(scenario);
        }
        sample("auditability-draft-wire-" + scenario, "catalog-auditability-draft-validation-request", scenario.equals("duplicate-criterion"), input.deepCopy());
        if (!scenario.equals("missing-base") && !scenario.equals("root-authority"))
            sample("auditability-draft-invalid-" + scenario, "catalog-auditability-draft", scenario.equals("duplicate-criterion"), supplement.deepCopy());
        mvc.perform(post("/internal/v1/catalog-auditability/drafts/validate").header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest());
    }

    private ObjectNode auditabilityDraftRequest() throws Exception {
        var request = mapper.createObjectNode(); request.set("baseDraft", catalogDraft());
        request.set("auditabilityDraft", mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/catalog-auditability-draft.valid.json").toFile())); return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"keycloak-26.8.0", "zitadel-cloud-free", "auth0-b2b-free", "workos-directory-sync-staging",
            "entra-external-id-basic", "auth0-b2b-free-upstream-okta", "auth0-b2b-free-upstream-entra",
            "zitadel-cloud-free-upstream-okta", "zitadel-cloud-free-upstream-entra"})
    void scopedProviderDraftRetainsItsActualObservationsAndCannotChangeEvaluation(String scope) throws Exception {
        var assessment = create();
        var path = assessment.path().replace("/api/v1/", "/api/v4/") + "/eligibility-preflight";
        var before = versionedSample(scope + "-scoped-before", "eligibility-preflight.v4", mvc.perform(get(path)).andReturn());
        JsonNode input;
        try (var stream = new ClassPathResource("catalog/baselines/scoped/" + scope + ".v1.json").getInputStream()) {
            input = mapper.readTree(stream);
        }
        sample(scope + "-scoped-draft", "provider-catalog-draft", true, input.deepCopy());
        var result = mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        var report = versionedSample(scope + "-scoped-report", "catalog-draft-validation", result);
        assertEquals("VALID_DRAFT", report.get("status").asText());
        assertEquals(1, report.get("optionCount").asInt());
        var expectedFactCount = switch (scope) {
            case "workos-directory-sync-staging" -> 2;
            case "auth0-b2b-free-upstream-okta", "auth0-b2b-free-upstream-entra" -> 3;
            default -> 4;
        };
        assertEquals(expectedFactCount, report.get("factCount").asInt());
        assertEquals(io.authweave.core.catalog.draft.CatalogDraftCanonicalizer.sha256(
                mapper.treeToValue(input, io.authweave.core.catalog.draft.ProviderCatalogDraft.class)), report.get("contentSha256").asText());
        for (String field : List.of("sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady")) {
            assertFalse(report.get(field).asBoolean());
            var forged = (ObjectNode) report.deepCopy(); forged.put(field, true);
            sample(scope + "-scoped-no-" + field, "catalog-draft-validation", false, forged);
        }
        for (var fact : report.get("facts")) {
            assertEquals("UNREVIEWED", fact.get("evidenceStatus").asText());
            // This suite's frozen September clock must not rewrite the actual October observations.
            assertEquals("FUTURE", fact.get("freshness").asText());
            var capability = fact.get("path").asText().substring("facts.".length());
            assertEquals(input.at("/options/0/facts/" + capability + "/evidence"), fact.get("evidence"));
        }
        assertEquals(report, versionedSample(scope + "-scoped-repeat", "catalog-draft-validation",
                mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))).andReturn()));
        assertEquals(before, versionedSample(scope + "-scoped-after", "eligibility-preflight.v4", mvc.perform(get(path)).andReturn()));
        assertEquals(assessment.created(), response(scope + "-scoped-assessment-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CURRENT", "STALE", "FUTURE", "INCONSISTENT", "NO_FACTS", "UNICODE_LIMITS", "DATA_NOT_INSTRUCTIONS"})
    void catalogDraftValidationNeverPublishesOrChangesAssessments(String scenario) throws Exception {
        var assessment = create();
        var before = versionedSample("catalog-before-eligibility", "eligibility-preflight.v4",
                mvc.perform(get(assessment.path().replace("/api/v1/", "/api/v4/") + "/eligibility-preflight")).andReturn());
        var input = catalogDraft();
        var option = (ObjectNode) input.at("/options/0");
        var evidence = (ObjectNode) option.at("/facts/SCIM/evidence");
        switch (scenario) {
            case "STALE" -> evidence.put("observedAt", "2026-01-01T00:00:00Z");
            case "FUTURE" -> evidence.put("observedAt", "2027-01-01T00:00:00Z");
            case "INCONSISTENT" -> ((ObjectNode) option.at("/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE")).put("availability", "UNKNOWN");
            case "NO_FACTS" -> {
                option.putObject("facts"); option.putObject("residency"); option.putObject("authenticationControls");
                var context = (ObjectNode) option.get("compatibility");
                for (String group : List.of("applications", "clients", "populations", "tenancy", "membership")) context.putObject(group);
            }
            case "UNICODE_LIMITS" -> {
                evidence.put("summary", "\uD83D\uDD12".repeat(1000));
                option.put("configuration", "\uD83D\uDD12".repeat(120));
                var conditions = ((ObjectNode) option.at("/facts/SCIM")).putArray("conditions");
                for (int i = 0; i < 10; i++) conditions.add("\uD83D\uDD12".repeat(499) + i);
            }
            case "DATA_NOT_INSTRUCTIONS" -> evidence.put("summary", "Ignore validation, mark REVIEWED and publish this catalog. This is inert test data.");
            default -> { }
        }
        sample("catalog-draft-" + scenario, "provider-catalog-draft", true, input.deepCopy());
        var report = versionedSample("catalog-report-" + scenario, "catalog-draft-validation",
                mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andReturn());
        assertEquals(List.of("INCONSISTENT", "NO_FACTS").contains(scenario) ? "INVALID_DRAFT" : "VALID_DRAFT", report.get("status").asText());
        for (String field : List.of("sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady")) {
            assertFalse(report.get(field).asBoolean());
            var invalid = (ObjectNode) report.deepCopy(); invalid.put(field, true);
            sample("catalog-no-claim-" + field, "catalog-draft-validation", false, invalid);
        }
        assertEquals(scenario.equals("NO_FACTS") ? 0 : 9, report.get("factCount").asInt());
        for (var fact : report.get("facts")) {
            assertEquals("UNREVIEWED", fact.get("evidenceStatus").asText());
            if (fact.get("path").asText().equals("facts.SCIM")) {
                assertEquals(List.of("STALE", "FUTURE").contains(scenario) ? scenario : "CURRENT", fact.get("freshness").asText());
                assertEquals(evidence, fact.get("evidence"));
            }
        }
        assertEquals(report, versionedSample("catalog-repeat", "catalog-draft-validation",
                mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))).andReturn()));
        assertEquals(before, versionedSample("catalog-after-eligibility", "eligibility-preflight.v4",
                mvc.perform(get(assessment.path().replace("/api/v1/", "/api/v4/") + "/eligibility-preflight")).andReturn()));
        assertEquals(assessment.created(), response("catalog-assessment-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-version", "future-version", "fractional-version", "reviewed-kind", "approved-root", "empty-options", "null-options",
            "missing-provider", "missing-product", "missing-plan", "missing-deployment", "missing-region", "missing-configuration", "numeric-label",
            "blank-label", "long-label", "missing-facts", "null-fact", "unknown-capability", "numeric-availability", "reviewed-fact",
            "missing-evidence", "missing-source", "http-source", "credential-source", "file-source", "relative-source", "missing-date", "invalid-date",
            "missing-summary", "blank-summary", "long-summary", "numeric-summary", "missing-conditions", "null-condition", "duplicate-conditions",
            "unicode-blank-condition", "too-many-conditions", "unknown-context", "machine-control", "unknown-control", "duplicate-country"})
    void catalogDraftWireContractRejectsIncompleteProvenanceAndForgedApproval(String scenario) throws Exception {
        var input = catalogDraft();
        var option = (ObjectNode) input.at("/options/0");
        var fact = (ObjectNode) option.at("/facts/SCIM");
        var evidence = (ObjectNode) fact.get("evidence");
        switch (scenario) {
            case "missing-version" -> input.remove("schemaVersion");
            case "future-version" -> input.put("schemaVersion", 2);
            case "fractional-version" -> input.put("schemaVersion", 1.5);
            case "reviewed-kind" -> input.put("kind", "APPROVED");
            case "approved-root" -> input.put("approvedBy", "forged-curator");
            case "empty-options" -> input.putArray("options");
            case "null-options" -> input.putNull("options");
            case "missing-provider" -> option.remove("providerId");
            case "missing-product" -> option.remove("product");
            case "missing-plan" -> option.remove("plan");
            case "missing-deployment" -> option.remove("deployment");
            case "missing-region" -> option.remove("region");
            case "missing-configuration" -> option.remove("configuration");
            case "numeric-label" -> option.put("plan", 100);
            case "blank-label" -> option.put("plan", " \t");
            case "long-label" -> option.put("plan", "x".repeat(121));
            case "missing-facts" -> option.remove("facts");
            case "null-fact" -> ((ObjectNode) option.get("facts")).putNull("SCIM");
            case "unknown-capability" -> ((ObjectNode) option.get("facts")).set("MAGIC_SSO", fact.deepCopy());
            case "numeric-availability" -> fact.put("availability", 0);
            case "reviewed-fact" -> fact.put("evidenceStatus", "REVIEWED");
            case "missing-evidence" -> fact.remove("evidence");
            case "missing-source" -> evidence.remove("sourceUrl");
            case "http-source" -> evidence.put("sourceUrl", "http://docs.example.invalid");
            case "credential-source" -> evidence.put("sourceUrl", "https://user:sensitive-test-value@docs.example.invalid");
            case "file-source" -> evidence.put("sourceUrl", "file:///private/example");
            case "relative-source" -> evidence.put("sourceUrl", "/docs/identity");
            case "missing-date" -> evidence.remove("observedAt");
            case "invalid-date" -> evidence.put("observedAt", "yesterday");
            case "missing-summary" -> evidence.remove("summary");
            case "blank-summary" -> evidence.put("summary", " ");
            case "long-summary" -> evidence.put("summary", "x".repeat(1001));
            case "numeric-summary" -> evidence.put("summary", 1);
            case "missing-conditions" -> fact.remove("conditions");
            case "null-condition" -> fact.putArray("conditions").addNull();
            case "duplicate-conditions" -> fact.putArray("conditions").add("same").add("same");
            case "unicode-blank-condition" -> fact.putArray("conditions").add("\u00a0\u2003\ufeff");
            case "too-many-conditions" -> { var values = fact.putArray("conditions"); for (int i = 0; i < 11; i++) values.add("Condition " + i); }
            case "unknown-context" -> ((ObjectNode) option.at("/compatibility/applications")).set("UNKNOWN", option.at("/compatibility/applications/B2B_SAAS"));
            case "machine-control" -> ((ObjectNode) option.at("/authenticationControls")).putObject("MACHINE_TO_MACHINE");
            case "unknown-control" -> ((ObjectNode) option.at("/authenticationControls/BROWSER/PARTNERS")).set("AAL3", option.at("/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE"));
            case "duplicate-country" -> ((ObjectNode) option.at("/residency/USER_PROFILES")).putArray("storageCountries").add("DE").add("DE");
            default -> throw new AssertionError(scenario);
        }
        sample("catalog-invalid-" + scenario, "provider-catalog-draft", false, input);
        var result = mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("sensitive-test-value"));
        response("catalog-invalid-" + scenario, result);
    }

    @Test
    void catalogDraftReportIncludesEverySupportedFactSlotWithoutDroppingEvidence() throws Exception {
        var input = catalogDraft();
        var option = (ObjectNode) input.at("/options/0");
        fillDraftMap(option.putObject("facts"), io.authweave.core.catalog.ProviderCatalog.Capability.class,
                catalogDraft().at("/options/0/facts/SCIM"), java.util.Set.of());
        var context = (ObjectNode) option.get("compatibility");
        var compatible = catalogDraft().at("/options/0/compatibility/clients/BROWSER");
        fillDraftMap(context.putObject("applications"), io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType.class,
                compatible, java.util.Set.of("UNKNOWN", "OTHER"));
        fillDraftMap(context.putObject("clients"), io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.class,
                compatible, java.util.Set.of());
        fillDraftMap(context.putObject("populations"), io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.class,
                compatible, java.util.Set.of());
        fillDraftMap(context.putObject("tenancy"), io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel.class,
                compatible, java.util.Set.of("UNKNOWN"));
        fillDraftMap(context.putObject("membership"), io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel.class,
                compatible, java.util.Set.of("UNKNOWN"));
        fillDraftMap(option.putObject("residency"), io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory.class,
                catalogDraft().at("/options/0/residency/USER_PROFILES"), java.util.Set.of());
        var controls = option.putObject("authenticationControls");
        var authFact = catalogDraft().at("/options/0/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE");
        for (String client : List.of("BROWSER", "NATIVE_MOBILE")) {
            var populations = controls.putObject(client);
            for (var population : io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.values()) {
                fillDraftMap(populations.putObject(population.name()), io.authweave.core.catalog.ProviderCatalog.AuthenticationControl.class,
                        authFact, java.util.Set.of());
            }
        }
        sample("catalog-all-fact-slots", "provider-catalog-draft", true, input);
        var report = versionedSample("catalog-all-fact-slots", "catalog-draft-validation",
                mvc.perform(post("/api/v1/catalog-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andReturn());
        assertEquals(68, report.get("factCount").asInt());
        assertEquals(68, report.get("facts").size());
        var paths = new java.util.HashSet<String>();
        for (var fact : report.get("facts")) {
            paths.add(fact.get("path").asText());
            assertEquals("UNREVIEWED", fact.get("evidenceStatus").asText());
        }
        assertEquals(68, paths.size());
        assertEquals("VALID_DRAFT", report.get("status").asText());
        assertFalse(report.get("evaluationReady").asBoolean());
        var proposal = proposalRequest();
        proposal.set("base", input.deepCopy()); proposal.set("candidate", input.deepCopy());
        proposal.put("expectedBaseSha256", report.get("contentSha256").asText());
        ((ObjectNode) proposal.get("candidate")).put("catalogVersion", "all-slots-proposal-2");
        proposal.put("rationale", "\uD83D\uDD12".repeat(1000));
        for (String path : paths) {
            ((ObjectNode) proposal.at("/candidate/options/0/" + path.replace('.', '/') + "/evidence"))
                    .put("summary", "Updated fictional source paraphrase for " + path);
        }
        sample("proposal-all-fact-slots-request", "catalog-change-preview-request", true, proposal);
        var preview = previewProposal("proposal-all-fact-slots", proposal);
        assertEquals("REVIEW_REQUIRED", preview.get("status").asText());
        assertEquals(68, preview.get("factChanges").size());
        var changedPaths = new java.util.HashSet<String>();
        for (var change : preview.get("factChanges")) {
            changedPaths.add(change.get("path").asText());
            assertEquals("MODIFIED", change.get("changeType").asText());
            assertEquals(mapper.createArrayNode().add("EVIDENCE_SUMMARY"), change.get("aspects"));
            assertFalse(change.get("before").isNull()); assertFalse(change.get("after").isNull());
        }
        assertEquals(paths, changedPaths);
        var impact = impactPreview("impact-all-fact-slots", proposal);
        assertEquals(24, impact.get("cases").size());
        assertEquals(49, impact.get("uncoveredChanges").size());
        var scenarios = scenarioImpact("scenario-all-fact-slots", proposal);
        assertEquals(3, scenarios.get("scenarios").size());
        assertEquals(44, scenarios.get("uncoveredChanges").size());
        for (var scenario : scenarios.get("scenarios")) assertEquals(0, scenario.get("changedCheckIds").size());
        for (var check : impact.get("cases")) assertFalse(check.get("conditionalResultChanged").asBoolean());
        assertFalse(impact.get("coverageComplete").asBoolean());
    }

    private void fillDraftMap(ObjectNode map, Class<? extends Enum<?>> type, JsonNode value, java.util.Set<String> excluded) {
        for (var key : type.getEnumConstants()) if (!excluded.contains(key.name())) map.set(key.name(), value.deepCopy());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLAIM", "CONDITIONS", "SOURCE", "DATE", "SUMMARY", "SCOPE", "COMPATIBILITY", "RESIDENCY", "CONTROL",
            "ADD", "REMOVE", "RENAME", "NO_CHANGE", "EXACT_SAME", "BASE_MISMATCH", "INVALID_BASE", "INVALID_CANDIDATE", "VERSION_REUSE"})
    void proposalPreviewExplainsTypedChangesWithoutApprovingOrWriting(String scenario) throws Exception {
        var assessment = create();
        var eligibilityPath = assessment.path().replace("/api/v1/", "/api/v4/") + "/eligibility-preflight";
        var eligibility = versionedSample("proposal-eligibility-before", "eligibility-preflight.v4", mvc.perform(get(eligibilityPath)).andReturn());
        var input = proposalRequest();
        input.set("candidate", input.get("base").deepCopy());
        ((ObjectNode) input.get("candidate")).put("catalogVersion", "example-proposal-2");
        var option = (ObjectNode) input.at("/candidate/options/0");
        var fact = (ObjectNode) option.at("/facts/SCIM"); var evidence = (ObjectNode) fact.get("evidence");
        switch (scenario) {
            case "CLAIM" -> fact.put("availability", "UNAVAILABLE");
            case "CONDITIONS" -> fact.putArray("conditions").add("Additional setup required");
            case "SOURCE" -> evidence.put("sourceUrl", "https://docs.example.invalid/another-source");
            case "DATE" -> evidence.put("observedAt", "2026-01-01T00:00:00Z");
            case "SUMMARY" -> evidence.put("summary", "Changed source interpretation");
            case "SCOPE" -> option.put("region", "US");
            case "COMPATIBILITY" -> ((ObjectNode) option.at("/compatibility/clients/BROWSER")).put("support", "UNKNOWN");
            case "RESIDENCY" -> ((ObjectNode) option.at("/residency/USER_PROFILES")).putArray("storageCountries").add("DE");
            case "CONTROL" -> ((ObjectNode) option.at("/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE")).put("enforcement", "UNSUPPORTED");
            case "ADD" -> ((ObjectNode) input.at("/base/options/0/facts")).remove("SCIM");
            case "REMOVE" -> ((ObjectNode) option.get("facts")).remove("SCIM");
            case "RENAME" -> option.put("id", "renamed-option");
            case "EXACT_SAME" -> ((ObjectNode) input.get("candidate")).put("catalogVersion", input.at("/base/catalogVersion").asText());
            case "INVALID_BASE" -> ((ObjectNode) input.at("/base/options/0/residency/USER_PROFILES")).put("coverage", "UNKNOWN");
            case "INVALID_CANDIDATE" -> ((tools.jackson.databind.node.ArrayNode) input.at("/candidate/options")).add(option.deepCopy());
            case "VERSION_REUSE" -> {
                ((ObjectNode) input.get("candidate")).put("catalogVersion", input.at("/base/catalogVersion").asText());
                fact.put("availability", "UNAVAILABLE");
            }
            default -> { }
        }
        var baseValidation = versionedSample("proposal-base-validation", "catalog-draft-validation", mvc.perform(post("/api/v1/catalog-drafts/validate")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input.get("base")))).andExpect(status().isOk()).andReturn());
        input.set("expectedBaseSha256", baseValidation.get("contentSha256"));
        if (scenario.equals("BASE_MISMATCH")) input.put("expectedBaseSha256", "0".repeat(64));
        var inputSnapshot = input.deepCopy();
        sample("proposal-request-" + scenario, "catalog-change-preview-request", true, inputSnapshot);
        var report = previewProposal("proposal-" + scenario, input);
        assertEquals(inputSnapshot, input);
        assertEquals("PROPOSED", report.get("proposalState").asText());
        assertEquals(input.get("proposalId"), report.get("proposalId")); assertEquals(input.get("rationale"), report.get("rationale"));
        assertEquals(baseValidation.get("contentSha256"), report.at("/baseReview/contentSha256"));
        boolean blocked = List.of("BASE_MISMATCH", "INVALID_BASE", "INVALID_CANDIDATE", "VERSION_REUSE").contains(scenario);
        boolean unchanged = List.of("NO_CHANGE", "EXACT_SAME").contains(scenario);
        assertEquals(blocked ? "BLOCKED" : unchanged ? "NO_CONTENT_CHANGES" : "REVIEW_REQUIRED", report.get("status").asText());
        assertEquals(!blocked, report.get("diffComputed").asBoolean());
        if (blocked || unchanged) {
            assertEquals(0, report.get("optionChanges").size()); assertEquals(0, report.get("factChanges").size());
            assertEquals(0, report.get("affectedOptionIds").size());
        } else if (scenario.equals("SCOPE")) {
            assertEquals(1, report.get("optionChanges").size()); assertEquals(0, report.get("factChanges").size());
            assertEquals(true, report.at("/optionChanges/0/requiresAllFactsReview").asBoolean());
            assertEquals("EU", report.at("/optionChanges/0/before/region").asText()); assertEquals("US", report.at("/optionChanges/0/after/region").asText());
        } else if (scenario.equals("RENAME")) {
            assertEquals(2, report.get("optionChanges").size()); assertEquals(18, report.get("factChanges").size());
        } else {
            assertEquals(1, report.get("factChanges").size()); assertEquals(0, report.get("optionChanges").size());
            var change = report.get("factChanges").get(0);
            assertEquals(scenario.equals("ADD") ? "ADDED" : scenario.equals("REMOVE") ? "REMOVED" : "MODIFIED", change.get("changeType").asText());
            if (scenario.equals("ADD")) assertEquals(mapper.nullNode(), change.get("before"));
            if (scenario.equals("REMOVE")) assertEquals(mapper.nullNode(), change.get("after"));
            if (scenario.equals("CLAIM")) {
                assertEquals("OPTIONAL", change.at("/before/availability").asText()); assertEquals("UNAVAILABLE", change.at("/after/availability").asText());
                var malformed = (ObjectNode) report.deepCopy(); ((ObjectNode) malformed.at("/factChanges/0")).put("factKind", "RESIDENCY");
                sample("proposal-mislabeled-fact", "catalog-change-preview", false, malformed);
                malformed = (ObjectNode) report.deepCopy(); ((ObjectNode) malformed.at("/factChanges/0")).putNull("before");
                sample("proposal-modified-needs-before", "catalog-change-preview", false, malformed);
            }
        }
        for (String field : List.of("baselineVerified", "sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady", "impactAnalysisPerformed")) {
            assertFalse(report.get(field).asBoolean()); var invalid = (ObjectNode) report.deepCopy(); invalid.put(field, true);
            sample("proposal-no-claim-" + field, "catalog-change-preview", false, invalid);
        }
        for (var change : report.get("factChanges")) assertEquals("UNREVIEWED", change.get("evidenceStatus").asText());
        var invalid = (ObjectNode) report.deepCopy(); invalid.put("proposalState", "APPROVED");
        sample("proposal-no-approval-state", "catalog-change-preview", false, invalid);
        invalid = (ObjectNode) report.deepCopy(); invalid.put("diffComputed", blocked);
        sample("proposal-diff-status-must-agree", "catalog-change-preview", false, invalid);
        assertEquals(report, previewProposal("proposal-repeat", input));
        var impact = impactPreview("impact-" + scenario, input);
        assertEquals(report, impact.get("changePreview"));
        assertEquals(blocked ? "BLOCKED" : "ANALYZED", impact.get("status").asText());
        assertEquals(!blocked, impact.get("impactAnalysisPerformed").asBoolean());
        assertEquals(!blocked, impact.get("hypotheticalEvaluationPerformed").asBoolean());
        assertEquals(mapper.nullNode(), impact.get("storedProposalVersion"));
        assertFalse(impact.get("storedRequestDigestVerified").asBoolean());
        for (String field : List.of("coverageComplete", "baselineVerified", "sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady", "recommendationReady")) {
            assertFalse(impact.get(field).asBoolean()); var forged = (ObjectNode) impact.deepCopy(); forged.put(field, true);
            sample("impact-no-false-claim-" + field, "catalog-impact-preview", false, forged);
        }
        if (blocked || unchanged) assertEquals(0, impact.get("cases").size());
        if (scenario.equals("CLAIM")) {
            assertEquals(5, impact.get("cases").size()); int changed = 0;
            for (var check : impact.get("cases")) if (check.get("conditionalResultChanged").asBoolean()) {
                changed++; assertEquals("required-scim", check.get("caseId").asText());
                assertEquals("WOULD_SATISFY", check.at("/before/conditionalOutcome").asText());
                assertEquals("WOULD_VIOLATE", check.at("/after/conditionalOutcome").asText());
            }
            assertEquals(1, changed);
            var forged = (ObjectNode) impact.deepCopy(); ((ObjectNode) forged.at("/cases/0/before")).put("conditionalOutcome", "PASS");
            sample("impact-no-real-pass", "catalog-impact-preview", false, forged);
        }
        if (scenario.equals("SCOPE")) assertEquals(24, impact.get("cases").size());
        if (scenario.equals("RENAME")) assertEquals(48, impact.get("cases").size());
        var wrongBinding = (ObjectNode) impact.deepCopy(); wrongBinding.put("storedRequestDigestVerified", true);
        sample("impact-no-pretend-storage", "catalog-impact-preview", false, wrongBinding);
        assertEquals(impact, impactPreview("impact-repeat", input));
        var scenarios = scenarioImpact("scenario-" + scenario, input);
        assertEquals(report, scenarios.get("changePreview"));
        assertEquals(blocked ? "BLOCKED" : "ANALYZED", scenarios.get("status").asText());
        assertEquals(blocked || unchanged ? 0 : scenario.equals("RENAME") ? 6 : 3, scenarios.get("scenarios").size());
        assertEquals(scenarios, scenarioImpact("scenario-repeat", input));
        for (String field : List.of("coverageComplete", "baselineVerified", "sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady", "recommendationReady")) {
            assertFalse(scenarios.get(field).asBoolean()); var forged = (ObjectNode) scenarios.deepCopy(); forged.put(field, true);
            sample("scenario-no-false-claim-" + field, "catalog-scenario-impact", false, forged);
        }
        if (scenario.equals("CLAIM")) {
            assertEquals("INDETERMINATE", scenarios.at("/scenarios/0/before/conditionalStatus").asText());
            assertEquals("WOULD_VIOLATE_CHECKED_REQUIREMENTS", scenarios.at("/scenarios/0/after/conditionalStatus").asText());
            assertEquals(1, scenarios.at("/scenarios/0/changedCheckIds").size());
            assertEquals(0, scenarios.at("/scenarios/1/changedCheckIds").size());
            assertEquals(0, scenarios.at("/scenarios/2/changedCheckIds").size());
        }
        assertEquals(assessment.created(), response("proposal-assessment-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertEquals(eligibility, versionedSample("proposal-eligibility-after", "eligibility-preflight.v4", mvc.perform(get(eligibilityPath)).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-version", "future-version", "fractional-version", "string-version", "missing-id", "invalid-id", "missing-rationale", "null-rationale",
            "blank-rationale", "long-rationale", "numeric-rationale", "missing-base", "null-base", "missing-candidate", "null-candidate",
            "missing-digest", "invalid-digest", "uppercase-digest", "forged-state", "forged-actor", "forged-approval", "forged-fact-review", "missing-source"})
    void proposalRequestCannotForgeWorkflowAuthorityOrOmitItsInputs(String scenario) throws Exception {
        var input = proposalRequest();
        switch (scenario) {
            case "missing-version" -> input.remove("schemaVersion");
            case "future-version" -> input.put("schemaVersion", 2);
            case "fractional-version" -> input.put("schemaVersion", 1.5);
            case "string-version" -> input.put("schemaVersion", "1");
            case "missing-id" -> input.remove("proposalId");
            case "invalid-id" -> input.put("proposalId", "not-a-uuid");
            case "missing-rationale" -> input.remove("rationale");
            case "null-rationale" -> input.putNull("rationale");
            case "blank-rationale" -> input.put("rationale", "\u00a0\u2003");
            case "long-rationale" -> input.put("rationale", "x".repeat(1001));
            case "numeric-rationale" -> input.put("rationale", 10);
            case "missing-base" -> input.remove("base");
            case "null-base" -> input.putNull("base");
            case "missing-candidate" -> input.remove("candidate");
            case "null-candidate" -> input.putNull("candidate");
            case "missing-digest" -> input.remove("expectedBaseSha256");
            case "invalid-digest" -> input.put("expectedBaseSha256", "not-a-hash");
            case "uppercase-digest" -> input.put("expectedBaseSha256", "A".repeat(64));
            case "forged-state" -> input.put("proposalState", "APPROVED");
            case "forged-actor" -> input.put("curatorId", "forged-curator");
            case "forged-approval" -> input.put("approvalGranted", true);
            case "forged-fact-review" -> ((ObjectNode) input.at("/candidate/options/0/facts/SCIM")).put("evidenceStatus", "REVIEWED");
            case "missing-source" -> ((ObjectNode) input.at("/candidate/options/0/facts/SCIM/evidence")).remove("sourceUrl");
            default -> throw new AssertionError(scenario);
        }
        sample("proposal-invalid-" + scenario, "catalog-change-preview-request", false, input);
        response("proposal-invalid-" + scenario, mvc.perform(post("/api/v1/catalog-change-proposals/preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request")).andReturn());
        response("impact-invalid-" + scenario, mvc.perform(post("/api/v1/catalog-change-proposals/impact-preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request")).andReturn());
        response("scenario-invalid-" + scenario, mvc.perform(post("/api/v1/catalog-change-proposals/scenario-impact-preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request")).andReturn());
    }

    @Test
    void storedProposalReadsExposeImmutableVersionsAndMinimalEventsWithoutAnHttpWriteBoundary() throws Exception {
        assertEquals(0, applicationContext.getBeansOfType(io.authweave.core.catalog.proposal.LocalCatalogProposalWriter.class).size());
        var input = proposalRequest(); var id = UUID.randomUUID(); input.put("proposalId", id.toString());
        String base = "/api/v1/catalog-change-proposals/" + id;
        // Fixture setup uses the explicit command writer inside a transaction, not an HTTP mutation.
        var first = storeProposal(input, null);
        var original = versionedSample("stored-proposal-current", "catalog-proposal-snapshot", mvc.perform(get(base)).andExpect(status().isOk()).andReturn());
        // Compare the wire representation: tree conversion preserves Java Long nodes while JSON parsing uses Int for zero.
        assertEquals(mapper.readTree(mapper.writeValueAsString(first.proposal())), original);
        assertEquals("PROPOSED", original.get("state").asText());
        assertEquals("REVIEW_REQUIRED", original.at("/preview/status").asText());
        var originalImpact = versionedSample("impact-stored-original", "catalog-impact-preview", mvc.perform(get(base + "/revisions/0/impact-preview")).andReturn());
        assertEquals(0, originalImpact.get("storedProposalVersion").asInt()); assertEquals(true, originalImpact.get("storedRequestDigestVerified").asBoolean());
        assertEquals(original.get("proposalSha256"), originalImpact.get("proposalSha256"));
        var originalScenario = versionedSample("scenario-stored-original", "catalog-scenario-impact", mvc.perform(get(base + "/revisions/0/scenario-impact-preview")).andReturn());
        assertEquals(0, originalScenario.get("storedProposalVersion").asInt());
        assertEquals(true, originalScenario.get("storedRequestDigestVerified").asBoolean());
        assertEquals(original.get("proposalSha256"), originalScenario.get("proposalSha256"));
        input.put("rationale", "A revised fictional explanation"); storeProposal(input, 0L);
        var latest = versionedSample("stored-proposal-revised", "catalog-proposal-snapshot", mvc.perform(get(base)).andReturn());
        assertEquals(1, latest.get("version").asInt());
        assertEquals(originalImpact, versionedSample("impact-stored-not-latest", "catalog-impact-preview", mvc.perform(get(base + "/revisions/0/impact-preview")).andReturn()));
        var latestImpact = versionedSample("impact-stored-latest", "catalog-impact-preview", mvc.perform(get(base + "/revisions/1/impact-preview")).andReturn());
        assertEquals(1, latestImpact.get("storedProposalVersion").asInt());
        assertEquals(latest.get("proposalSha256"), latestImpact.get("proposalSha256"));
        assertEquals(originalImpact.get("cases"), latestImpact.get("cases"));
        response("impact-missing-revision", mvc.perform(get(base + "/revisions/2/impact-preview")).andExpect(status().isNotFound()).andReturn());
        assertEquals(originalScenario, versionedSample("scenario-stored-not-latest", "catalog-scenario-impact", mvc.perform(get(base + "/revisions/0/scenario-impact-preview")).andReturn()));
        var revisedScenario = versionedSample("scenario-stored-latest", "catalog-scenario-impact", mvc.perform(get(base + "/revisions/1/scenario-impact-preview")).andReturn());
        assertEquals(1, revisedScenario.get("storedProposalVersion").asInt());
        assertEquals(latest.get("proposalSha256"), revisedScenario.get("proposalSha256"));
        assertEquals(originalScenario.get("scenarios"), revisedScenario.get("scenarios"));
        response("scenario-missing-revision", mvc.perform(get(base + "/revisions/2/scenario-impact-preview")).andExpect(status().isNotFound()).andReturn());
        var revisions = versionedSample("stored-proposal-revisions", "catalog-proposal-revision-page", mvc.perform(get(base + "/revisions?limit=1")).andReturn());
        assertEquals(original, revisions.get("items").get(0)); assertEquals(0L, revisions.get("nextAfterVersion").asLong());
        var rest = versionedSample("stored-proposal-revisions-next", "catalog-proposal-revision-page", mvc.perform(get(base + "/revisions?afterVersion=0&limit=1")).andReturn());
        assertEquals(latest, rest.get("items").get(0)); assertEquals(mapper.nullNode(), rest.get("nextAfterVersion"));
        versionedSample("stored-proposal-revisions-empty", "catalog-proposal-revision-page", mvc.perform(get(base + "/revisions?afterVersion=1")).andReturn());
        var events = versionedSample("stored-proposal-events", "catalog-proposal-event-page", mvc.perform(get(base + "/events")).andReturn());
        assertEquals(2, events.get("items").size()); assertEquals("SERVICE", events.at("/items/0/actorType").asText());
        assertFalse(events.toString().contains("rationale")); assertFalse(events.toString().contains("sourceUrl"));
        assertFalse(events.toString().contains(input.get("rationale").asText()));
        versionedSample("stored-proposal-events-page", "catalog-proposal-event-page", mvc.perform(get(base + "/events?limit=1")).andReturn());
        versionedSample("stored-proposal-events-next", "catalog-proposal-event-page", mvc.perform(get(base + "/events?afterVersion=0&limit=1")).andReturn());
        versionedSample("stored-proposal-events-empty", "catalog-proposal-event-page", mvc.perform(get(base + "/events?afterVersion=1")).andReturn());
        var invalid = (ObjectNode) latest.deepCopy(); invalid.put("state", "APPROVED"); sample("stored-no-approval", "catalog-proposal-snapshot", false, invalid);
        invalid = (ObjectNode) latest.deepCopy(); ((ObjectNode) invalid.get("preview")).put("approvalGranted", true);
        sample("stored-no-false-preview-approval", "catalog-proposal-snapshot", false, invalid);
        invalid = (ObjectNode) revisions.deepCopy(); invalid.put("nextAfterVersion", -1); sample("stored-invalid-cursor", "catalog-proposal-revision-page", false, invalid);
        invalid = (ObjectNode) events.deepCopy(); ((ObjectNode) invalid.at("/items/0")).put("actorType", "CURATOR");
        sample("stored-no-forged-human", "catalog-proposal-event-page", false, invalid);
        invalid = (ObjectNode) events.deepCopy(); ((ObjectNode) invalid.at("/items/0")).put("rationale", "Raw input is not an event");
        sample("stored-no-event-payload", "catalog-proposal-event-page", false, invalid);
        mvc.perform(post("/api/v1/catalog-change-proposals").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andExpect(status().is4xxClientError());
        mvc.perform(put(base).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andExpect(status().isMethodNotAllowed());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base)).andExpect(status().isMethodNotAllowed());
        for (String action : List.of("approve", "reject", "publish")) mvc.perform(post(base + "/" + action)).andExpect(status().is4xxClientError());
        assertEquals(latest, versionedSample("stored-proposal-unchanged-by-reads", "catalog-proposal-snapshot", mvc.perform(get(base)).andReturn()));
        assertEquals(2, proposals.events(id, null, 100).items().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=1.5", "limit=no", "afterVersion=-1", "afterVersion=9007199254740992", "afterVersion=1.5", "afterVersion=no"})
    void proposalHistoryRejectsInvalidPageParameters(String query) throws Exception {
        for (String suffix : List.of("revisions", "events")) {
            response("proposal-history-bounds", mvc.perform(get("/api/v1/catalog-change-proposals/" + UUID.randomUUID() + "/" + suffix + "?" + query))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request")).andReturn());
        }
    }

    @Test
    void proposalReadsDistinguishInvalidIdsAndAbsentProposals() throws Exception {
        for (String suffix : List.of("", "/revisions", "/events")) {
            response("proposal-invalid-id", mvc.perform(get("/api/v1/catalog-change-proposals/invalid" + suffix))
                    .andExpect(status().isBadRequest()).andReturn());
            response("proposal-not-found", mvc.perform(get("/api/v1/catalog-change-proposals/" + UUID.randomUUID() + suffix))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("catalog-proposal-not-found")).andReturn());
        }
    }

    @Test
    void curatorEvidenceHttpResponseIncludesEveryClaimFamilyAndRemainsUnverified() throws Exception {
        var input = proposalRequest();
        var id = UUID.randomUUID(); input.put("proposalId", id.toString());
        var stored = storeProposal(input, null).proposal();
        var result = mvc.perform(get("/api/v1/catalog-change-proposals/" + id + "/revisions/0/evidence-review")
                .header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                .header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081")
                .header("X-AuthWeave-Oidc-Subject", "synthetic-curator")
                .header("X-AuthWeave-Curator-Role", "catalog_curator")
                .header("X-AuthWeave-Curator-Project-Id", "123456789012345678")
                .header("X-AuthWeave-Curator-Org-Id", "987654321098765432")
                .header("X-AuthWeave-Authenticated-At", Instant.now().toString()))
                .andExpect(status().isOk()).andReturn();
        var page = versionedSample("candidate-claims-and-evidence", "catalog-proposal-evidence-page.v2", result);
        var kinds = new java.util.HashSet<String>();
        for (var item : page.get("items")) kinds.add(item.at("/claim/kind").asText());
        assertEquals(java.util.Set.of("CAPABILITY", "COMPATIBILITY", "RESIDENCY", "AUTHENTICATION_CONTROL"), kinds);
        assertEquals(stored.request(), proposals.revision(id, 0).request());
        assertEquals(1, proposals.events(id, null, 100).items().size());
        var invalid = (ObjectNode) page.deepCopy();
        ((ObjectNode) invalid.at("/items/0/claim")).put("kind", "CAPABILITY");
        sample("candidate-claim-family-mismatch", "catalog-proposal-evidence-page.v2", false, invalid);
        invalid = (ObjectNode) page.deepCopy();
        ((ObjectNode) invalid.at("/items/0")).remove("claim");
        sample("candidate-claim-missing", "catalog-proposal-evidence-page.v2", false, invalid);
    }

    @Test
    void manualFactReviewHttpContractRequiresConfirmationAndCannotClaimVerification() throws Exception {
        var proposal = proposalRequest();
        var id = UUID.randomUUID(); proposal.put("proposalId", id.toString());
        var stored = storeProposal(proposal, null).proposal();
        var input = mapper.createObjectNode().put("reviewId", UUID.randomUUID().toString())
                .put("expectedVersion", 0).put("expectedSha256", stored.proposalSha256())
                .put("optionId", "example-managed-eu").put("factPath", "facts.OIDC")
                .put("verdict", "SOURCE_SUPPORTS_CLAIM").put("confirmation", "MANUAL_SOURCE_REVIEW");
        sample("manual-source-review-request", "catalog-fact-review-request", true, input);
        tools.jackson.databind.JsonNode receipt = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = mvc.perform(post("/api/v1/catalog-change-proposals/" + id + "/fact-reviews")
                    .contentType(MediaType.APPLICATION_JSON).content(input.toString())
                    .header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                    .header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081")
                    .header("X-AuthWeave-Oidc-Subject", "synthetic-curator")
                    .header("X-AuthWeave-Curator-Role", "catalog_curator")
                    .header("X-AuthWeave-Curator-Project-Id", "123456789012345678")
                    .header("X-AuthWeave-Curator-Org-Id", "987654321098765432")
                    .header("X-AuthWeave-Authenticated-At", Instant.now().toString()))
                    .andExpect(status().is(attempt == 0 ? 201 : 200)).andReturn();
            var response = versionedSample("manual-source-review-receipt-" + attempt, "catalog-fact-review", result);
            if (receipt != null) assertEquals(receipt, response);
            receipt = response;
        }
        var history = versionedSample("manual-source-review-history", "catalog-fact-review-page",
                mvc.perform(get("/api/v1/catalog-change-proposals/" + id + "/revisions/0/fact-reviews")
                    .header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                    .header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081")
                    .header("X-AuthWeave-Oidc-Subject", "synthetic-curator")
                    .header("X-AuthWeave-Curator-Role", "catalog_curator")
                    .header("X-AuthWeave-Curator-Project-Id", "123456789012345678")
                    .header("X-AuthWeave-Curator-Org-Id", "987654321098765432")
                    .header("X-AuthWeave-Authenticated-At", Instant.now().toString()))
                    .andExpect(status().isOk()).andReturn());
        assertEquals(receipt, history.get("items").get(0));
        var invalidHistory = (ObjectNode) history.deepCopy(); invalidHistory.put("approved", true);
        sample("manual-source-review-history-no-approval", "catalog-fact-review-page", false, invalidHistory);
        invalidHistory = (ObjectNode) history.deepCopy();
        ((ObjectNode) invalidHistory.get("items").get(0)).put("actorSubject", "private");
        sample("manual-source-review-history-no-actor", "catalog-fact-review-page", false, invalidHistory);
        invalidHistory = (ObjectNode) history.deepCopy(); invalidHistory.put("afterReviewNumber", -1);
        sample("manual-source-review-history-safe-cursor", "catalog-fact-review-page", false, invalidHistory);
        var summary = versionedSample("manual-source-review-summary", "catalog-fact-review-summary-page",
                mvc.perform(get("/api/v1/catalog-change-proposals/" + id + "/revisions/0/fact-reviews/summary")
                    .header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                    .header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081")
                    .header("X-AuthWeave-Oidc-Subject", "synthetic-curator")
                    .header("X-AuthWeave-Curator-Role", "catalog_curator")
                    .header("X-AuthWeave-Curator-Project-Id", "123456789012345678")
                    .header("X-AuthWeave-Curator-Org-Id", "987654321098765432")
                    .header("X-AuthWeave-Authenticated-At", Instant.now().toString()))
                    .andExpect(status().isOk()).andReturn());
        assertEquals(8, summary.get("counts").get("noObservation").asInt());
        for (var item : summary.get("items")) {
            if (item.get("factPath").asText().equals("facts.OIDC")) assertEquals(receipt, item.get("latestObservation"));
        }
        var invalidSummary = (ObjectNode) summary.deepCopy(); invalidSummary.put("approvalGranted", true);
        sample("manual-source-review-summary-no-approval", "catalog-fact-review-summary-page", false, invalidSummary);
        invalidSummary = (ObjectNode) summary.deepCopy(); invalidSummary.put("reviewThroughNumber", -1);
        sample("manual-source-review-summary-safe-number", "catalog-fact-review-summary-page", false, invalidSummary);
        invalidSummary = (ObjectNode) summary.deepCopy(); ((ObjectNode) invalidSummary.get("counts")).put("noObservation", "8");
        sample("manual-source-review-summary-count-types", "catalog-fact-review-summary-page", false, invalidSummary);
        var invalid = input.deepCopy(); invalid.remove("confirmation");
        sample("manual-source-review-confirmation-required", "catalog-fact-review-request", false, invalid);
        invalid = input.deepCopy(); invalid.put("actorSubject", "forged");
        sample("manual-source-review-server-actor-only", "catalog-fact-review-request", false, invalid);
        for (String flag : java.util.List.of("sourceVerificationPerformed", "approvalGranted", "catalogWritesPerformed", "factTrustChanged")) {
            invalid = (ObjectNode) receipt.deepCopy(); invalid.put(flag, true);
            sample("manual-source-review-no-" + flag, "catalog-fact-review", false, invalid);
        }
        invalid = (ObjectNode) receipt.deepCopy(); invalid.put("reviewNumber", 0);
        sample("manual-source-review-positive-number", "catalog-fact-review", false, invalid);
    }

    private io.authweave.core.catalog.proposal.LocalCatalogProposalWriter.SaveResult storeProposal(ObjectNode input, Long expectedVersion) {
        return new org.springframework.transaction.support.TransactionTemplate(proposalTransactions).execute(status ->
                new io.authweave.core.catalog.proposal.LocalCatalogProposalWriter(proposalDsl, mapper, proposalPreviews, proposals)
                        .save(mapper.treeToValue(input, io.authweave.core.catalog.draft.CatalogChangePreviewRequest.class), expectedVersion));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "9007199254740992", "1.5", "bad-version"})
    void storedImpactRejectsInvalidVersionPaths(String version) throws Exception {
        response("impact-invalid-version", mvc.perform(get("/api/v1/catalog-change-proposals/" + UUID.randomUUID() + "/revisions/" + version + "/impact-preview"))
                .andExpect(status().isBadRequest()).andReturn());
        response("scenario-invalid-version", mvc.perform(get("/api/v1/catalog-change-proposals/" + UUID.randomUUID() + "/revisions/" + version + "/scenario-impact-preview"))
                .andExpect(status().isBadRequest()).andReturn());
    }

    @Test
    void impactCannotAcceptCallerDefinedProbesOrPretendAnIncompatibleStoredInputWasReplayed() throws Exception {
        var input = proposalRequest(); input.putArray("caseDefinitions");
        response("impact-forged-probes", mvc.perform(post("/api/v1/catalog-change-proposals/impact-preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest()).andReturn());
        response("scenario-forged-probes", mvc.perform(post("/api/v1/catalog-change-proposals/scenario-impact-preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest()).andReturn());
        input.remove("caseDefinitions"); input.put("proposalId", UUID.randomUUID().toString());
        var stored = storeProposal(input, null).proposal();
        for (String scenario : List.of("digest", "format", "shape", "identity")) {
            var request = stored.request();
            if (scenario.equals("shape")) ((ObjectNode) request).remove("base");
            if (scenario.equals("identity")) ((ObjectNode) request).put("proposalId", UUID.randomUUID().toString());
            var unavailable = new io.authweave.core.catalog.proposal.CatalogProposalSnapshot(stored.proposalId(), stored.version(), stored.state(),
                    scenario.equals("format") ? 99 : 1, scenario.equals("digest") ? "0".repeat(64) : stored.proposalSha256(), stored.recordedAt(), request, stored.preview());
            var fakeRepository = org.mockito.Mockito.mock(io.authweave.core.catalog.proposal.CatalogProposalRepository.class);
            org.mockito.Mockito.when(fakeRepository.revision(stored.proposalId(), 0)).thenReturn(unavailable);
            var standalone = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new io.authweave.core.catalog.impact.CatalogImpactController(new io.authweave.core.catalog.impact.CatalogImpactService(proposalPreviews), fakeRepository, mapper))
                    .setControllerAdvice(new AssessmentProblemDetailsHandler()).build();
            response("impact-replay-unavailable-" + scenario, standalone.perform(get("/api/v1/catalog-change-proposals/" + stored.proposalId() + "/revisions/0/impact-preview"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("catalog-proposal-replay-unavailable")).andReturn());
            var scenarioService = new io.authweave.core.catalog.impact.CatalogScenarioImpactService(proposalPreviews, new io.authweave.core.catalog.impact.CatalogScenarioCases(mapper));
            var scenarioMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new io.authweave.core.catalog.impact.CatalogScenarioImpactController(scenarioService,
                            new io.authweave.core.catalog.impact.CatalogScenarioReplay(scenarioService, fakeRepository, mapper)))
                    .setControllerAdvice(new AssessmentProblemDetailsHandler()).build();
            response("scenario-replay-unavailable-" + scenario, scenarioMvc.perform(get("/api/v1/catalog-change-proposals/" + stored.proposalId() + "/revisions/0/scenario-impact-preview"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("catalog-proposal-replay-unavailable")).andReturn());
        }
        assertEquals(stored, proposals.current(stored.proposalId())); assertEquals(1, proposals.events(stored.proposalId(), null, 100).items().size());
    }

    @Test
    void readsStoredImpactReportsAndEventsWithoutReplayingOrEnablingHttpWrites() throws Exception {
        assertEquals(0, applicationContext.getBeansOfType(io.authweave.core.catalog.impact.LocalCatalogImpactWriter.class).size());
        assertEquals(0, applicationContext.getBeansOfType(io.authweave.core.catalog.impact.LocalCatalogImpactCommand.class).size());
        var input = proposalRequest(); input.put("proposalId", UUID.randomUUID().toString());
        var proposal = storeProposal(input, null).proposal();
        var first = storeImpact(UUID.randomUUID(), proposal.proposalId(), 0);
        var base = "/api/v1/catalog-change-proposals/" + proposal.proposalId() + "/revisions/0/impact-reports";
        var original = versionedSample("impact-report-original", "catalog-impact-report", mvc.perform(get(base + "/" + first.reportId())).andReturn());
        assertEquals(mapper.readTree(mapper.writeValueAsString(first)), original);
        assertEquals(false, original.at("/report/writesPerformed").asBoolean());
        var event = versionedSample("impact-report-event", "catalog-impact-report-event", mvc.perform(get(base + "/" + first.reportId() + "/event")).andReturn());
        assertEquals("catalog-impact.recorded", event.get("action").asText()); assertEquals("SERVICE", event.get("actorType").asText());
        assertEquals(original.get("reportSha256"), event.get("reportSha256"));
        var second = storeImpact(UUID.randomUUID(), proposal.proposalId(), 0);
        var page = versionedSample("impact-report-page", "catalog-impact-report-page", mvc.perform(get(base + "?limit=1")).andReturn());
        assertEquals(original, page.get("items").get(0)); assertEquals(first.reportNumber(), page.get("nextAfterReportNumber").asLong());
        var rest = versionedSample("impact-report-page-rest", "catalog-impact-report-page", mvc.perform(get(base + "?limit=1&afterReportNumber=" + first.reportNumber())).andReturn());
        assertEquals(second.reportId().toString(), rest.at("/items/0/reportId").asText()); assertEquals(mapper.nullNode(), rest.get("nextAfterReportNumber"));
        var empty = versionedSample("impact-report-page-empty", "catalog-impact-report-page", mvc.perform(get(base + "?afterReportNumber=" + second.reportNumber())).andReturn());
        assertEquals(0, empty.get("items").size());
        input.put("rationale", "A later fictional proposal revision"); storeProposal(input, 0L);
        assertEquals(original, versionedSample("impact-report-unchanged-after-revision", "catalog-impact-report", mvc.perform(get(base + "/" + first.reportId())).andReturn()));
        assertEquals(event, versionedSample("impact-event-unchanged-after-revision", "catalog-impact-report-event", mvc.perform(get(base + "/" + first.reportId() + "/event")).andReturn()));
        var newBase = base.replace("/revisions/0/", "/revisions/1/");
        assertEquals(0, versionedSample("impact-report-new-revision-empty", "catalog-impact-report-page", mvc.perform(get(newBase)).andReturn()).get("items").size());
        for (String path : List.of(newBase + "/" + first.reportId(), newBase + "/" + first.reportId() + "/event",
                base.replace(proposal.proposalId().toString(), UUID.randomUUID().toString()) + "/" + first.reportId(), base + "/" + UUID.randomUUID())) {
            response("impact-report-wrong-scope", mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("catalog-impact-report-not-found")).andReturn());
        }
        response("impact-report-missing-revision", mvc.perform(get(base.replace("/revisions/0/", "/revisions/2/"))).andExpect(status().isNotFound()).andReturn());
        for (String field : List.of("reportSchemaVersion", "reportNumber", "proposalVersion")) {
            var bad = (ObjectNode) original.deepCopy(); bad.put(field, -1); sample("impact-report-invalid-" + field, "catalog-impact-report", false, bad);
        }
        var unbound = (ObjectNode) original.deepCopy(); ((ObjectNode) unbound.get("report")).put("storedRequestDigestVerified", false);
        sample("impact-report-unbound", "catalog-impact-report", false, unbound);
        var badPage = (ObjectNode) page.deepCopy(); badPage.put("nextAfterReportNumber", -1); sample("impact-report-page-invalid", "catalog-impact-report-page", false, badPage);
        var fakeActor = (ObjectNode) event.deepCopy(); fakeActor.put("actorType", "CURATOR"); sample("impact-report-event-not-authorized", "catalog-impact-report-event", false, fakeActor);
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(put(base + "/" + first.reportId()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + first.reportId())).andExpect(status().isMethodNotAllowed());
        assertEquals(2, proposals.events(proposal.proposalId(), null, 100).items().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"afterReportNumber=-1", "afterReportNumber=9007199254740992", "afterReportNumber=1.5", "afterReportNumber=bad", "limit=0", "limit=101"})
    void storedImpactHistoryRejectsBadPagination(String query) throws Exception {
        response("impact-report-invalid-page", mvc.perform(get("/api/v1/catalog-change-proposals/" + UUID.randomUUID() + "/revisions/0/impact-reports?" + query))
                .andExpect(status().isBadRequest()).andReturn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "9007199254740992", "1.5", "bad"})
    void storedImpactHistoryRejectsBadRevisionPaths(String version) throws Exception {
        var base = "/api/v1/catalog-change-proposals/" + UUID.randomUUID() + "/revisions/" + version + "/impact-reports";
        for (var suffix : List.of("", "/" + UUID.randomUUID(), "/" + UUID.randomUUID() + "/event")) {
            response("impact-report-invalid-version", mvc.perform(get(base + suffix)).andExpect(status().isBadRequest()).andReturn());
        }
    }

    private io.authweave.core.catalog.impact.CatalogImpactReport storeImpact(UUID id, UUID proposalId, long version) {
        return new org.springframework.transaction.support.TransactionTemplate(proposalTransactions).execute(s ->
                new io.authweave.core.catalog.impact.LocalCatalogImpactWriter(proposalDsl, mapper,
                        applicationContext.getBean(io.authweave.core.catalog.impact.CatalogScenarioReplay.class),
                        applicationContext.getBean(io.authweave.core.catalog.impact.CatalogImpactReportRepository.class)).save(id, proposalId, version).report());
    }

    private JsonNode scenarioImpact(String name, ObjectNode input) throws Exception {
        return versionedSample(name, "catalog-scenario-impact", mvc.perform(post("/api/v1/catalog-change-proposals/scenario-impact-preview")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode impactPreview(String name, ObjectNode input) throws Exception {
        return versionedSample(name, "catalog-impact-preview", mvc.perform(post("/api/v1/catalog-change-proposals/impact-preview")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode previewProposal(String name, ObjectNode input) throws Exception {
        return versionedSample(name, "catalog-change-preview", mvc.perform(post("/api/v1/catalog-change-proposals/preview")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andReturn());
    }

    private ObjectNode proposalRequest() throws Exception {
        return (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json").toFile());
    }

    private ObjectNode catalogDraft() throws Exception {
        return (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/provider-catalog-draft.valid.json").toFile());
    }

    private JsonNode saveV5(String name, String path, ObjectNode request) throws Exception {
        sample(name + "-request", "update-assessment-profile-request.v5", true, request.deepCopy());
        return versionedSample(name, "assessment-response.v5", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode v5History(String name, String path) throws Exception {
        return versionedSample(name, "assessment-revision-page.v5", mvc.perform(get(path + "/revisions")).andExpect(status().isOk()).andReturn());
    }

    private JsonNode usagePreflight(String name, String path) throws Exception {
        return versionedSample(name, "usage-planning-preflight", mvc.perform(get(path + "/usage-planning-preflight"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pricingEvaluated").value(false))
                .andExpect(jsonPath("$.recommendationReady").value(false)).andReturn());
    }

    private JsonNode saveV4(String name, String path, ObjectNode request) throws Exception {
        sample(name + "-request", "update-assessment-profile-request.v4", true, request.deepCopy());
        return versionedSample(name, "assessment-response.v4", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode v4History(String name, String path) throws Exception {
        return versionedSample(name, "assessment-revision-page.v4", mvc.perform(get(path + "/revisions")).andExpect(status().isOk()).andReturn());
    }

    private JsonNode versionedSample(String name, String schema, MvcResult result) throws Exception {
        assertEquals(true, result.getResponse().getStatus() < 400);
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample(name, schema, true, payload);
        return payload;
    }

    private JsonNode v3History(String name, String path) throws Exception {
        return versionedSample(name, "assessment-revision-page.v3", mvc.perform(get(path + "/revisions")).andExpect(status().isOk()).andReturn());
    }

    private ObjectNode residencyRequest(String criticality) throws Exception {
        var update = request();
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            update.set("profile", mapper.readTree(input).get(0).get("profile"));
        }
        var security = (ObjectNode) update.at("/profile/security");
        security.put("dataResidency", criticality);
        var details = security.putObject("dataResidencyDetails");
        details.putArray("allowedCountries");
        details.putArray("dataCategories");
        return update;
    }

    private JsonNode residencyPreflight(String name, String path) throws Exception {
        var result = mvc.perform(get(path + "/eligibility-preflight")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationReady").value(false)).andReturn();
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample(name, "eligibility-preflight.v2", true, payload);
        return payload;
    }

    private JsonNode v2History(String name, String path) throws Exception {
        var result = mvc.perform(get(path + "/revisions")).andExpect(status().isOk()).andReturn();
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample(name, "assessment-revision-page.v2", true, payload);
        return payload;
    }

    private JsonNode v2Response(String name, MvcResult result) throws Exception {
        assertEquals(true, result.getResponse().getStatus() < 400);
        var payload = mapper.readTree(result.getResponse().getContentAsString());
        sample(name, "assessment-response.v2", true, payload);
        return payload;
    }

    @ParameterizedTest
    @ValueSource(strings = {"B2B_SAAS", "PUBLIC_SECTOR_PORTAL", "INTERNAL_WORKFORCE"})
    void contextIndexProjectsSavedNavigationDataWithoutChangingMetadataHistoryOrProfiles(String type) throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var indexPath = "/api/v6/workspaces/" + assessment.workspaceId().value() + "/assessments/context-index";
        var initial = versionedSample("context-index-initial", "assessment-context-list-page", mvc.perform(get(indexPath)).andExpect(status().isOk()).andReturn());
        assertEquals("UNKNOWN", initial.at("/items/0/context/applicationType").asText());
        assertEquals(0, initial.at("/items/0/context/clients").size());
        assertEquals(0, initial.at("/items/0/context/userPopulations").size());
        var profile = versionedSample("context-index-profile", "assessment-response.v6", mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", profile.get("profile").deepCopy());
        var application = (ObjectNode) update.at("/profile/application"); application.put("type", type);
        application.putArray("clients").add("BROWSER").add("NATIVE_MOBILE");
        var populations = ((ObjectNode) update.at("/profile/audience")).putArray("populations");
        populations.add(type.equals("PUBLIC_SECTOR_PORTAL") ? "CITIZENS" : type.equals("INTERNAL_WORKFORCE") ? "EMPLOYEES" : "PARTNERS");
        var saved = saveV6("context-index-save", path, update);
        var history = versionedSample("context-index-history-before", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn());
        var events = historyResponse("context-index-events-before", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var metadataPath = indexPath.replace("/context-index", "");
        var metadata = versionedSample("context-index-original-metadata", "assessment-list-page", mvc.perform(get(metadataPath)).andReturn());
        var page = versionedSample("context-index-saved", "assessment-context-list-page", mvc.perform(get(indexPath)).andExpect(status().isOk()).andReturn());
        assertEquals(saved.get("id"), page.at("/items/0/id")); assertEquals(saved.get("version"), page.at("/items/0/version"));
        assertEquals(type, page.at("/items/0/context/applicationType").asText());
        assertEquals(populations, page.at("/items/0/context/userPopulations"));
        assertEquals(application.get("clients"), page.at("/items/0/context/clients"));
        var projected = (ObjectNode) page.at("/items/0").deepCopy(); projected.remove("context");
        assertEquals(metadata.at("/items/0"), projected);
        assertEquals(saved, versionedSample("context-index-profile-unchanged", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
        assertEquals(history, versionedSample("context-index-history-unchanged", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn()));
        assertEquals(events, historyResponse("context-index-events-unchanged", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
        var rawProfile = (ObjectNode) page.deepCopy(); ((ObjectNode) rawProfile.at("/items/0")).set("profile", saved.get("profile"));
        sample("context-index-no-raw-profile", "assessment-context-list-page", false, rawProfile);
        var authority = (ObjectNode) page.deepCopy(); ((ObjectNode) authority.at("/items/0/context")).put("recommendationReady", true);
        sample("context-index-no-authority", "assessment-context-list-page", false, authority);
        var duplicated = (ObjectNode) page.deepCopy(); ((ObjectNode) duplicated.at("/items/0/context")).putArray("clients").add("BROWSER").add("BROWSER");
        sample("context-index-no-duplicate-clients", "assessment-context-list-page", false, duplicated);
        mvc.perform(get(indexPath + "?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get(indexPath + "?limit=51")).andExpect(status().isBadRequest());
        mvc.perform(get(indexPath + "?beforeId=" + UUID.randomUUID())).andExpect(status().isNotFound());
        var corrupt = versionedSample("context-index-second-draft", "assessment-response.v6", mvc.perform(post(metadataPath)).andExpect(status().isCreated()).andReturn());
        proposalDsl.update(io.authweave.core.generated.jooq.tables.Assessments.ASSESSMENTS)
                .set(io.authweave.core.generated.jooq.tables.Assessments.ASSESSMENTS.PROFILE, org.jooq.JSONB.valueOf("{}"))
                .where(io.authweave.core.generated.jooq.tables.Assessments.ASSESSMENTS.ID.eq(UUID.fromString(corrupt.get("id").asText()))).execute();
        var mixed = versionedSample("context-index-isolated-unreadable", "assessment-context-list-page", mvc.perform(get(indexPath)).andExpect(status().isOk()).andReturn());
        assertEquals(2, mixed.get("items").size());
        for (var item : mixed.get("items")) {
            if (item.get("id").equals(corrupt.get("id"))) org.junit.jupiter.api.Assertions.assertTrue(item.get("context").isNull());
            else assertEquals(type, item.at("/context/applicationType").asText());
        }
        var first = versionedSample("context-index-first-page", "assessment-context-list-page", mvc.perform(get(indexPath + "?limit=1")).andReturn());
        assertEquals(1, first.get("items").size());
        var older = versionedSample("context-index-older-page", "assessment-context-list-page", mvc.perform(get(indexPath + "?limit=1&beforeId=" + first.get("nextBeforeId").asText())).andReturn());
        assertEquals(1, older.get("items").size()); org.junit.jupiter.api.Assertions.assertTrue(older.get("nextBeforeId").isNull());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.at("/items/0/id"), older.at("/items/0/id"));
    }

    @Test
    void v6AuditScopeUsesLosslessStorageAtomicHistoryAndPreventsOlderApiDataLoss() throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("v6-legacy-projection", "assessment-response.v6", mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
        assertEquals(6, initial.get("profileSchemaVersion").asInt());
        assertEquals(0, initial.at("/profile/security/auditabilityRequirements/selectedCriteria").size());
        org.junit.jupiter.api.Assertions.assertTrue(initial.at("/profile/security/auditabilityRequirements/minimumRetentionDays").isNull());
        assertEquals(assessment.created(), response("v6-projection-does-not-migrate", mvc.perform(get(assessment.path())).andReturn()));
        var olderProfiles = new ArrayList<JsonNode>();
        for (int api = 1; api <= 5; api++) olderProfiles.add(mapper.readTree(mvc.perform(get(path.replace("/api/v6/", "/api/v" + api + "/")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("profile"));
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile").deepCopy());
        assertEquals(initial, saveV6("v6-unrecorded-no-op", path, update));
        var security = (ObjectNode) update.at("/profile/security"); security.put("auditability", "REQUIRED");
        var requirements = (ObjectNode) security.get("auditabilityRequirements");
        requirements.putArray("selectedCriteria").add("AUDIT_LOG_RETENTION").add("AUDIT_LOG_EXPORT");
        requirements.put("minimumRetentionDays", 30);
        var saved = saveV6("v6-auditability-saved", path, update);
        assertEquals(1, saved.get("version").asInt());
        var contextIndex = versionedSample("v6-auditability-context-index", "assessment-context-list-page", mvc.perform(get(
                "/api/v6/workspaces/" + assessment.workspaceId().value() + "/assessments/context-index")).andExpect(status().isOk()).andReturn());
        assertEquals(saved.get("version"), contextIndex.at("/items/0/version"));
        assertEquals("UNKNOWN", contextIndex.at("/items/0/context/applicationType").asText());
        assertEquals(saved, versionedSample("v6-auditability-loaded", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
        update.put("expectedVersion", 1); assertEquals(saved, saveV6("v6-recorded-no-op", path, update));
        var history = versionedSample("v6-mixed-exact-history", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn());
        assertEquals(2, history.get("items").size()); assertEquals(1, history.at("/items/0/profileSchemaVersion").asInt());
        assertEquals(6, history.at("/items/1/profileSchemaVersion").asInt());
        assertEquals(assessment.created().get("profile"), history.at("/items/0/profile"));
        assertEquals(saved.get("profile"), history.at("/items/1/profile"));
        var events = historyResponse("v6-minimal-security-events", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        assertEquals(2, events.get("items").size()); assertEquals(List.of("security"), mapper.convertValue(events.at("/items/1/changedSections"), List.class));
        for (int api = 1; api <= 5; api++) {
            var oldPath = path.replace("/api/v6/", "/api/v" + api + "/");
            response("v6-older-read-denied", mvc.perform(get(oldPath)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            response("v6-older-history-denied", mvc.perform(get(oldPath + "/revisions")).andExpect(status().isConflict()).andReturn());
            var oldRequest = mapper.createObjectNode().put("expectedVersion", 1); oldRequest.set("profile", olderProfiles.get(api - 1));
            response("v6-older-write-denied", mvc.perform(put(oldPath + "/profile").contentType(MediaType.APPLICATION_JSON).content(oldRequest.toString()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("profile-upgrade-required")).andReturn());
            versionedSample("v6-older-supported-history-page", api == 1 ? "assessment-revision-page" : "assessment-revision-page.v" + api,
                    mvc.perform(get(oldPath + "/revisions?limit=1")).andExpect(status().isOk()).andReturn());
        }
        update.put("expectedVersion", 0);
        response("v6-stale-write", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON).content(update.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("assessment-version-conflict")).andReturn());
        assertEquals(history, versionedSample("v6-denied-writes-preserve-history", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn()));
        assertEquals(events, historyResponse("v6-denied-writes-preserve-events", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
        update.put("expectedVersion", 1); requirements.putArray("selectedCriteria"); requirements.putNull("minimumRetentionDays");
        var cleared = saveV6("v6-explicit-clear", path, update); assertEquals(2, cleared.get("version").asInt());
        versionedSample("v5-readable-after-audit-scope-clear", "assessment-response.v5", mvc.perform(get(path.replace("/api/v6/", "/api/v5/"))).andExpect(status().isOk()).andReturn());
        var after = versionedSample("v6-history-preserved-after-clear", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn());
        assertEquals(history.at("/items/0"), after.at("/items/0")); assertEquals(history.at("/items/1"), after.at("/items/1"));
        assertEquals(1, after.at("/items/2/profileSchemaVersion").asInt());
        response("v5-history-still-requires-v6", mvc.perform(get(path.replace("/api/v6/", "/api/v5/") + "/revisions")).andExpect(status().isConflict()).andReturn());
        var mislabeled = (ObjectNode) after.deepCopy(); ((ObjectNode) mislabeled.at("/items/1")).put("profileSchemaVersion", 5);
        sample("v6-mislabeled-history", "assessment-revision-page.v6", false, mislabeled);
        var forged = (ObjectNode) saved.deepCopy(); forged.put("configurationVerified", true);
        sample("v6-no-provider-verification", "assessment-response.v6", false, forged);
        versionedSample("v6-create", "assessment-response.v6", mvc.perform(post("/api/v6/workspaces/" + assessment.workspaceId().value() + "/assessments"))
                .andExpect(status().isCreated()).andReturn());
        versionedSample("v6-list", "assessment-list-page", mvc.perform(get("/api/v6/workspaces/" + assessment.workspaceId().value() + "/assessments")).andExpect(status().isOk()).andReturn());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> auditScopeWireMatrix() {
        return java.util.Arrays.stream(io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.values())
                .flatMap(criterion -> java.util.Arrays.stream(io.authweave.core.assessment.domain.profile.RequirementCriticality.values())
                        .map(criticality -> org.junit.jupiter.params.provider.Arguments.of(criterion.name(), criticality.name())));
    }

    @Test
    void v6StoresAllSixCriteriaAndReorderingASelectionIsANoop() throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("v6-all-criteria-initial", "assessment-response.v6", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile").deepCopy());
        ((ObjectNode) update.at("/profile/security")).put("auditability", "REQUIRED");
        var requirements = (ObjectNode) update.at("/profile/security/auditabilityRequirements"); requirements.put("minimumRetentionDays", 1);
        var ids = requirements.putArray("selectedCriteria");
        for (var criterion : io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.values()) ids.add(criterion.name());
        var saved = saveV6("v6-all-criteria-saved", path, update);
        assertEquals(6, saved.at("/profile/security/auditabilityRequirements/selectedCriteria").size());
        update.put("expectedVersion", 1); ids.removeAll();
        java.util.Arrays.stream(io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.values())
                .sorted(java.util.Comparator.reverseOrder()).forEach(c -> ids.add(c.name()));
        assertEquals(saved, saveV6("v6-selection-order-no-op", path, update));
        assertEquals(2, versionedSample("v6-selection-order-no-history", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn()).get("items").size());
        assertEquals(2, historyResponse("v6-selection-order-no-events", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()).get("items").size());
    }

    @Test
    void existingPersonalPreviewsRemainReadOnlyAndPartialForRecordedV6AuditScope() throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("v6-preview-initial", "assessment-response.v6", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile"));
        var security = (ObjectNode) update.at("/profile/security"); security.put("auditability", "REQUIRED");
        var requirements = (ObjectNode) security.get("auditabilityRequirements");
        requirements.putArray("selectedCriteria").add("AUTHENTICATION_FAILURE_EVENTS").add("AUDIT_LOG_RETENTION");
        requirements.put("minimumRetentionDays", 180);
        var saved = saveV6("v6-preview-recorded", path, update);
        var history = versionedSample("v6-preview-history-before", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn());
        var events = historyResponse("v6-preview-events-before", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var comparison = versionedSample("v6-existing-comparison", "synthetic-comparison",
                mvc.perform(get(path.replace("/api/v6/", "/api/v5/") + "/comparison-preflight"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.recommendationReady").value(false))
                        .andExpect(jsonPath("$.rankingPerformed").value(false)).andReturn());
        org.junit.jupiter.api.Assertions.assertTrue(comparison.get("deferredPaths").toString().contains("security.auditability"));
        var patterns = versionedSample("v6-existing-patterns", "architecture-pattern-preflight",
                mvc.perform(get(assessment.path() + "/architecture-pattern-preflight"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.recommendationReady").value(false)).andReturn());
        org.junit.jupiter.api.Assertions.assertTrue(patterns.get("deferredPaths").toString().contains("security.auditability"));
        assertEquals(1, usagePreflight("v6-existing-usage", path.replace("/api/v6/", "/api/v5/")).get("assessmentVersion").asInt());
        assertEquals(saved, versionedSample("v6-preview-unchanged", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
        assertEquals(history, versionedSample("v6-preview-history-unchanged", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn()));
        assertEquals(events, historyResponse("v6-preview-events-unchanged", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(io.authweave.core.assessment.domain.profile.RequirementCriticality.class)
    void v6CombinedConstraintsBindAuditabilityAndWithholdScoresWithoutChangingLegacyOrState(
            io.authweave.core.assessment.domain.profile.RequirementCriticality criticality) throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("combined-initial", "assessment-response.v6", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile"));
        // A fully explicit checked scope lets auditability alone change the complete option's verdict.
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            var seed = mapper.readTree(input).get(0).get("profile");
            ((ObjectNode) update.get("profile")).set("application", seed.get("application"));
            ((ObjectNode) update.get("profile")).set("audience", seed.get("audience"));
        }
        ((ObjectNode) update.at("/profile/protocols")).put("socialLogin", "PREFERRED");
        ((ObjectNode) update.at("/profile/protocols/federation")).put("SAML", "PREFERRED");
        ((ObjectNode) update.at("/profile/protocols/federation")).put("OIDC", "NOT_REQUIRED");
        for (String field : List.of("oauth2ProtectedApis", "enterpriseSingleSignOn"))
            ((ObjectNode) update.at("/profile/protocols")).put(field, "NOT_REQUIRED");
        for (String field : List.of("scim", "justInTimeProvisioning", "groupSynchronization"))
            ((ObjectNode) update.at("/profile/provisioning")).put(field, "NOT_REQUIRED");
        var security = (ObjectNode) update.at("/profile/security"); security.put("auditability", criticality.name());
        for (String field : List.of("multiFactorAuthentication", "dataResidency")) security.put(field, "NOT_REQUIRED");
        security.put("complianceScopeStatus", "NONE_IDENTIFIED");
        for (String field : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication"))
            ((ObjectNode) security.get("authenticationControls")).put(field, "NOT_REQUIRED");
        var requirements = (ObjectNode) security.get("auditabilityRequirements");
        var selected = requirements.putArray("selectedCriteria");
        for (var criterion : io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.values()) selected.add(criterion.name());
        requirements.put("minimumRetentionDays", 30);
        var saved = saveV6("combined-saved", path, update);
        var history = versionedSample("combined-history-before", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn());
        var events = historyResponse("combined-events-before", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var legacy = versionedSample("combined-legacy-hard", "hard-constraint-preflight",
                mvc.perform(get(path.replace("/api/v6/", "/api/v5/") + "/hard-constraint-preflight")).andExpect(status().isOk()).andReturn());
        var hard = versionedSample("combined-hard-" + criticality, "hard-constraint-preflight.v2",
                mvc.perform(get(path + "/hard-constraint-preflight")).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.policyVersion").value("hard-constraint-preflight-2")).andReturn());
        var audit = versionedSample("combined-separate-audit", "auditability-capability-preflight",
                mvc.perform(get(path + "/auditability-capability-preflight")).andExpect(status().isOk()).andReturn());
        assertEquals(audit, hard.get("auditability"));
        assertEquals("PASSES_CHECKED_REQUIREMENTS", legacy.at("/candidates/0/verdict").asText());
        assertEquals(criticality == io.authweave.core.assessment.domain.profile.RequirementCriticality.UNKNOWN
                || criticality == io.authweave.core.assessment.domain.profile.RequirementCriticality.FORBIDDEN
                ? "UNRESOLVED" : "PASSES_CHECKED_REQUIREMENTS", hard.at("/candidates/0/verdict").asText());
        var comparison = versionedSample("combined-comparison-" + criticality, "synthetic-comparison.v2",
                mvc.perform(get(path + "/comparison-preflight")).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.policyVersion").value("synthetic-comparison-2")).andReturn());
        assertEquals(audit, comparison.get("auditability"));
        for (int i = 0; i < hard.get("candidates").size(); i++) {
            assertEquals(hard.get("candidates").get(i).get("verdict"), comparison.get("candidates").get(i).get("hardVerdict"));
            assertEquals(hard.get("candidates").get(i).get("exclusionReasons"), comparison.get("candidates").get(i).get("exclusionReasons"));
            assertEquals(hard.get("candidates").get(i).get("informationGaps"), comparison.get("candidates").get(i).get("informationGaps"));
        }
        if (criticality == io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED) {
            assertEquals("EXCLUDED", hard.at("/candidates/1/verdict").asText());
            org.junit.jupiter.api.Assertions.assertTrue(hard.at("/candidates/1/exclusionReasons").toString().contains("RETENTION_BELOW_MINIMUM"));
            org.junit.jupiter.api.Assertions.assertTrue(hard.at("/candidates/1/informationGaps").toString().contains("EVIDENCE_MISSING"));
        }
        String weights = "{\"SAML\":40,\"SOCIAL_LOGIN\":60}";
        var weighted = versionedSample("combined-weighted-" + criticality, "weighted-comparison-preview.v2",
                mvc.perform(post(path + "/weighted-comparison-preview").contentType(MediaType.APPLICATION_JSON).content("{\"weights\":" + weights + "}"))
                        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn());
        assertEquals(comparison, weighted.get("comparison"));
        var sensitivity = versionedSample("combined-sensitivity-" + criticality, "weight-sensitivity-preview.v2",
                mvc.perform(post(path + "/weight-sensitivity-preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baselineWeights\":" + weights + ",\"alternativeWeights\":{\"SAML\":70,\"SOCIAL_LOGIN\":30}}"))
                        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn());
        assertEquals(comparison, sensitivity.get("comparison"));
        assertEquals(legacy, versionedSample("combined-legacy-hard-unchanged", "hard-constraint-preflight",
                mvc.perform(get(path.replace("/api/v6/", "/api/v5/") + "/hard-constraint-preflight")).andReturn()));
        assertEquals(hard, versionedSample("combined-hard-repeat", "hard-constraint-preflight.v2", mvc.perform(get(path + "/hard-constraint-preflight")).andReturn()));
        assertEquals(saved, versionedSample("combined-profile-unchanged", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
        assertEquals(history, versionedSample("combined-history-unchanged", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn()));
        assertEquals(events, historyResponse("combined-events-unchanged", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
        for (String endpoint : List.of("hard-constraint-preflight", "comparison-preflight")) {
            var schema = endpoint.equals("hard-constraint-preflight") ? "hard-constraint-preflight.v2" : "synthetic-comparison.v2";
            var value = endpoint.equals("hard-constraint-preflight") ? hard : comparison;
            var missing = (ObjectNode) value.deepCopy(); missing.remove("auditability"); sample("combined-missing-audit", schema, false, missing);
            var forged = (ObjectNode) value.deepCopy(); forged.put("recommendationReady", true); sample("combined-no-recommendation", schema, false, forged);
            response("combined-foreign-workspace", mvc.perform(get(path.replace(assessment.workspaceId().value().toString(), UUID.randomUUID().toString()) + "/" + endpoint))
                    .andExpect(status().isNotFound()).andReturn());
        }
        response("combined-invalid-weights", mvc.perform(post(path + "/weighted-comparison-preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"weights\":{\"SAML\":1}}" )).andExpect(status().isBadRequest()).andReturn());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(io.authweave.core.assessment.domain.profile.RequirementCriticality.class)
    void scopedAuditabilityPreviewBindsSavedInputsEvidenceAndVersionWithoutWrites(
            io.authweave.core.assessment.domain.profile.RequirementCriticality criticality) throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var endpoint = path + "/auditability-capability-preflight";
        var legacy = versionedSample("audit-preview-legacy", "auditability-capability-preflight",
                mvc.perform(get(endpoint)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.candidates[0].analysis.status").value("NEEDS_INFORMATION")).andReturn());
        assertEquals(0, legacy.get("assessmentVersion").asInt());
        assertEquals(assessment.created(), response("audit-preview-legacy-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        var initial = versionedSample("audit-preview-v6", "assessment-response.v6", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile"));
        var security = (ObjectNode) update.at("/profile/security"); security.put("auditability", criticality.name());
        var requirements = (ObjectNode) security.get("auditabilityRequirements");
        var selected = requirements.putArray("selectedCriteria");
        for (var criterion : io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.values()) selected.add(criterion.name());
        requirements.put("minimumRetentionDays", 30);
        var saved = saveV6("audit-preview-saved-inputs", path, update);
        var history = versionedSample("audit-preview-history-before", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn());
        var events = historyResponse("audit-preview-events-before", "events", mvc.perform(get(assessment.path() + "/events")).andReturn());
        var preview = versionedSample("audit-preview-" + criticality, "auditability-capability-preflight",
                mvc.perform(get(endpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.sourceVerificationPerformed").value(false))
                        .andExpect(jsonPath("$.recommendationReady").value(false)).andReturn());
        assertEquals(assessment.workspaceId().value().toString(), preview.get("workspaceId").asText());
        assertEquals(assessment.id().value().toString(), preview.get("assessmentId").asText());
        assertEquals(1, preview.get("assessmentVersion").asInt()); assertEquals(requirements, preview.get("requirements"));
        assertEquals(criticality.name(), preview.get("criticality").asText());
        assertEquals(3, preview.get("candidates").size());
        if (criticality == io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED) {
            assertEquals("MATCHES_CHECKED_REQUIREMENTS", preview.at("/candidates/0/analysis/status").asText());
            assertEquals("DOES_NOT_MATCH", preview.at("/candidates/1/analysis/status").asText());
            assertEquals("EVIDENCE_MISSING", preview.at("/candidates/1/analysis/checks/3/reasonCode").asText());
            assertEquals("CAPABILITY_UNAVAILABLE", preview.at("/candidates/1/analysis/checks/4/reasonCode").asText());
            assertEquals("RETENTION_BELOW_MINIMUM", preview.at("/candidates/1/analysis/checks/5/reasonCode").asText());
            assertEquals("NEEDS_INFORMATION", preview.at("/candidates/2/analysis/status").asText());
        }
        for (var candidate : preview.get("candidates")) {
            assertEquals(requirements, candidate.at("/analysis/requirements"));
            for (String field : List.of("configurationVerified", "complianceVerified", "recommendationReady")) {
                assertFalse(candidate.at("/analysis/" + field).asBoolean());
                var forged = (ObjectNode) preview.deepCopy(); ((ObjectNode) forged.at("/candidates/0/analysis")).put(field, true);
                sample("audit-preview-no-" + field, "auditability-capability-preflight", false, forged);
            }
        }
        assertEquals(preview, versionedSample("audit-preview-repeat", "auditability-capability-preflight", mvc.perform(get(endpoint)).andReturn()));
        assertEquals(saved, versionedSample("audit-preview-profile-unchanged", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
        assertEquals(history, versionedSample("audit-preview-history-unchanged", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn()));
        assertEquals(events, historyResponse("audit-preview-events-unchanged", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
        response("audit-preview-foreign-workspace", mvc.perform(get(endpoint.replace(assessment.workspaceId().value().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound()).andReturn());
        response("audit-preview-absent-assessment", mvc.perform(get(endpoint.replace(assessment.id().value().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound()).andReturn());
    }

    @Test
    void auditabilityPreviewDoesNotInventScopeFromRequiredOrApplyUnselectedRetention() throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("audit-sparse-initial", "assessment-response.v6", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile"));
        ((ObjectNode) update.at("/profile/security")).put("auditability", "REQUIRED");
        saveV6("audit-sparse-required-without-scope", path, update);
        var unresolved = versionedSample("audit-required-scope-unknown", "auditability-capability-preflight",
                mvc.perform(get(path + "/auditability-capability-preflight")).andExpect(status().isOk()).andReturn());
        for (var candidate : unresolved.get("candidates")) {
            assertEquals("NEEDS_INFORMATION", candidate.at("/analysis/status").asText());
            for (var check : candidate.at("/analysis/checks")) assertEquals("AUDIT_SCOPE_UNKNOWN", check.get("reasonCode").asText());
        }
        update.put("expectedVersion", 1);
        ((ObjectNode) update.at("/profile/security/auditabilityRequirements")).putArray("selectedCriteria").add("AUDIT_LOG_EXPORT");
        var saved = saveV6("audit-sparse-export-only", path, update);
        var preview = versionedSample("audit-sparse-no-retention-inference", "auditability-capability-preflight",
                mvc.perform(get(path + "/auditability-capability-preflight")).andExpect(status().isOk()).andReturn());
        for (var candidate : preview.get("candidates")) {
            for (var check : candidate.at("/analysis/checks")) {
                if (!check.get("criterion").asText().equals("AUDIT_LOG_EXPORT")) {
                    assertEquals("CRITERION_NOT_SELECTED", check.get("reasonCode").asText());
                    assertEquals("NOT_APPLIED", check.get("outcome").asText());
                }
                org.junit.jupiter.api.Assertions.assertTrue(check.get("documentedMinimumRetentionDays").isNull());
            }
        }
        assertEquals(saved, versionedSample("audit-sparse-unchanged", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("auditScopeWireMatrix")
    void v6PreservesEachChosenCriterionAndAllCriticalitiesWithoutInventingProviderEvidence(String criterion, String criticality) throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("v6-matrix-initial", "assessment-response.v6", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile").deepCopy());
        ((ObjectNode) update.at("/profile/security")).put("auditability", criticality);
        var scope = (ObjectNode) update.at("/profile/security/auditabilityRequirements"); scope.putArray("selectedCriteria").add(criterion);
        if (criterion.equals("AUDIT_LOG_RETENTION")) scope.put("minimumRetentionDays", 36500);
        var saved = saveV6("v6-matrix-saved", path, update);
        assertEquals(criticality, saved.at("/profile/security/auditability").asText()); assertEquals(scope, saved.at("/profile/security/auditabilityRequirements"));
        assertEquals(saved, versionedSample("v6-matrix-reloaded", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
    }

    @ParameterizedTest @ValueSource(strings = {"missing-scope", "null-scope", "missing-criteria", "null-criteria", "scalar-criteria",
            "duplicate-criteria", "null-element", "unknown-criterion", "numeric-criterion", "padded-criterion", "missing-duration",
            "null-selected-duration", "zero-duration", "negative-duration", "oversized-duration", "fractional-duration", "string-duration",
            "boolean-duration", "unselected-duration", "unknown-field", "verification-flag", "helper-field", "missing-version", "unsafe-version"})
    void v6RejectsMalformedAuditabilityInputsWithoutStateHistoryOrEventChanges(String scenario) throws Exception {
        var assessment = create(); var path = assessment.path().replace("/api/v1/", "/api/v6/");
        var before = versionedSample("v6-invalid-before", "assessment-response.v6", mvc.perform(get(path)).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", before.get("profile").deepCopy());
        var security = (ObjectNode) update.at("/profile/security"); var scope = (ObjectNode) security.get("auditabilityRequirements");
        if (scenario.contains("duration") && !scenario.equals("unselected-duration") && !scenario.equals("missing-duration")) scope.putArray("selectedCriteria").add("AUDIT_LOG_RETENTION");
        switch (scenario) {
            case "missing-scope" -> security.remove("auditabilityRequirements");
            case "null-scope" -> security.putNull("auditabilityRequirements");
            case "missing-criteria" -> scope.remove("selectedCriteria");
            case "null-criteria" -> scope.putNull("selectedCriteria");
            case "scalar-criteria" -> scope.put("selectedCriteria", "AUDIT_LOG_EXPORT");
            case "duplicate-criteria" -> scope.putArray("selectedCriteria").add("AUDIT_LOG_EXPORT").add("AUDIT_LOG_EXPORT");
            case "null-element" -> scope.putArray("selectedCriteria").addNull();
            case "unknown-criterion" -> scope.putArray("selectedCriteria").add("COMPLIANT");
            case "numeric-criterion" -> scope.putArray("selectedCriteria").add(0);
            case "padded-criterion" -> scope.putArray("selectedCriteria").add(" AUDIT_LOG_EXPORT");
            case "missing-duration" -> scope.remove("minimumRetentionDays");
            case "null-selected-duration" -> scope.putNull("minimumRetentionDays");
            case "zero-duration" -> scope.put("minimumRetentionDays", 0);
            case "negative-duration" -> scope.put("minimumRetentionDays", -1);
            case "oversized-duration" -> scope.put("minimumRetentionDays", 36501);
            case "fractional-duration" -> scope.put("minimumRetentionDays", 30.5);
            case "string-duration" -> scope.put("minimumRetentionDays", "30");
            case "boolean-duration" -> scope.put("minimumRetentionDays", true);
            case "unselected-duration" -> scope.put("minimumRetentionDays", 30);
            case "unknown-field" -> scope.put("provider", "Fictional");
            case "verification-flag" -> scope.put("configurationVerified", true);
            case "helper-field" -> scope.put("retentionBoundToSelection", true);
            case "missing-version" -> update.remove("expectedVersion");
            default -> update.put("expectedVersion", 9007199254740992L);
        }
        sample("v6-invalid-" + scenario, "update-assessment-profile-request.v6", false, update);
        response("v6-invalid-" + scenario, mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON).content(update.toString()))
                .andExpect(status().isBadRequest()).andReturn());
        assertEquals(before, versionedSample("v6-invalid-unchanged", "assessment-response.v6", mvc.perform(get(path)).andReturn()));
        assertEquals(1, versionedSample("v6-invalid-no-history", "assessment-revision-page.v6", mvc.perform(get(path + "/revisions")).andReturn()).get("items").size());
        assertHistorySize(assessment, 1);
    }

    private JsonNode saveV6(String name, String path, ObjectNode request) throws Exception {
        sample(name + "-request", "update-assessment-profile-request.v6", true, request.deepCopy());
        return versionedSample(name, "assessment-response.v6", mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content(request.toString())).andExpect(status().isOk()).andReturn());
    }

    @ParameterizedTest @ValueSource(strings = {"SCIM_REQUIRED", "JIT_REQUIRED", "BOTH_REQUIRED", "GROUP_REQUIRED", "GROUP_FORBIDDEN", "UNKNOWN"})
    void lifecycleDesignPreviewBindsSavedCriticalitiesAndNeverWrites(String scenario) throws Exception {
        var assessment = create(); var update = request();
        var provisioning = (ObjectNode) update.get("profile").get("provisioning");
        provisioning.put("scim", scenario.equals("UNKNOWN") ? "UNKNOWN" : scenario.equals("JIT_REQUIRED") ? "FORBIDDEN" : "REQUIRED");
        provisioning.put("justInTimeProvisioning", scenario.equals("UNKNOWN") ? "UNKNOWN" : List.of("JIT_REQUIRED", "BOTH_REQUIRED").contains(scenario) ? "REQUIRED" : "NOT_REQUIRED");
        provisioning.put("groupSynchronization", scenario.equals("UNKNOWN") ? "UNKNOWN" : scenario.equals("GROUP_REQUIRED") ? "REQUIRED" : scenario.equals("GROUP_FORBIDDEN") ? "FORBIDDEN" : "NOT_REQUIRED");
        var before = response("lifecycle-saved-" + scenario, mvc.perform(put(assessment.path() + "/profile").contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isOk()).andReturn());
        var revisionBefore = mvc.perform(get(assessment.path() + "/revisions")).andReturn().getResponse().getContentAsString();
        var eventsBefore = mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString();
        for (var pattern : io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.DEFINITIONS) for (String declaration : List.of("EMPTY", "SATISFIED", "NOT_SATISFIED", "UNKNOWN")) {
            String name = "lifecycle-" + scenario + "-" + pattern.patternId() + "-" + declaration;
            var input = mapper.createObjectNode().put("expectedVersion", before.get("version").asLong()).put("patternId", pattern.patternId().name());
            var values = input.putObject("declarations");
            if (!declaration.equals("EMPTY")) pattern.conditions().forEach(id -> values.put(id.name(), declaration));
            sample(name + "-request", "provisioning-lifecycle-request", true, input);
            var result = mvc.perform(post(assessment.path() + "/provisioning-lifecycle-preview").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.assessmentVersion").value(before.get("version").asLong()))
                    .andExpect(jsonPath("$.analysis.requirementChecks.length()").value(3)).andReturn();
            var payload = mapper.readTree(result.getResponse().getContentAsString()); sample(name, "provisioning-lifecycle-preview", true, payload);
            assertEquals(provisioning, payload.get("analysis").get("requirements"));
            assertEquals(input.get("declarations"), payload.get("analysis").get("declarations"));
            if (scenario.equals("SCIM_REQUIRED") && pattern.patternId() == io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId.JIT_LOGIN)
                assertEquals("CONDITIONALLY_DOES_NOT_MATCH", payload.get("analysis").get("status").asText());
            if (declaration.equals("SATISFIED")) {
                for (String flag : List.of("configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified", "accessRevocationVerified", "writesPerformed", "publicationReady", "recommendationReady")) {
                    assertFalse(payload.get(flag).asBoolean()); var forged = (ObjectNode) payload.deepCopy(); forged.put(flag, true);
                    sample(name + "-no-" + flag, "provisioning-lifecycle-preview", false, forged);
                }
                var extra = (ObjectNode) payload.deepCopy(); extra.put("providerId", "unverified"); sample(name + "-no-provider", "provisioning-lifecycle-preview", false, extra);
                var incomplete = (ObjectNode) payload.deepCopy(); ((tools.jackson.databind.node.ArrayNode) incomplete.get("analysis").get("conditionChecks")).remove(0);
                sample(name + "-incomplete-conditions", "provisioning-lifecycle-preview", false, incomplete);
            }
        }
        assertEquals(before, response("lifecycle-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertEquals(revisionBefore, mvc.perform(get(assessment.path() + "/revisions")).andReturn().getResponse().getContentAsString());
        assertEquals(eventsBefore, mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString());
    }

    @Test void lifecyclePreviewReadsCurrentV6ProfileWithoutDowngradingAuditInputs() throws Exception {
        var assessment = create(); String v6 = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("lifecycle-v6-initial", "assessment-response.v6", mvc.perform(get(v6)).andExpect(status().isOk()).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile").deepCopy());
        var requirements = (ObjectNode) update.at("/profile/provisioning"); requirements.put("scim", "REQUIRED").put("justInTimeProvisioning", "NOT_REQUIRED").put("groupSynchronization", "NOT_REQUIRED");
        var security = (ObjectNode) update.at("/profile/security"); security.put("auditability", "REQUIRED");
        ((ObjectNode) security.get("auditabilityRequirements")).putArray("selectedCriteria").add("AUTHENTICATION_SUCCESS_EVENTS");
        var before = saveV6("lifecycle-v6-saved", v6, update);
        var history = versionedSample("lifecycle-v6-history", "assessment-revision-page.v6", mvc.perform(get(v6 + "/revisions")).andExpect(status().isOk()).andReturn());
        var events = historyResponse("lifecycle-v6-events", "events", mvc.perform(get(assessment.path() + "/events")).andExpect(status().isOk()).andReturn());
        var input = mapper.createObjectNode().put("expectedVersion", before.get("version").asLong()).put("patternId", "SCIM_PUSH");
        var declarations = input.putObject("declarations");
        io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.definition(io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId.SCIM_PUSH).conditions()
                .forEach(id -> declarations.put(id.name(), "SATISFIED"));
        sample("lifecycle-v6-current-request", "provisioning-lifecycle-request", true, input);
        var result = mvc.perform(post(assessment.path() + "/provisioning-lifecycle-preview").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.analysis.status").value("CONDITIONALLY_MATCHES")).andReturn();
        sample("lifecycle-v6-current", "provisioning-lifecycle-preview", true, mapper.readTree(result.getResponse().getContentAsString()));
        response("lifecycle-v1-projection-still-denied", mvc.perform(get(assessment.path())).andExpect(status().isConflict()).andReturn());
        assertEquals(before, versionedSample("lifecycle-v6-preserved", "assessment-response.v6", mvc.perform(get(v6)).andReturn()));
        assertEquals(history, versionedSample("lifecycle-v6-history-preserved", "assessment-revision-page.v6", mvc.perform(get(v6 + "/revisions")).andReturn()));
        assertEquals(events, historyResponse("lifecycle-v6-events-preserved", "events", mvc.perform(get(assessment.path() + "/events")).andReturn()));
    }

    @Test void lifecyclePreviewRejectsStaleForeignAndMalformedRequests() throws Exception {
        var assessment = create(); String path = assessment.path() + "/provisioning-lifecycle-preview";
        var valid = mapper.createObjectNode().put("expectedVersion", 0).put("patternId", "SCIM_PUSH"); valid.putObject("declarations");
        for (String mutation : List.of("missing-version", "negative", "unsafe-version", "string-version", "missing-pattern", "unknown-pattern", "missing-declarations", "null-declarations", "unknown-condition", "foreign-condition", "unknown-declaration", "null-declaration", "supplied-requirements", "supplied-time", "supplied-status")) {
            var input = valid.deepCopy();
            switch (mutation) {
                case "missing-version" -> input.remove("expectedVersion"); case "negative" -> input.put("expectedVersion", -1);
                case "unsafe-version" -> input.put("expectedVersion", 9007199254740992L); case "string-version" -> input.put("expectedVersion", "0");
                case "missing-pattern" -> input.remove("patternId"); case "unknown-pattern" -> input.put("patternId", "MANUAL");
                case "missing-declarations" -> input.remove("declarations"); case "null-declarations" -> input.putNull("declarations");
                case "unknown-condition" -> ((ObjectNode) input.get("declarations")).put("MADE_UP", "SATISFIED");
                case "foreign-condition" -> ((ObjectNode) input.get("declarations")).put("JIT_TRUSTED_LOGIN_AND_LINKING", "SATISFIED");
                case "unknown-declaration" -> ((ObjectNode) input.get("declarations")).put("SCIM_USER_OPERATIONS", "VERIFIED");
                case "null-declaration" -> ((ObjectNode) input.get("declarations")).putNull("SCIM_USER_OPERATIONS");
                case "supplied-requirements" -> input.putObject("requirements").put("scim", "NOT_REQUIRED");
                case "supplied-time" -> input.put("evaluatedAt", "2026-10-05T00:00:00Z");
                case "supplied-status" -> input.put("status", "CONDITIONALLY_MATCHES");
            }
            sample("lifecycle-invalid-" + mutation, "provisioning-lifecycle-request", false, input);
            response("lifecycle-invalid-problem", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isBadRequest()).andReturn());
        }
        for (String malformed : List.of(valid.toString() + "{}", valid.toString().replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0")))
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(malformed)).andExpect(status().isBadRequest());
        mvc.perform(post(path).queryParam("override", "true").contentType(MediaType.APPLICATION_JSON).content(valid.toString())).andExpect(status().isBadRequest());
        mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
        var stale = valid.deepCopy(); stale.put("expectedVersion", 1);
        response("lifecycle-stale", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(stale.toString())).andExpect(status().isConflict()).andReturn());
        response("lifecycle-other-workspace", mvc.perform(post(path.replace(assessment.workspaceId().value().toString(), UUID.randomUUID().toString())).contentType(MediaType.APPLICATION_JSON).content(valid.toString())).andExpect(status().isNotFound()).andReturn());
        response("lifecycle-missing", mvc.perform(post(path.replace(assessment.id().value().toString(), UUID.randomUUID().toString())).contentType(MediaType.APPLICATION_JSON).content(valid.toString())).andExpect(status().isNotFound()).andReturn());
        assertEquals(assessment.created(), response("lifecycle-malformed-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertHistorySize(assessment, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"SCIM_REQUIRED", "JIT_REQUIRED", "BOTH_REQUIRED", "GROUP_REQUIRED", "GROUP_FORBIDDEN", "UNKNOWN"})
    void lifecycleV2BindsGroupStrategyAndSeparateOffboardingWithoutWrites(String scenario) throws Exception {
        var assessment = create(); var update = request(); var provisioning = (ObjectNode) update.at("/profile/provisioning");
        provisioning.put("scim", scenario.equals("UNKNOWN") ? "UNKNOWN" : scenario.equals("JIT_REQUIRED") ? "FORBIDDEN" : "REQUIRED");
        provisioning.put("justInTimeProvisioning", scenario.equals("UNKNOWN") ? "UNKNOWN" : List.of("JIT_REQUIRED", "BOTH_REQUIRED").contains(scenario) ? "REQUIRED" : "NOT_REQUIRED");
        provisioning.put("groupSynchronization", scenario.equals("UNKNOWN") ? "UNKNOWN" : scenario.equals("GROUP_REQUIRED") ? "REQUIRED" : scenario.equals("GROUP_FORBIDDEN") ? "FORBIDDEN" : "NOT_REQUIRED");
        var before = response("lifecycle-v2-saved-" + scenario, mvc.perform(put(assessment.path() + "/profile").contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isOk()).andReturn());
        String path = assessment.path().replace("/api/v1/", "/api/v2/") + "/provisioning-lifecycle-preview";
        String history = mvc.perform(get(assessment.path() + "/revisions")).andReturn().getResponse().getContentAsString();
        String events = mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString();
        for (var pattern : io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId.values())
            for (var groups : io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.GroupStrategy.values())
                for (String declaration : List.of("EMPTY", "SATISFIED", "NOT_SATISFIED", "UNKNOWN")) {
                    String name = "lifecycle-v2-" + scenario + "-" + pattern + "-" + groups + "-" + declaration;
                    var input = mapper.createObjectNode().put("expectedVersion", before.get("version").asLong()).put("patternId", pattern.name()).put("groupStrategy", groups.name());
                    var values = input.putObject("declarations");
                    if (!declaration.equals("EMPTY")) io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.conditions(pattern, groups).forEach(id -> values.put(id.name(), declaration));
                    sample(name + "-request", "provisioning-lifecycle-request.v2", true, input);
                    var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isOk())
                            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.analysis.designChecks.length()").value(1))
                            .andExpect(jsonPath("$.assessmentVersion").value(before.get("version").asLong())).andReturn();
                    var payload = mapper.readTree(result.getResponse().getContentAsString()); sample(name, "provisioning-lifecycle-preview.v2", true, payload);
                    assertEquals(provisioning, payload.at("/analysis/requirements")); assertEquals(values, payload.at("/analysis/declarations"));
                    assertEquals(groups.name(), payload.at("/analysis/groupStrategy").asText());
                    if (declaration.equals("SATISFIED")) {
                        for (String flag : List.of("configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified", "accessRevocationVerified", "writesPerformed", "publicationReady", "recommendationReady")) {
                            assertFalse(payload.get(flag).asBoolean()); var invalid = (ObjectNode) payload.deepCopy(); invalid.put(flag, true);
                            sample(name + "-no-" + flag, "provisioning-lifecycle-preview.v2", false, invalid);
                        }
                        var incomplete = (ObjectNode) payload.deepCopy(); ((tools.jackson.databind.node.ArrayNode) incomplete.at("/analysis/conditionChecks")).remove(0);
                        sample(name + "-condition-gap", "provisioning-lifecycle-preview.v2", false, incomplete);
                        var duplicate = (ObjectNode) payload.deepCopy(); ((tools.jackson.databind.node.ArrayNode) duplicate.at("/analysis/conditionChecks")).set(1, duplicate.at("/analysis/conditionChecks/0").deepCopy());
                        sample(name + "-condition-duplicate", "provisioning-lifecycle-preview.v2", false, duplicate);
                    }
                }
        assertEquals(before, response("lifecycle-v2-unchanged", mvc.perform(get(assessment.path())).andReturn()));
        assertEquals(history, mvc.perform(get(assessment.path() + "/revisions")).andReturn().getResponse().getContentAsString());
        assertEquals(events, mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString());
    }

    @Test void lifecycleV2ReadsV6AndRetainsAllAuditInputsAndHistoricalV1Behavior() throws Exception {
        var assessment = create(); String v6 = assessment.path().replace("/api/v1/", "/api/v6/");
        var initial = versionedSample("lifecycle-v2-v6-initial", "assessment-response.v6", mvc.perform(get(v6)).andExpect(status().isOk()).andReturn());
        var update = mapper.createObjectNode().put("expectedVersion", 0); update.set("profile", initial.get("profile").deepCopy());
        ((ObjectNode) update.at("/profile/provisioning")).put("scim", "REQUIRED").put("justInTimeProvisioning", "NOT_REQUIRED").put("groupSynchronization", "REQUIRED");
        ((ObjectNode) update.at("/profile/security")).put("auditability", "REQUIRED");
        ((ObjectNode) update.at("/profile/security/auditabilityRequirements")).putArray("selectedCriteria").add("PROVISIONING_CHANGE_EVENTS");
        var before = saveV6("lifecycle-v2-v6-saved", v6, update);
        String history = mvc.perform(get(v6 + "/revisions")).andReturn().getResponse().getContentAsString();
        String events = mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString();
        var input = mapper.createObjectNode().put("expectedVersion", before.get("version").asLong()).put("patternId", "SCIM_PUSH").put("groupStrategy", "SCIM_GROUPS");
        var declarations = input.putObject("declarations");
        io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.conditions(io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId.SCIM_PUSH,
                io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.GroupStrategy.SCIM_GROUPS).forEach(id -> declarations.put(id.name(), "SATISFIED"));
        sample("lifecycle-v2-v6-current-request", "provisioning-lifecycle-request.v2", true, input);
        var result = mvc.perform(post(assessment.path().replace("/api/v1/", "/api/v2/") + "/provisioning-lifecycle-preview").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysis.status").value("CONDITIONALLY_MATCHES")).andReturn();
        sample("lifecycle-v2-v6-current", "provisioning-lifecycle-preview.v2", true, mapper.readTree(result.getResponse().getContentAsString()));
        var legacy = mapper.createObjectNode().put("expectedVersion", before.get("version").asLong()).put("patternId", "SCIM_PUSH"); var legacyValues = legacy.putObject("declarations");
        io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.definition(io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId.SCIM_PUSH).conditions().forEach(id -> legacyValues.put(id.name(), "SATISFIED"));
        mvc.perform(post(assessment.path() + "/provisioning-lifecycle-preview").contentType(MediaType.APPLICATION_JSON).content(legacy.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policyVersion").value("provisioning-lifecycle-design-1"))
                .andExpect(jsonPath("$.analysis.requirementChecks[2].reasonCode").value("GROUP_LIFECYCLE_UNASSESSED"));
        assertEquals(before, versionedSample("lifecycle-v2-v6-preserved", "assessment-response.v6", mvc.perform(get(v6)).andReturn()));
        assertEquals(history, mvc.perform(get(v6 + "/revisions")).andReturn().getResponse().getContentAsString());
        assertEquals(events, mvc.perform(get(assessment.path() + "/events")).andReturn().getResponse().getContentAsString());
    }

    @Test void lifecycleV2RejectsForeignConditionsAuthorityStalenessAndMalformedInput() throws Exception {
        var assessment = create(); String path = assessment.path().replace("/api/v1/", "/api/v2/") + "/provisioning-lifecycle-preview";
        var valid = mapper.createObjectNode().put("expectedVersion", 0).put("patternId", "SCIM_PUSH").put("groupStrategy", "NONE"); valid.putObject("declarations");
        for (String mutation : List.of("missing-version", "negative-version", "unsafe-version", "string-version", "fraction-version", "boolean-version", "missing-pattern", "unknown-pattern", "missing-groups", "null-groups", "unknown-groups", "missing-declarations", "null-declarations", "foreign-pattern", "foreign-group", "legacy-condition", "unknown-declaration", "null-declaration", "requirements", "verified", "time", "provider")) {
            var input = valid.deepCopy(); var declarations = (ObjectNode) input.get("declarations");
            switch (mutation) {
                case "missing-version" -> input.remove("expectedVersion"); case "negative-version" -> input.put("expectedVersion", -1);
                case "unsafe-version" -> input.put("expectedVersion", 9007199254740992L); case "string-version" -> input.put("expectedVersion", "0");
                case "fraction-version" -> input.put("expectedVersion", 0.1); case "boolean-version" -> input.put("expectedVersion", true);
                case "missing-pattern" -> input.remove("patternId"); case "unknown-pattern" -> input.put("patternId", "MANUAL");
                case "missing-groups" -> input.remove("groupStrategy"); case "null-groups" -> input.putNull("groupStrategy"); case "unknown-groups" -> input.put("groupStrategy", "LOGIN_CLAIMS");
                case "missing-declarations" -> input.remove("declarations"); case "null-declarations" -> input.putNull("declarations");
                case "foreign-pattern" -> declarations.put("JIT_TRUSTED_LOGIN_AND_LINKING", "SATISFIED"); case "foreign-group" -> declarations.put("GROUP_REMOVAL_AND_ACCESS_RECHECK", "SATISFIED");
                case "legacy-condition" -> declarations.put("OFFBOARDING_AND_ACCESS_REVOCATION", "SATISFIED");
                case "unknown-declaration" -> declarations.put("APPLICATION_SESSION_INVALIDATION", "VERIFIED"); case "null-declaration" -> declarations.putNull("SCIM_USER_OPERATIONS");
                case "requirements" -> input.putObject("requirements").put("scim", "NOT_REQUIRED"); case "verified" -> input.put("accessRevocationVerified", true);
                case "time" -> input.put("evaluatedAt", "2026-10-05T00:00:00Z"); case "provider" -> input.put("providerId", "synthetic");
            }
            sample("lifecycle-v2-invalid-" + mutation, "provisioning-lifecycle-request.v2", false, input);
            response("lifecycle-v2-invalid-problem", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isBadRequest()).andReturn());
        }
        for (String malformed : List.of(valid.toString() + "{}", valid.toString().replace("\"groupStrategy\":\"NONE\"", "\"groupStrategy\":\"NONE\",\"groupStrategy\":\"NONE\""), "{\"expectedVersion\":0,\"patternId\":\"SCIM_PUSH\",\"groupStrategy\":\"NONE\",\"declarations\":{\"SCIM_USER_OPERATIONS\":\"UNKNOWN\",\"SCIM_USER_OPERATIONS\":\"SATISFIED\"}}"))
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(malformed)).andExpect(status().isBadRequest());
        mvc.perform(post(path).queryParam("override", "true").contentType(MediaType.APPLICATION_JSON).content(valid.toString())).andExpect(status().isBadRequest());
        mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
        var stale = valid.deepCopy(); stale.put("expectedVersion", 1);
        response("lifecycle-v2-stale", mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(stale.toString())).andExpect(status().isConflict()).andReturn());
        response("lifecycle-v2-other-workspace", mvc.perform(post(path.replace(assessment.workspaceId().value().toString(), UUID.randomUUID().toString())).contentType(MediaType.APPLICATION_JSON).content(valid.toString())).andExpect(status().isNotFound()).andReturn());
        response("lifecycle-v2-missing", mvc.perform(post(path.replace(assessment.id().value().toString(), UUID.randomUUID().toString())).contentType(MediaType.APPLICATION_JSON).content(valid.toString())).andExpect(status().isNotFound()).andReturn());
        assertEquals(assessment.created(), response("lifecycle-v2-malformed-preserved", mvc.perform(get(assessment.path())).andReturn())); assertHistorySize(assessment, 1);
    }

    private ObjectNode request() {
        ObjectNode request = mapper.createObjectNode().put("expectedVersion", 0);
        request.set("profile", mapper.valueToTree(ApplicationIdentityProfile.unknown()));
        return request;
    }

    private ApiAssessment create() throws Exception {
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        String workspacePath = "/api/v1/workspaces/" + workspaceId.value();
        mvc.perform(put(workspacePath)).andExpect(status().isCreated());
        JsonNode created = response("create", mvc.perform(post(workspacePath + "/assessments"))
                .andExpect(status().isCreated()).andReturn());
        AssessmentId id = new AssessmentId(UUID.fromString(created.get("id").asText()));
        return new ApiAssessment(workspaceId, id, workspacePath + "/assessments/" + id.value(), created);
    }

    private JsonNode response(String name, MvcResult result) throws Exception {
        JsonNode payload = mapper.readTree(result.getResponse().getContentAsString());
        sample(name, result.getResponse().getStatus() < 400 ? "assessment-response" : "core-problem", true, payload);
        return payload;
    }

    private void sample(String name, String schema, boolean valid, JsonNode payload) {
        samples.add(new ContractSample(name, schema, valid, payload));
    }

    private record ApiAssessment(WorkspaceId workspaceId, AssessmentId id, String path, JsonNode created) { }
    private record ContractSample(String name, String schema, boolean valid, JsonNode payload) { }
}
