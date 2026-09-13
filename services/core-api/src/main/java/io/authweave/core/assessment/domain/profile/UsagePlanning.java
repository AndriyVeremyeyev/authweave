package io.authweave.core.assessment.domain.profile;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Owner-supplied planning inputs, not verified observations or provider billing units. */
public record UsagePlanning(String scopeDescription, List<String> assumptions, Map<Metric, Quantity> volumes) {
    public UsagePlanning {
        Objects.requireNonNull(scopeDescription);
        assumptions = List.copyOf(assumptions);
        volumes = Map.copyOf(volumes);
        if (scopeDescription.length() > 500 || assumptions.size() > 10
                || new HashSet<>(assumptions).size() != assumptions.size()
                || assumptions.stream().anyMatch(text -> text.isBlank() || text.length() > 500)) {
            throw new IllegalArgumentException("Planning context and assumptions must be bounded, with distinct nonblank assumptions");
        }
    }

    public static UsagePlanning unknown() { return new UsagePlanning("", List.of(), Map.of()); }

    @JsonIgnore
    public boolean isUnrecorded() { return scopeDescription.isEmpty() && assumptions.isEmpty() && volumes.isEmpty(); }

    public record Quantity(Basis basis, Long value) {
        public Quantity {
            Objects.requireNonNull(basis);
            Objects.requireNonNull(value);
            if (value < 0 || value > 9007199254740991L) {
                throw new IllegalArgumentException("Usage must be a non-negative JavaScript-safe integer");
            }
        }
    }

    public enum Basis { ASSUMED, OBSERVED }
    public enum Unit { USERS_PER_MONTH, CONFIGURED_CONNECTIONS, TOKEN_ISSUANCES_PER_MONTH, LOGINS_PER_SECOND }
    public enum Metric {
        MONTHLY_ACTIVE_USERS(Unit.USERS_PER_MONTH,
                "Distinct human users authenticating during one planning month, not registered accounts or login count."),
        ENTERPRISE_SSO_CONNECTIONS(Unit.CONFIGURED_CONNECTIONS,
                "Configured upstream enterprise IdP connections in the stated scope, not organization count."),
        MONTHLY_M2M_TOKEN_ISSUANCES(Unit.TOKEN_ISSUANCES_PER_MONTH,
                "Machine-to-machine access-token issuances during one planning month, not downstream API requests."),
        PEAK_HUMAN_LOGINS_PER_SECOND(Unit.LOGINS_PER_SECOND,
                "Successful human logins during the busiest one-second interval, not monthly active users or machine tokens.");

        private final Unit unit;
        private final String definition;
        Metric(Unit unit, String definition) { this.unit = unit; this.definition = definition; }
        public Unit unit() { return unit; }
        public String definition() { return definition; }
    }

    public static final class UnrecordedFilter {
        @Override public boolean equals(Object value) { return value instanceof UsagePlanning planning && planning.isUnrecorded(); }
        @Override public int hashCode() { return 0; }
    }
}
