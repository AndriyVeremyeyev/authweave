package io.authweave.core.assessment.api;

import java.nio.file.Files;
import java.nio.file.Path;
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
class AssessmentHttpContractIntegrationTests extends PostgresIntegrationTest {

    private static final Path SAMPLES = Path.of("target", "core-http-contract-samples.json");
    private final List<ContractSample> samples = new ArrayList<>();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AssessmentRepository repository;

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
