package io.authweave.core.assessment.domain.profile;

import java.util.Set;

/** At-rest placement only. An empty selection is unrecorded, not unrestricted. */
public record DataResidencyDetails(Set<String> allowedCountries, Set<DataCategory> dataCategories) {
    public DataResidencyDetails {
        allowedCountries = Set.copyOf(allowedCountries);
        dataCategories = Set.copyOf(dataCategories);
    }

    public enum DataCategory { USER_PROFILES, CREDENTIALS, AUDIT_LOGS, BACKUPS }

    public static DataResidencyDetails unknown() {
        return new DataResidencyDetails(Set.of(), Set.of());
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isUnrecorded() {
        return allowedCountries.isEmpty() && dataCategories.isEmpty();
    }

    /** Keep the original v1 representation when there is no additional information. */
    public static final class UnrecordedFilter {
        @Override public boolean equals(Object value) {
            return value instanceof DataResidencyDetails details && details.isUnrecorded();
        }
        @Override public int hashCode() { return 0; }
    }
}
