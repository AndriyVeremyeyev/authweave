package io.authweave.core.assessment.domain.profile;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.ProtocolRequirements.FederationProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationIdentityProfileValidatorTests {

    @Test
    void incompleteDraftCanBeSavedButCannotBeEvaluated() {
        ProfileValidationResult result =
                ApplicationIdentityProfileValidator.validate(ApplicationIdentityProfile.unknown());

        assertTrue(result.canSave());
        assertFalse(result.canEvaluate());
        assertEquals(
                Set.of("application_type_required", "client_type_required"),
                issueCodes(result));
    }

    @Test
    void readyProfileCanBeEvaluatedWithNonCriticalUnknowns() {
        ProfileValidationResult result =
                ApplicationIdentityProfileValidator.validate(validProfile());

        assertTrue(result.canSave());
        assertTrue(result.canEvaluate());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void multipleMembershipsRequireMultiTenantOrganizations() {
        ApplicationIdentityProfile profile = withAudience(
                new AudienceRequirements(
                        Set.of(UserPopulation.EXTERNAL_CUSTOMERS),
                        TenancyModel.SINGLE_ORGANIZATION,
                        MembershipModel.MULTIPLE_ORGANIZATIONS_PER_USER));

        ProfileValidationResult result = ApplicationIdentityProfileValidator.validate(profile);

        assertFalse(result.canSave());
        assertEquals(
                Set.of("multi_organization_membership_requires_multi_tenancy"),
                issueCodes(result));
    }

    @Test
    void socialLoginConflictsWithWorkforceOnlyPopulation() {
        ApplicationIdentityProfile base = new ApplicationIdentityProfile(
                new ApplicationTopology(
                        ApplicationType.INTERNAL_WORKFORCE,
                        Set.of(ClientType.BROWSER)),
                new AudienceRequirements(
                        Set.of(UserPopulation.EMPLOYEES, UserPopulation.CONTRACTORS),
                        TenancyModel.SINGLE_ORGANIZATION,
                        MembershipModel.SINGLE_ORGANIZATION_PER_USER),
                new ProtocolRequirements(
                        Map.of(FederationProtocol.OIDC, RequirementCriticality.REQUIRED),
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.REQUIRED),
                ProvisioningRequirements.unknown(),
                SecurityRequirements.unknown(),
                OperationalConstraints.unknown());

        ProfileValidationResult result = ApplicationIdentityProfileValidator.validate(base);

        assertFalse(result.canSave());
        assertEquals(
                Set.of("social_login_conflicts_with_workforce_only_population"),
                issueCodes(result));
    }

    @Test
    void requiredEnterpriseSsoNeedsAnEligibleFederationProtocol() {
        ApplicationIdentityProfile base = validProfile();
        ApplicationIdentityProfile profile = new ApplicationIdentityProfile(
                base.application(),
                base.audience(),
                new ProtocolRequirements(
                        Map.of(
                                FederationProtocol.OIDC,
                                RequirementCriticality.NOT_REQUIRED,
                                FederationProtocol.SAML,
                                RequirementCriticality.NOT_REQUIRED),
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.NOT_REQUIRED,
                        RequirementCriticality.REQUIRED),
                base.provisioning(),
                base.security(),
                base.operations());

        ProfileValidationResult result = ApplicationIdentityProfileValidator.validate(profile);

        assertFalse(result.canSave());
        assertEquals(
                Set.of("enterprise_sso_requires_federation_protocol"),
                issueCodes(result));
    }

    private static ApplicationIdentityProfile validProfile() {
        return new ApplicationIdentityProfile(
                new ApplicationTopology(
                        ApplicationType.B2B_SAAS,
                        Set.of(ClientType.BROWSER)),
                new AudienceRequirements(
                        Set.of(UserPopulation.EXTERNAL_CUSTOMERS),
                        TenancyModel.MULTI_TENANT_ORGANIZATIONS,
                        MembershipModel.SINGLE_ORGANIZATION_PER_USER),
                ProtocolRequirements.unknown(),
                ProvisioningRequirements.unknown(),
                SecurityRequirements.unknown(),
                OperationalConstraints.unknown());
    }

    private static ApplicationIdentityProfile withAudience(AudienceRequirements audience) {
        ApplicationIdentityProfile base = validProfile();
        return new ApplicationIdentityProfile(
                base.application(),
                audience,
                base.protocols(),
                base.provisioning(),
                base.security(),
                base.operations());
    }

    private static Set<String> issueCodes(ProfileValidationResult result) {
        return result.issues().stream()
                .map(ProfileIssue::code)
                .collect(java.util.stream.Collectors.toSet());
    }
}
