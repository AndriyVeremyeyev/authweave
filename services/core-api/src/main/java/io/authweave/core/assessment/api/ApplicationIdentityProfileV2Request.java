package io.authweave.core.assessment.api;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;

public record ApplicationIdentityProfileV2Request(
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
                security.assurance(), Set.copyOf(security.complianceTargets()), security.dataResidencyDetails().toDomain()),
                baseline.operations());
    }

    public record SecurityInput(
            @NotNull RequirementCriticality multiFactorAuthentication,
            @NotNull RequirementCriticality browserTokenExposureMinimization,
            @NotNull RequirementCriticality auditability,
            @NotNull RequirementCriticality dataResidency,
            @NotNull AssuranceLevel assurance,
            @NotNull @UniqueElements List<@NotNull ComplianceTarget> complianceTargets,
            @NotNull @Valid ResidencyInput dataResidencyDetails) { }

    public record ResidencyInput(
            @NotNull @UniqueElements @Size(max = 249) List<@NotNull @Pattern(regexp = "[A-Z]{2}") String> allowedCountries,
            @NotNull @UniqueElements List<@NotNull DataCategory> dataCategories) {
        DataResidencyDetails toDomain() {
            return new DataResidencyDetails(Set.copyOf(allowedCountries), Set.copyOf(dataCategories));
        }
    }
}
