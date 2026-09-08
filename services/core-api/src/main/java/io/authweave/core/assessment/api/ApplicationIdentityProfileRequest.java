package io.authweave.core.assessment.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.UniqueElements;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.OperationalConstraints;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.BudgetSensitivity;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.DeploymentTarget;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.HostingPreference;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.IdentityExpertise;
import io.authweave.core.assessment.domain.profile.ProtocolRequirements;
import io.authweave.core.assessment.domain.profile.ProtocolRequirements.FederationProtocol;
import io.authweave.core.assessment.domain.profile.ProvisioningRequirements;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.SecurityRequirements;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;

/** Wire input preserves duplicates until validation, before creating domain sets. */
public record ApplicationIdentityProfileRequest(
        @NotNull @Valid ApplicationInput application,
        @NotNull @Valid AudienceInput audience,
        @NotNull @Valid ProtocolInput protocols,
        @NotNull @Valid ProvisioningInput provisioning,
        @NotNull @Valid SecurityInput security,
        @NotNull @Valid OperationsInput operations) {

    ApplicationIdentityProfile toDomain() {
        return new ApplicationIdentityProfile(
                new ApplicationTopology(application.type(), Set.copyOf(application.clients())),
                new AudienceRequirements(Set.copyOf(audience.populations()),
                        audience.tenancy(), audience.membership()),
                new ProtocolRequirements(protocols.federation(), protocols.oauth2ProtectedApis(),
                        protocols.socialLogin(), protocols.enterpriseSingleSignOn()),
                new ProvisioningRequirements(provisioning.scim(),
                        provisioning.justInTimeProvisioning(), provisioning.groupSynchronization()),
                new SecurityRequirements(security.multiFactorAuthentication(),
                        security.browserTokenExposureMinimization(), security.auditability(),
                        security.dataResidency(), security.assurance(),
                        Set.copyOf(security.complianceTargets())),
                new OperationalConstraints(operations.hosting(), operations.deploymentTarget(),
                        operations.identityExpertise(), operations.budgetSensitivity()));
    }

    public record ApplicationInput(
            @NotNull ApplicationType type,
            @NotNull @UniqueElements List<@NotNull ClientType> clients) {
    }

    public record AudienceInput(
            @NotNull @UniqueElements List<@NotNull UserPopulation> populations,
            @NotNull TenancyModel tenancy,
            @NotNull MembershipModel membership) {
    }

    public record ProtocolInput(
            @NotNull Map<FederationProtocol, @NotNull RequirementCriticality> federation,
            @NotNull RequirementCriticality oauth2ProtectedApis,
            @NotNull RequirementCriticality socialLogin,
            @NotNull RequirementCriticality enterpriseSingleSignOn) {
    }

    public record ProvisioningInput(
            @NotNull RequirementCriticality scim,
            @NotNull RequirementCriticality justInTimeProvisioning,
            @NotNull RequirementCriticality groupSynchronization) {
    }

    public record SecurityInput(
            @NotNull RequirementCriticality multiFactorAuthentication,
            @NotNull RequirementCriticality browserTokenExposureMinimization,
            @NotNull RequirementCriticality auditability,
            @NotNull RequirementCriticality dataResidency,
            @NotNull AssuranceLevel assurance,
            @NotNull @UniqueElements List<@NotNull ComplianceTarget> complianceTargets) {
    }

    public record OperationsInput(
            @NotNull HostingPreference hosting,
            @NotNull DeploymentTarget deploymentTarget,
            @NotNull IdentityExpertise identityExpertise,
            @NotNull BudgetSensitivity budgetSensitivity) {
    }
}
