package io.authweave.core.assessment.seed;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfileValidator;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.persistence.AssessmentRepository;
import io.authweave.core.assessment.persistence.WorkspaceRepository;

import static io.authweave.core.assessment.seed.SyntheticAssessmentSeeder.WORKSPACE_ID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-seed")
@Transactional
class SyntheticAssessmentSeederIntegrationTests extends PostgresIntegrationTest {

    @Autowired private SyntheticAssessmentSeeder seeder;
    @Autowired private AssessmentApplicationService service;
    @Autowired private AssessmentRepository assessments;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private tools.jackson.databind.ObjectMapper mapper;

    @Test
    void createsThreeDistinctDraftsWithCompleteInitialHistory() {
        var results = seeder.seed();
        assertEquals(Set.of("b2b-saas", "public-sector-portal", "internal-workforce"),
                results.stream().map(SyntheticAssessmentSeeder.SeedResult::key).collect(Collectors.toSet()));
        assertTrue(results.stream().allMatch(SyntheticAssessmentSeeder.SeedResult::created));
        for (var result : results) {
            var id = new AssessmentId(result.id());
            var current = service.getAssessment(WORKSPACE_ID, id);
            assertEquals(AssessmentStatus.DRAFT, current.assessment().status());
            assertEquals(0, current.version());
            assertTrue(ApplicationIdentityProfileValidator.validate(current.assessment().profile()).canEvaluate());
            var revisions = service.getRevisions(WORKSPACE_ID, id, null, 100).items();
            assertEquals(1, revisions.size());
            assertEquals(mapper.valueToTree(current.assessment().profile()), revisions.getFirst().profile());
            var events = service.getEvents(WORKSPACE_ID, id, null, 100).items();
            assertEquals(1, events.size());
            assertEquals("assessment.created", events.getFirst().action());
            assertEquals(0, events.getFirst().version());
            if (result.key().equals("b2b-saas")) {
                assertEquals(RequirementCriticality.REQUIRED, current.assessment().profile().provisioning().scim());
            } else {
                assertNotEquals(RequirementCriticality.REQUIRED, current.assessment().profile().provisioning().scim());
                assertEquals(RequirementCriticality.FORBIDDEN, current.assessment().profile().protocols().socialLogin());
            }
        }
    }

    @Test
    void repeatedSeedPreservesEditedArchivedAndUnrelatedAssessments() {
        var first = seeder.seed();
        var editedId = new AssessmentId(first.getFirst().id());
        var edited = service.getAssessment(WORKSPACE_ID, editedId);
        var alternateProfile = service.getAssessment(WORKSPACE_ID, new AssessmentId(first.getLast().id()))
                .assessment().profile();
        edited = service.updateProfile(WORKSPACE_ID, editedId, edited.version(), alternateProfile);
        edited.assessment().archive();
        var archived = assessments.update(edited.assessment(), edited.version());
        var unrelatedId = new AssessmentId(UUID.randomUUID());
        var unrelated = service.createAssessment(WORKSPACE_ID, unrelatedId);

        assertTrue(seeder.seed().stream().noneMatch(SyntheticAssessmentSeeder.SeedResult::created));
        var unchanged = service.getAssessment(WORKSPACE_ID, editedId);
        assertEquals(archived.version(), unchanged.version());
        assertEquals(archived.updatedAt(), unchanged.updatedAt());
        assertEquals(AssessmentStatus.ARCHIVED, unchanged.assessment().status());
        assertEquals(alternateProfile, unchanged.assessment().profile());
        assertEquals(3, service.getRevisions(WORKSPACE_ID, editedId, null, 100).items().size());
        assertEquals(3, service.getEvents(WORKSPACE_ID, editedId, null, 100).items().size());
        assertEquals(unrelated.updatedAt(), service.getAssessment(WORKSPACE_ID, unrelatedId).updatedAt());
    }

    @Test
    void seedParticipatesInOneTransactionIncludingWorkspaceAndHistory() {
        var results = seeder.seed();
        assertTrue(workspaces.exists(WORKSPACE_ID));
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertFalse(workspaces.exists(WORKSPACE_ID));
        for (var result : results) {
            assertFalse(assessments.exists(WORKSPACE_ID, new AssessmentId(result.id())));
        }
    }
}
