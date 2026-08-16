package io.authweave.core.assessment.domain.profile;

import java.util.Objects;
import java.util.Set;

public record ApplicationTopology(ApplicationType type, Set<ClientType> clients) {

    public ApplicationTopology {
        Objects.requireNonNull(type, "type must not be null");
        clients = Set.copyOf(Objects.requireNonNull(clients, "clients must not be null"));
    }

    public static ApplicationTopology unknown() {
        return new ApplicationTopology(ApplicationType.UNKNOWN, Set.of());
    }

    public enum ApplicationType {
        B2B_SAAS,
        PARTNER_PORTAL,
        PUBLIC_SECTOR_PORTAL,
        INTERNAL_WORKFORCE,
        OTHER,
        UNKNOWN
    }

    public enum ClientType {
        BROWSER,
        NATIVE_MOBILE,
        MACHINE_TO_MACHINE
    }
}
