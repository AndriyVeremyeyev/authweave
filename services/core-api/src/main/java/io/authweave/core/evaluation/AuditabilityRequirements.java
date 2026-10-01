package io.authweave.core.evaluation;

import java.util.Set;
import io.authweave.core.catalog.AuditabilityFacts.Criterion;
import static io.authweave.core.catalog.AuditabilityFacts.MAX_RETENTION_DAYS;

/** Explicit requested dimensions only. Empty selection is unresolved scope, not no audit requirement.
 * These inputs are not yet persisted in the canonical assessment profile. */
public record AuditabilityRequirements(Set<Criterion> selectedCriteria, Integer minimumRetentionDays) {
    public AuditabilityRequirements {
        selectedCriteria = Set.copyOf(selectedCriteria);
        if (selectedCriteria.contains(Criterion.AUDIT_LOG_RETENTION)
                ? minimumRetentionDays == null || minimumRetentionDays < 1 || minimumRetentionDays > MAX_RETENTION_DAYS
                : minimumRetentionDays != null) {
            throw new IllegalArgumentException("Selected retention requires an explicit bounded minimum in days");
        }
    }

    public static AuditabilityRequirements unspecified() { return new AuditabilityRequirements(Set.of(), null); }
}
