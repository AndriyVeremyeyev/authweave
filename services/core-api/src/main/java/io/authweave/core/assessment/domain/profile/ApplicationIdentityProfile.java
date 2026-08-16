package io.authweave.core.assessment.domain.profile;

import java.util.Objects;

public record ApplicationIdentityProfile(
        ApplicationTopology application,
        AudienceRequirements audience,
        ProtocolRequirements protocols,
        ProvisioningRequirements provisioning,
        SecurityRequirements security,
        OperationalConstraints operations) {

    public ApplicationIdentityProfile {
        Objects.requireNonNull(application, "application must not be null");
        Objects.requireNonNull(audience, "audience must not be null");
        Objects.requireNonNull(protocols, "protocols must not be null");
        Objects.requireNonNull(provisioning, "provisioning must not be null");
        Objects.requireNonNull(security, "security must not be null");
        Objects.requireNonNull(operations, "operations must not be null");
    }

    public static ApplicationIdentityProfile unknown() {
        return new ApplicationIdentityProfile(
                ApplicationTopology.unknown(),
                AudienceRequirements.unknown(),
                ProtocolRequirements.unknown(),
                ProvisioningRequirements.unknown(),
                SecurityRequirements.unknown(),
                OperationalConstraints.unknown());
    }
}
