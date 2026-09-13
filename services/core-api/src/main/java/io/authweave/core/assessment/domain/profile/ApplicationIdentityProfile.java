package io.authweave.core.assessment.domain.profile;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnore;

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

    @JsonIgnore
    public short minimumSchemaVersion() {
        return operations.usagePlanning().isUnrecorded() ? security.minimumSchemaVersion() : 5;
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
