package io.authweave.core.assessment.domain.profile;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Independent human-authentication requirements, not a certification or an AAL mapping. */
public record AuthenticationControls(RequirementCriticality phishingResistance,
        RequirementCriticality nonExportableKeys, RequirementCriticality stepUpAuthentication) {
    public AuthenticationControls {
        Objects.requireNonNull(phishingResistance);
        Objects.requireNonNull(nonExportableKeys);
        Objects.requireNonNull(stepUpAuthentication);
    }

    public static AuthenticationControls unknown() {
        return new AuthenticationControls(RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN, RequirementCriticality.UNKNOWN);
    }

    @JsonIgnore
    public boolean isUnrecorded() {
        return equals(unknown());
    }

    public static final class UnrecordedFilter {
        @Override public boolean equals(Object value) {
            return value instanceof AuthenticationControls controls && controls.isUnrecorded();
        }
        @Override public int hashCode() { return 0; }
    }
}
