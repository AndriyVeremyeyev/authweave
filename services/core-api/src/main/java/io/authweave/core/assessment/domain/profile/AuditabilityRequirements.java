package io.authweave.core.assessment.domain.profile;

import java.util.Set;
import java.util.EnumSet;
import java.util.Collections;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Explicit requested dimensions only. Empty selection is unresolved scope, not no audit requirement.
 * Empty legacy inputs remain unknown, not a verified logging configuration. */
public record AuditabilityRequirements(Set<Criterion> selectedCriteria,
        @JsonProperty(required = true) Integer minimumRetentionDays) {
    public static final int MAX_RETENTION_DAYS = 36500;
    public enum Criterion {
        AUTHENTICATION_SUCCESS_EVENTS, AUTHENTICATION_FAILURE_EVENTS,
        ADMINISTRATIVE_CHANGE_EVENTS, PROVISIONING_CHANGE_EVENTS, AUDIT_LOG_EXPORT, AUDIT_LOG_RETENTION
    }
    public AuditabilityRequirements {
        // Stable new-format JSON across insertion orders and JVMs; never reorder legacy snapshots.
        var canonical = EnumSet.noneOf(Criterion.class);
        canonical.addAll(Set.copyOf(selectedCriteria));
        selectedCriteria = Collections.unmodifiableSet(canonical);
        if (selectedCriteria.contains(Criterion.AUDIT_LOG_RETENTION)
                ? minimumRetentionDays == null || minimumRetentionDays < 1 || minimumRetentionDays > MAX_RETENTION_DAYS
                : minimumRetentionDays != null) {
            throw new IllegalArgumentException("Selected retention requires an explicit bounded minimum in days");
        }
    }

    public static AuditabilityRequirements unspecified() { return new AuditabilityRequirements(Set.of(), null); }

    @JsonIgnore public boolean isUnrecorded() { return selectedCriteria.isEmpty(); }
    public static final class UnrecordedFilter {
        @Override public boolean equals(Object value) {
            return value instanceof AuditabilityRequirements requirements && requirements.isUnrecorded();
        }
        @Override public int hashCode() { return 0; }
    }
}
