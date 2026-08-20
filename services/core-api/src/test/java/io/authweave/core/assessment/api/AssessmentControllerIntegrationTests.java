package io.authweave.core.assessment.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.AudienceRequirements;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AssessmentControllerIntegrationTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void provisionsAWorkspaceAndCompletesTheCreateReadUpdateFlow() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        String workspacePath = "/api/v1/workspaces/" + workspaceId;

        mockMvc.perform(put(workspacePath))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", workspacePath));
        mockMvc.perform(put(workspacePath))
                .andExpect(status().isNoContent());

        String createResponse = mockMvc.perform(post(workspacePath + "/assessments"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.profile.application.type").value("UNKNOWN"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode created = objectMapper.readTree(createResponse);
        UUID assessmentId = UUID.fromString(created.get("id").asText());
        String assessmentPath = workspacePath + "/assessments/" + assessmentId;

        mockMvc.perform(get(assessmentPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assessmentId.toString()))
                .andExpect(jsonPath("$.version").value(0));

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 0,
                "profile", ApplicationIdentityProfile.unknown()));
        mockMvc.perform(put(assessmentPath + "/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void returnsProblemDetailsForAStaleProfileUpdate() throws Exception {
        PersistedApiAssessment assessment = createAssessment();
        String updateBody = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 0,
                "profile", ApplicationIdentityProfile.unknown()));
        mockMvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("urn:authweave:problem:assessment-version-conflict"))
                .andExpect(jsonPath("$.code").value("assessment-version-conflict"))
                .andExpect(jsonPath("$.assessmentId").value(assessment.assessmentId().toString()))
                .andExpect(jsonPath("$.expectedVersion").value(0))
                .andExpect(jsonPath("$.actualVersion").value(1));
    }

    @Test
    void rejectsAContradictoryProfileWithStableIssueCodes() throws Exception {
        PersistedApiAssessment assessment = createAssessment();
        ApplicationIdentityProfile unknown = ApplicationIdentityProfile.unknown();
        ApplicationIdentityProfile contradictory = new ApplicationIdentityProfile(
                unknown.application(),
                new AudienceRequirements(
                        Set.of(),
                        TenancyModel.NO_ORGANIZATION_BOUNDARY,
                        MembershipModel.SINGLE_ORGANIZATION_PER_USER),
                unknown.protocols(),
                unknown.provisioning(),
                unknown.security(),
                unknown.operations());
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 0,
                "profile", contradictory));

        mockMvc.perform(put(assessment.path() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("urn:authweave:problem:invalid-application-identity-profile"))
                .andExpect(jsonPath("$.issues[0].code")
                        .value("organization_membership_without_organization_boundary"))
                .andExpect(jsonPath("$.issues[0].path").value("audience.membership"));
    }

    @Test
    void doesNotExposeAnAssessmentThroughAnotherWorkspace() throws Exception {
        PersistedApiAssessment assessment = createAssessment();
        UUID otherWorkspaceId = UUID.randomUUID();
        String otherWorkspacePath = "/api/v1/workspaces/" + otherWorkspaceId;
        mockMvc.perform(put(otherWorkspacePath))
                .andExpect(status().isCreated());

        mockMvc.perform(get(otherWorkspacePath + "/assessments/" + assessment.assessmentId()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("assessment-not-found"))
                .andExpect(jsonPath("$.workspaceId").value(otherWorkspaceId.toString()));
    }

    private PersistedApiAssessment createAssessment() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        String workspacePath = "/api/v1/workspaces/" + workspaceId;
        mockMvc.perform(put(workspacePath))
                .andExpect(status().isCreated());
        String responseBody = mockMvc.perform(post(workspacePath + "/assessments"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID assessmentId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
        return new PersistedApiAssessment(
                assessmentId,
                workspacePath + "/assessments/" + assessmentId);
    }

    private record PersistedApiAssessment(UUID assessmentId, String path) {
    }
}
