package io.authweave.core.assessment.api;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.assessment.application.PersonalWorkspaceService;
import tools.jackson.databind.ObjectMapper;

import static io.authweave.core.generated.jooq.tables.PersonalWorkspaces.PERSONAL_WORKSPACES;
import static io.authweave.core.generated.jooq.tables.Workspaces.WORKSPACES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-internal-token-000000000000000000000")
@AutoConfigureMockMvc
class PersonalWorkspaceIntegrationTests extends PostgresIntegrationTest {

    private static final String PATH = "/internal/v1/personal-workspaces";
    private static final String TOKEN = "Bearer synthetic-internal-token-000000000000000000000";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;
    @Autowired private PersonalWorkspaceService service;

    @Test
    void rejectsAnonymousOrWrongServiceCredentialBeforeProvisioning() throws Exception {
        String subject = "unauthorized-" + UUID.randomUUID();
        String body = request("http://localhost:8081", subject);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, dsl.fetchCount(PERSONAL_WORKSPACES,
                PERSONAL_WORKSPACES.SUBJECT.eq(subject)));
    }

    @Test
    void bindsOneWorkspaceToOneIssuerAndSubjectWithoutCrossUserReuse() throws Exception {
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID aliceId = provision("http://localhost:8081", alice);
        assertEquals(aliceId, provision("http://localhost:8081", alice));
        UUID bobId = provision("http://localhost:8081", bob);
        UUID otherIssuerId = provision("https://other.example.test", alice);
        assertNotEquals(aliceId, bobId);
        assertNotEquals(aliceId, otherIssuerId);
        assertEquals(1, dsl.fetchCount(PERSONAL_WORKSPACES,
                PERSONAL_WORKSPACES.ISSUER.eq("http://localhost:8081")
                        .and(PERSONAL_WORKSPACES.SUBJECT.eq(alice))));
        assertTrue(dsl.fetchExists(WORKSPACES, WORKSPACES.ID.eq(aliceId)));
    }

    @Test
    void serializesConcurrentFirstLoginsWithoutOrphanWorkspaces() throws Exception {
        String subject = "concurrent-" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.provision("http://localhost:8081", subject));
            var second = executor.submit(() -> service.provision("http://localhost:8081", subject));
            UUID workspaceId = first.get(10, TimeUnit.SECONDS);
            assertEquals(workspaceId, second.get(10, TimeUnit.SECONDS));
            assertEquals(1, dsl.fetchCount(PERSONAL_WORKSPACES,
                    PERSONAL_WORKSPACES.SUBJECT.eq(subject)));
        }
    }

    @Test
    void rejectsInvalidIssuerAndRuntimeCannotRewriteOwnership() throws Exception {
        String subject = "invalid-" + UUID.randomUUID();
        mvc.perform(post(PATH).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("http://example.test", subject)))
                .andExpect(status().isBadRequest());
        UUID workspaceId = provision("http://localhost:8081", subject);
        assertThrows(DataAccessException.class,
                () -> dsl.update(PERSONAL_WORKSPACES)
                        .set(PERSONAL_WORKSPACES.WORKSPACE_ID, UUID.randomUUID())
                        .where(PERSONAL_WORKSPACES.WORKSPACE_ID.eq(workspaceId)).execute());
        assertThrows(DataAccessException.class,
                () -> dsl.deleteFrom(PERSONAL_WORKSPACES)
                        .where(PERSONAL_WORKSPACES.WORKSPACE_ID.eq(workspaceId)).execute());
        assertEquals(1, dsl.fetchCount(PERSONAL_WORKSPACES,
                PERSONAL_WORKSPACES.WORKSPACE_ID.eq(workspaceId)));
    }

    @Test
    void protectsEveryVersionedWorkspaceRouteBeforeControllerDispatch() throws Exception {
        String issuer = "http://localhost:8081";
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID aliceWorkspace = provision(issuer, alice);
        UUID bobWorkspace = provision(issuer, bob);

        for (int version = 1; version <= 5; version++) {
            String alicePath = "/api/v" + version + "/workspaces/" + aliceWorkspace;
            mvc.perform(post(alicePath + "/assessments"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(alicePath + "/assessments").header("Authorization", TOKEN))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(alicePath + "/assessments")
                            .header("Authorization", TOKEN)
                            .header("X-AuthWeave-Oidc-Issuer", issuer)
                            .header("X-AuthWeave-Oidc-Subject", bob))
                    .andExpect(status().isForbidden());
            mvc.perform(get(alicePath + "/assessments/" + UUID.randomUUID())
                            .header("Authorization", TOKEN)
                            .header("X-AuthWeave-Oidc-Issuer", issuer)
                            .header("X-AuthWeave-Oidc-Subject", bob))
                    .andExpect(status().isForbidden());
            mvc.perform(put(alicePath + "/assessments/" + UUID.randomUUID() + "/profile")
                            .header("Authorization", TOKEN)
                            .header("X-AuthWeave-Oidc-Issuer", issuer)
                            .header("X-AuthWeave-Oidc-Subject", bob))
                    .andExpect(status().isForbidden());
            mvc.perform(get(alicePath + "/assessments/" + UUID.randomUUID() + "/revisions")
                            .header("Authorization", TOKEN)
                            .header("X-AuthWeave-Oidc-Issuer", issuer)
                            .header("X-AuthWeave-Oidc-Subject", bob))
                    .andExpect(status().isForbidden());
        }

        mvc.perform(put("/api/v1/workspaces/" + bobWorkspace)
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/workspaces/" + aliceWorkspace)
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isNoContent());
        String created = mvc.perform(post("/api/v1/workspaces/" + aliceWorkspace + "/assessments")
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String summaryPath = "/api/v5/workspaces/" + aliceWorkspace + "/assessments/"
                + mapper.readTree(created).get("id").asText() + "/hard-constraint-preflight";
        mvc.perform(get(summaryPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(summaryPath).header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", bob))
                .andExpect(status().isForbidden());
        mvc.perform(get(summaryPath).header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationReady").value(false));
    }

    @Test
    void rejectsAmbiguousPrincipalHeadersAndMalformedWorkspacePaths() throws Exception {
        String issuer = "http://localhost:8081";
        String subject = "alice-" + UUID.randomUUID();
        UUID workspaceId = provision(issuer, subject);
        String path = "/api/v1/workspaces/" + workspaceId + "/assessments";
        mvc.perform(post(path).header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", subject, subject))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", TOKEN, TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", subject))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workspaces/not-a-uuid/assessments/" + UUID.randomUUID())
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", subject))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsOnlyOwnedAssessmentsAndRejectsForeignPaginationCursor() throws Exception {
        String issuer = "http://localhost:8081";
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID aliceWorkspace = provision(issuer, alice);
        UUID bobWorkspace = provision(issuer, bob);
        String alicePath = "/api/v5/workspaces/" + aliceWorkspace + "/assessments";
        String bobPath = "/api/v5/workspaces/" + bobWorkspace + "/assessments";
        for (int index = 0; index < 2; index++) {
            mvc.perform(post(alicePath).header("Authorization", TOKEN)
                            .header("X-AuthWeave-Oidc-Issuer", issuer)
                            .header("X-AuthWeave-Oidc-Subject", alice))
                    .andExpect(status().isCreated());
        }
        String bobBody = mvc.perform(post(bobPath).header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", bob))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String bobAssessmentId = mapper.readTree(bobBody).get("id").asText();

        mvc.perform(get(alicePath)).andExpect(status().isUnauthorized());
        mvc.perform(get(alicePath).header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", bob))
                .andExpect(status().isForbidden());
        String firstPage = mvc.perform(get(alicePath).param("limit", "1")
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextBeforeId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String beforeId = mapper.readTree(firstPage).get("nextBeforeId").asText();
        mvc.perform(get(alicePath).param("limit", "1").param("beforeId", beforeId)
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextBeforeId").isEmpty());
        mvc.perform(get(alicePath).param("beforeId", bobAssessmentId)
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isNotFound());
        mvc.perform(get(alicePath).param("limit", "0")
                        .header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", issuer)
                        .header("X-AuthWeave-Oidc-Subject", alice))
                .andExpect(status().isBadRequest());
    }

    private UUID provision(String issuer, String subject) throws Exception {
        String response = mvc.perform(post(PATH).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(request(issuer, subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).get("workspaceId").asText());
    }

    private static String request(String issuer, String subject) {
        return "{\"issuer\":\"" + issuer + "\",\"subject\":\"" + subject + "\"}";
    }
}
