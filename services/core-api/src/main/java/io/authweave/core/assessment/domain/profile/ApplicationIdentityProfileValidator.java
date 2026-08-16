package io.authweave.core.assessment.domain.profile;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.ProtocolRequirements.FederationProtocol;

public final class ApplicationIdentityProfileValidator {

    private static final Set<UserPopulation> WORKFORCE_POPULATIONS = EnumSet.of(
            UserPopulation.EMPLOYEES,
            UserPopulation.CONTRACTORS,
            UserPopulation.INTERNAL_OPERATORS);

    private ApplicationIdentityProfileValidator() {
    }

    public static ProfileValidationResult validate(ApplicationIdentityProfile profile) {
        List<ProfileIssue> issues = new ArrayList<>();
        validateMinimumContext(profile, issues);
        validateTenancy(profile.audience(), issues);
        validateSocialLogin(profile, issues);
        validateEnterpriseSingleSignOn(profile.protocols(), issues);
        return new ProfileValidationResult(issues);
    }

    private static void validateMinimumContext(
            ApplicationIdentityProfile profile,
            List<ProfileIssue> issues) {
        if (profile.application().type() == ApplicationType.UNKNOWN) {
            issues.add(missing(
                    "application_type_required",
                    "application.type",
                    "Choose the application type before evaluation."));
        }
        if (profile.application().clients().isEmpty()) {
            issues.add(missing(
                    "client_type_required",
                    "application.clients",
                    "Choose at least one client type before evaluation."));
        }

        boolean hasHumanFacingClient = profile.application().clients().contains(ClientType.BROWSER)
                || profile.application().clients().contains(ClientType.NATIVE_MOBILE);
        if (hasHumanFacingClient && profile.audience().populations().isEmpty()) {
            issues.add(missing(
                    "user_population_required",
                    "audience.populations",
                    "Choose at least one user population for a human-facing client."));
        }
        if (hasHumanFacingClient && profile.audience().tenancy() == TenancyModel.UNKNOWN) {
            issues.add(missing(
                    "tenancy_model_required",
                    "audience.tenancy",
                    "Choose the organization tenancy model before evaluation."));
        }
        if (hasHumanFacingClient && profile.audience().membership() == MembershipModel.UNKNOWN) {
            issues.add(missing(
                    "membership_model_required",
                    "audience.membership",
                    "Choose the organization membership model before evaluation."));
        }
    }

    private static void validateTenancy(
            AudienceRequirements audience,
            List<ProfileIssue> issues) {
        TenancyModel tenancy = audience.tenancy();
        MembershipModel membership = audience.membership();

        if (tenancy == TenancyModel.NO_ORGANIZATION_BOUNDARY
                && membership != MembershipModel.NOT_APPLICABLE
                && membership != MembershipModel.UNKNOWN) {
            issues.add(contradiction(
                    "organization_membership_without_organization_boundary",
                    "audience.membership",
                    "Organization membership cannot be used without an organization boundary."));
        } else if (membership == MembershipModel.MULTIPLE_ORGANIZATIONS_PER_USER
                && tenancy != TenancyModel.MULTI_TENANT_ORGANIZATIONS
                && tenancy != TenancyModel.UNKNOWN) {
            issues.add(contradiction(
                    "multi_organization_membership_requires_multi_tenancy",
                    "audience.tenancy",
                    "Multiple organization memberships require multi-tenant organizations."));
        } else if (tenancy == TenancyModel.MULTI_TENANT_ORGANIZATIONS
                && membership == MembershipModel.NOT_APPLICABLE) {
            issues.add(contradiction(
                    "multi_tenancy_requires_membership_model",
                    "audience.membership",
                    "Multi-tenant organizations require an organization membership model."));
        }
    }

    private static void validateSocialLogin(
            ApplicationIdentityProfile profile,
            List<ProfileIssue> issues) {
        Set<UserPopulation> populations = profile.audience().populations();
        if (profile.protocols().socialLogin() == RequirementCriticality.REQUIRED
                && !populations.isEmpty()
                && WORKFORCE_POPULATIONS.containsAll(populations)) {
            issues.add(contradiction(
                    "social_login_conflicts_with_workforce_only_population",
                    "protocols.socialLogin",
                    "Required social login conflicts with a workforce-only population."));
        }
    }

    private static void validateEnterpriseSingleSignOn(
            ProtocolRequirements protocols,
            List<ProfileIssue> issues) {
        if (protocols.enterpriseSingleSignOn() == RequirementCriticality.REQUIRED
                && protocols.criticalityOf(FederationProtocol.OIDC)
                        == RequirementCriticality.NOT_REQUIRED
                && protocols.criticalityOf(FederationProtocol.SAML)
                        == RequirementCriticality.NOT_REQUIRED) {
            issues.add(contradiction(
                    "enterprise_sso_requires_federation_protocol",
                    "protocols.federation",
                    "Required enterprise SSO needs OIDC or SAML federation."));
        }
    }

    private static ProfileIssue missing(String code, String path, String message) {
        return new ProfileIssue(code, path, ProfileIssueType.MISSING_INFORMATION, message);
    }

    private static ProfileIssue contradiction(String code, String path, String message) {
        return new ProfileIssue(code, path, ProfileIssueType.CONTRADICTION, message);
    }
}
