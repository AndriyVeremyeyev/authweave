package io.authweave.core.assessment.api;

import java.util.List;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;

public record ApplicationIdentityProfileV6Request(
        @NotNull @Valid ApplicationIdentityProfileRequest.ApplicationInput application,
        @NotNull @Valid ApplicationIdentityProfileRequest.AudienceInput audience,
        @NotNull @Valid ApplicationIdentityProfileRequest.ProtocolInput protocols,
        @NotNull @Valid ApplicationIdentityProfileRequest.ProvisioningInput provisioning,
        @NotNull @Valid SecurityInput security,
        @NotNull @Valid ApplicationIdentityProfileV5Request.OperationsInput operations) {

    ApplicationIdentityProfile toDomain() {
        var base = new ApplicationIdentityProfileV5Request(application, audience, protocols, provisioning,
                new ApplicationIdentityProfileV4Request.SecurityInput(security.multiFactorAuthentication(),
                        security.browserTokenExposureMinimization(), security.auditability(), security.dataResidency(),
                        security.assurance(), security.complianceTargets(), security.dataResidencyDetails(),
                        security.authenticationControls(), security.complianceScopeStatus()), operations).toDomain();
        var s = base.security();
        return new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), s.auditability(),
                        s.dataResidency(), s.assurance(), s.complianceTargets(), s.dataResidencyDetails(),
                        s.authenticationControls(), s.complianceScopeStatus(), security.auditabilityRequirements().toDomain()),
                base.operations());
    }

    public record SecurityInput(@NotNull RequirementCriticality multiFactorAuthentication,
            @NotNull RequirementCriticality browserTokenExposureMinimization, @NotNull RequirementCriticality auditability,
            @NotNull RequirementCriticality dataResidency, @NotNull SecurityRequirements.AssuranceLevel assurance,
            @NotNull @UniqueElements List<@NotNull ComplianceTarget> complianceTargets,
            @NotNull @Valid ApplicationIdentityProfileV2Request.ResidencyInput dataResidencyDetails,
            @NotNull @Valid ApplicationIdentityProfileV3Request.AuthenticationControlsInput authenticationControls,
            @NotNull ComplianceScopeStatus complianceScopeStatus,
            @NotNull @Valid AuditabilityInput auditabilityRequirements) { }

    public record AuditabilityInput(@NotNull @Size(max = 6) @UniqueElements List<@NotNull Criterion> selectedCriteria,
            @JsonProperty(required = true) @Min(1) @Max(AuditabilityRequirements.MAX_RETENTION_DAYS) Integer minimumRetentionDays) {
        public AuditabilityInput {
            if (selectedCriteria != null && (selectedCriteria.contains(Criterion.AUDIT_LOG_RETENTION)
                    ? minimumRetentionDays == null : minimumRetentionDays != null)) {
                throw new IllegalArgumentException("Retention must be explicitly bound to its selected criterion");
            }
        }
        AuditabilityRequirements toDomain() { return new AuditabilityRequirements(Set.copyOf(selectedCriteria), minimumRetentionDays); }
    }
}
