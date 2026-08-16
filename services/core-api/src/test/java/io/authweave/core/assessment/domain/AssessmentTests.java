package io.authweave.core.assessment.domain;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.InvalidApplicationIdentityProfileException;
import io.authweave.core.assessment.domain.profile.OperationalConstraints;
import io.authweave.core.assessment.domain.profile.ProtocolRequirements;
import io.authweave.core.assessment.domain.profile.ProvisioningRequirements;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.SecurityRequirements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssessmentTests {

    private static final WorkspaceId WORKSPACE_ID =
            new WorkspaceId(UUID.fromString("ce9616c3-2b51-4897-a520-0df3c4d321ce"));

    @Test
    void createsDraftInsideItsWorkspace() {
        AssessmentId assessmentId =
                new AssessmentId(UUID.fromString("2d4ef269-c82c-457e-a6c8-d13f5204a04b"));

        Assessment assessment = Assessment.createDraft(assessmentId, WORKSPACE_ID);

        assertEquals(assessmentId, assessment.id());
        assertEquals(WORKSPACE_ID, assessment.workspaceId());
        assertEquals(AssessmentStatus.DRAFT, assessment.status());
        assertEquals(ApplicationIdentityProfile.unknown(), assessment.profile());
    }

    @Test
    void followsTheDecisionLifecycle() {
        Assessment assessment = newDraft();
        assessment.updateProfile(readyProfile(ApplicationType.B2B_SAAS));

        assessment.markReadyForEvaluation();
        assessment.recordEvaluation();
        assessment.recordDecision();

        assertEquals(AssessmentStatus.DECIDED, assessment.status());
    }

    @Test
    void revisionInvalidatesAnExistingEvaluation() {
        Assessment assessment = newDraft();
        assessment.updateProfile(readyProfile(ApplicationType.B2B_SAAS));
        assessment.markReadyForEvaluation();
        assessment.recordEvaluation();

        ApplicationIdentityProfile revisedProfile = readyProfile(ApplicationType.PARTNER_PORTAL);
        assessment.updateProfile(revisedProfile);

        assertEquals(AssessmentStatus.DRAFT, assessment.status());
        assertEquals(revisedProfile, assessment.profile());
    }

    @Test
    void cannotSkipRequiredLifecycleSteps() {
        Assessment assessment = newDraft();

        InvalidAssessmentTransitionException exception = assertThrows(
                InvalidAssessmentTransitionException.class,
                assessment::recordDecision);

        assertEquals(AssessmentStatus.DRAFT, exception.currentStatus());
        assertEquals(AssessmentStatus.DECIDED, exception.targetStatus());
        assertEquals(AssessmentStatus.DRAFT, assessment.status());
    }

    @Test
    void archivedAssessmentIsTerminal() {
        Assessment assessment = newDraft();
        assessment.archive();

        InvalidAssessmentTransitionException exception = assertThrows(
                InvalidAssessmentTransitionException.class,
                assessment::revise);

        assertEquals(AssessmentStatus.ARCHIVED, exception.currentStatus());
        assertEquals(AssessmentStatus.DRAFT, exception.targetStatus());
    }

    @Test
    void incompleteProfileCannotBecomeReadyForEvaluation() {
        Assessment assessment = newDraft();

        InvalidApplicationIdentityProfileException exception = assertThrows(
                InvalidApplicationIdentityProfileException.class,
                assessment::markReadyForEvaluation);

        assertEquals(
                Set.of(
                        "application_type_required",
                        "client_type_required"),
                exception.issues().stream()
                        .map(issue -> issue.code())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(AssessmentStatus.DRAFT, assessment.status());
    }

    @Test
    void contradictoryProfileCannotReplaceCurrentDraft() {
        Assessment assessment = newDraft();
        ApplicationIdentityProfile originalProfile = readyProfile(ApplicationType.B2B_SAAS);
        assessment.updateProfile(originalProfile);
        ApplicationIdentityProfile contradictoryProfile = new ApplicationIdentityProfile(
                originalProfile.application(),
                new AudienceRequirements(
                        Set.of(UserPopulation.EXTERNAL_CUSTOMERS),
                        TenancyModel.SINGLE_ORGANIZATION,
                        MembershipModel.MULTIPLE_ORGANIZATIONS_PER_USER),
                originalProfile.protocols(),
                originalProfile.provisioning(),
                originalProfile.security(),
                originalProfile.operations());

        InvalidApplicationIdentityProfileException exception = assertThrows(
                InvalidApplicationIdentityProfileException.class,
                () -> assessment.updateProfile(contradictoryProfile));

        assertEquals(
                "multi_organization_membership_requires_multi_tenancy",
                exception.issues().getFirst().code());
        assertEquals(originalProfile, assessment.profile());
    }

    private static Assessment newDraft() {
        return Assessment.createDraft(AssessmentId.generate(), WORKSPACE_ID);
    }

    private static ApplicationIdentityProfile readyProfile(ApplicationType applicationType) {
        return new ApplicationIdentityProfile(
                new ApplicationTopology(applicationType, Set.of(ClientType.BROWSER)),
                new AudienceRequirements(
                        Set.of(UserPopulation.EXTERNAL_CUSTOMERS),
                        TenancyModel.MULTI_TENANT_ORGANIZATIONS,
                        MembershipModel.SINGLE_ORGANIZATION_PER_USER),
                new ProtocolRequirements(
                        Map.of(),
                        RequirementCriticality.UNKNOWN,
                        RequirementCriticality.UNKNOWN,
                        RequirementCriticality.UNKNOWN),
                ProvisioningRequirements.unknown(),
                SecurityRequirements.unknown(),
                OperationalConstraints.unknown());
    }
}
