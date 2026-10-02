package io.authweave.core.catalog;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.ProviderCatalog.Evidence;
import io.authweave.core.catalog.ProviderCatalog.EvidenceStatus;
import io.authweave.core.catalog.ProviderCatalog.Support;

/** Separate synthetic capability fixtures, not an extension of catalog v4 or observed log records. */
public final class AuditabilityFacts {
    public static final int MAX_RETENTION_DAYS = AuditabilityRequirements.MAX_RETENTION_DAYS;
    private AuditabilityFacts() { }

    public enum Emitter { IDENTITY_PROVIDER, APPLICATION }

    /** Labels are exact bindings, never region/configuration menus or wildcard scopes. */
    public record Scope(String optionId, String plan, String region, String configuration) {
        public Scope {
            if (optionId == null || !optionId.matches("[a-z0-9][a-z0-9.-]{0,99}")) {
                throw new IllegalArgumentException("Invalid auditability option scope");
            }
            for (String label : new String[] { plan, region, configuration }) {
                if (label == null || label.isBlank() || label.length() > 120 || !label.equals(label.strip())
                        || label.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Use explicit bounded auditability scope labels");
                }
            }
        }
    }

    /** The duration is a documented minimum for this exact option, not a configurable maximum,
     * an external sink's retention, source freshness, or proof of deployed retention. */
    public record Fact(Scope scope, Emitter emitter, Criterion criterion, Support support,
            Integer documentedMinimumRetentionDays, EvidenceStatus evidenceStatus, URI sourceUrl,
            Instant observedAt) implements Evidence {
        public Fact {
            Objects.requireNonNull(scope); Objects.requireNonNull(emitter);
            Objects.requireNonNull(criterion); Objects.requireNonNull(support);
            if (documentedMinimumRetentionDays != null && (criterion != Criterion.AUDIT_LOG_RETENTION
                    || support != Support.SUPPORTED || documentedMinimumRetentionDays < 0
                    || documentedMinimumRetentionDays > MAX_RETENTION_DAYS)) {
                throw new IllegalArgumentException("Inconsistent documented retention duration");
            }
            ProviderCatalog.validateEvidence(evidenceStatus, sourceUrl, observedAt);
        }
    }
}
