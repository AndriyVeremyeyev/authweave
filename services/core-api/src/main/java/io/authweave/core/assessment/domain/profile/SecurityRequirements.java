package io.authweave.core.assessment.domain.profile;

import java.util.Objects;
import java.util.Set;

public record SecurityRequirements(
        RequirementCriticality multiFactorAuthentication,
        RequirementCriticality browserTokenExposureMinimization,
        RequirementCriticality auditability,
        RequirementCriticality dataResidency,
        AssuranceLevel assurance,
        Set<ComplianceTarget> complianceTargets) {

    public SecurityRequirements {
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
