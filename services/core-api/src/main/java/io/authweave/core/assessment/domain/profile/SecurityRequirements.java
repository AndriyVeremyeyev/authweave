package io.authweave.core.assessment.domain.profile;

import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record SecurityRequirements(
        RequirementCriticality multiFactorAuthentication,
        RequirementCriticality browserTokenExposureMinimization,
        RequirementCriticality auditability,
        RequirementCriticality dataResidency,
        AssuranceLevel assurance,
        Set<ComplianceTarget> complianceTargets,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = DataResidencyDetails.UnrecordedFilter.class)
        DataResidencyDetails dataResidencyDetails,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = AuthenticationControls.UnrecordedFilter.class)
        AuthenticationControls authenticationControls,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = ComplianceScopeStatus.UnknownFilter.class)
        ComplianceScopeStatus complianceScopeStatus) {

    public SecurityRequirements {
        // Legacy v1 JSON has no details. The v2 wire input and versioned codec require this field.
        if (dataResidencyDetails == null) dataResidencyDetails = DataResidencyDetails.unknown();
        // Legacy v1/v2 JSON has no controls; v3 input and the codec enforce their presence.
        if (authenticationControls == null) authenticationControls = AuthenticationControls.unknown();
        // Older formats record targets but not whether their scope was explicitly established.
        if (complianceScopeStatus == null) complianceScopeStatus = ComplianceScopeStatus.UNKNOWN;
        Objects.requireNonNull(
                multiFactorAuthentication,
                "multiFactorAuthentication must not be null");
        Objects.requireNonNull(
                browserTokenExposureMinimization,
                "browserTokenExposureMinimization must not be null");
        Objects.requireNonNull(auditability, "auditability must not be null");
        Objects.requireNonNull(dataResidency, "dataResidency must not be null");
        Objects.requireNonNull(assurance, "assurance must not be null");
        complianceTargets = Set.copyOf(
                Objects.requireNonNull(
                        complianceTargets,
                        "complianceTargets must not be null"));
    }

    public SecurityRequirements(RequirementCriticality multiFactorAuthentication,
            RequirementCriticality browserTokenExposureMinimization, RequirementCriticality auditability,
            RequirementCriticality dataResidency, AssuranceLevel assurance, Set<ComplianceTarget> complianceTargets,
            DataResidencyDetails dataResidencyDetails, AuthenticationControls authenticationControls) {
        this(multiFactorAuthentication, browserTokenExposureMinimization, auditability, dataResidency,
                assurance, complianceTargets, dataResidencyDetails, authenticationControls, ComplianceScopeStatus.UNKNOWN);
    }

    public SecurityRequirements(RequirementCriticality multiFactorAuthentication,
            RequirementCriticality browserTokenExposureMinimization, RequirementCriticality auditability,
            RequirementCriticality dataResidency, AssuranceLevel assurance, Set<ComplianceTarget> complianceTargets,
            DataResidencyDetails dataResidencyDetails) {
        this(multiFactorAuthentication, browserTokenExposureMinimization, auditability, dataResidency,
                assurance, complianceTargets, dataResidencyDetails, AuthenticationControls.unknown());
    }

    @JsonIgnore
    public short minimumSchemaVersion() {
        if (complianceScopeStatus != ComplianceScopeStatus.UNKNOWN) return 4;
        return (short) (!authenticationControls.isUnrecorded() ? 3 : dataResidencyDetails.isUnrecorded() ? 1 : 2);
    }

    public SecurityRequirements(RequirementCriticality multiFactorAuthentication,
            RequirementCriticality browserTokenExposureMinimization, RequirementCriticality auditability,
            RequirementCriticality dataResidency, AssuranceLevel assurance, Set<ComplianceTarget> complianceTargets) {
        this(multiFactorAuthentication, browserTokenExposureMinimization, auditability, dataResidency,
                assurance, complianceTargets, DataResidencyDetails.unknown());
    }

    public static SecurityRequirements unknown() {
        return new SecurityRequirements(
                RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN,
                AssuranceLevel.UNKNOWN,
                Set.of());
    }

    public enum AssuranceLevel {
        BASELINE,
        ELEVATED,
        HIGH,
        UNKNOWN
    }

    public enum ComplianceTarget {
        SOC_2,
        ISO_27001,
        HIPAA,
        FEDRAMP,
        GDPR,
        OTHER
    }
}
