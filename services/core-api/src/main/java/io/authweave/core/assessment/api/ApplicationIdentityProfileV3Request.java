package io.authweave.core.assessment.api;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.UniqueElements;

import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;

public record ApplicationIdentityProfileV3Request(
        @NotNull @Valid ApplicationIdentityProfileRequest.ApplicationInput application,
        @NotNull @Valid ApplicationIdentityProfileRequest.AudienceInput audience,
        @NotNull @Valid ApplicationIdentityProfileRequest.ProtocolInput protocols,
        @NotNull @Valid ApplicationIdentityProfileRequest.ProvisioningInput provisioning,
        @NotNull @Valid SecurityInput security,
        @NotNull @Valid ApplicationIdentityProfileRequest.OperationsInput operations) {

    ApplicationIdentityProfile toDomain() {
        var baseline = new ApplicationIdentityProfileRequest(application, audience, protocols, provisioning,
                new ApplicationIdentityProfileRequest.SecurityInput(security.multiFactorAuthentication(),
                        security.browserTokenExposureMinimization(), security.auditability(), security.dataResidency(),
                        security.assurance(), security.complianceTargets()), operations).toDomain();
        return new ApplicationIdentityProfile(baseline.application(), baseline.audience(), baseline.protocols(),
                baseline.provisioning(), new SecurityRequirements(security.multiFactorAuthentication(),
                security.browserTokenExposureMinimization(), security.auditability(), security.dataResidency(),
                security.assurance(), Set.copyOf(security.complianceTargets()), security.dataResidencyDetails().toDomain(),
                security.authenticationControls().toDomain()),
                baseline.operations());
    }

    public record SecurityInput(
            @NotNull RequirementCriticality multiFactorAuthentication,
            @NotNull RequirementCriticality browserTokenExposureMinimization,
            @NotNull RequirementCriticality auditability,
            @NotNull RequirementCriticality dataResidency,
            @NotNull AssuranceLevel assurance,
            @NotNull @UniqueElements List<@NotNull ComplianceTarget> complianceTargets,
            @NotNull @Valid ApplicationIdentityProfileV2Request.ResidencyInput dataResidencyDetails,
            @NotNull @Valid AuthenticationControlsInput authenticationControls) { }

    public record AuthenticationControlsInput(
            @NotNull RequirementCriticality phishingResistance,
            @NotNull RequirementCriticality nonExportableKeys,
            @NotNull RequirementCriticality stepUpAuthentication) {
        AuthenticationControls toDomain() {
            return new AuthenticationControls(phishingResistance, nonExportableKeys, stepUpAuthentication);
        }
    }

}
