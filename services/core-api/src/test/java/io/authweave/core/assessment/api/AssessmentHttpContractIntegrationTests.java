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
    }

    private void fillDraftMap(ObjectNode map, Class<? extends Enum<?>> type, JsonNode value, java.util.Set<String> excluded) {
        for (var key : type.getEnumConstants()) if (!excluded.contains(key.name())) map.set(key.name(), value.deepCopy());
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
