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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exports actual MVC requests/responses for independent AJV checks in make check-core and CI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(AssessmentHttpContractIntegrationTests.PreflightClock.class)
class AssessmentHttpContractIntegrationTests extends PostgresIntegrationTest {

    private static final Path SAMPLES = Path.of("target", "core-http-contract-samples.json");
    private final List<ContractSample> samples = new ArrayList<>();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AssessmentRepository repository;

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

    @AfterAll
    void exportSamples() throws Exception {
        Files.createDirectories(SAMPLES.getParent());
        Files.writeString(SAMPLES, mapper.writeValueAsString(samples));
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
