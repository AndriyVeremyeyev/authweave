package io.authweave.core.assessment.domain.profile;

import java.util.Objects;

public record ProvisioningRequirements(
        RequirementCriticality scim,
        RequirementCriticality justInTimeProvisioning,
        RequirementCriticality groupSynchronization) {

    public ProvisioningRequirements {
        Objects.requireNonNull(scim, "scim must not be null");
        Objects.requireNonNull(
                justInTimeProvisioning,
                "justInTimeProvisioning must not be null");
        Objects.requireNonNull(
                groupSynchronization,
                "groupSynchronization must not be null");
    }

    public static ProvisioningRequirements unknown() {
        return new ProvisioningRequirements(
                RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN);
    }
}
