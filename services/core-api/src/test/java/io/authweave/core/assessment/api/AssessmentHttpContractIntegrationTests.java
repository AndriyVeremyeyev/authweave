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
                .andExpect(jsonPath("$.catalogVersion").value("synthetic-2026-09-12.2"))
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
