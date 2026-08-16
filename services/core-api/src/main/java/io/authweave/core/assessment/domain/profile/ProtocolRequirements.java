package io.authweave.core.assessment.domain.profile;

import java.util.Map;
import java.util.Objects;

public record ProtocolRequirements(
        Map<FederationProtocol, RequirementCriticality> federation,
        RequirementCriticality oauth2ProtectedApis,
        RequirementCriticality socialLogin,
        RequirementCriticality enterpriseSingleSignOn) {

    public ProtocolRequirements {
        federation = Map.copyOf(
                Objects.requireNonNull(federation, "federation must not be null"));
        Objects.requireNonNull(
                oauth2ProtectedApis,
                "oauth2ProtectedApis must not be null");
        Objects.requireNonNull(socialLogin, "socialLogin must not be null");
        Objects.requireNonNull(
                enterpriseSingleSignOn,
                "enterpriseSingleSignOn must not be null");
    }

    public static ProtocolRequirements unknown() {
        return new ProtocolRequirements(
                Map.of(),
                RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN);
    }

    public RequirementCriticality criticalityOf(FederationProtocol protocol) {
        return federation.getOrDefault(
                Objects.requireNonNull(protocol, "protocol must not be null"),
                RequirementCriticality.UNKNOWN);
    }

    public enum FederationProtocol {
        OIDC,
        SAML
    }
}
