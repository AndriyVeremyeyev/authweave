package io.authweave.core.catalog.draft;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.ProviderCatalog.Support;

/** Untrusted supplement to an exact draft v1, never a reviewed synthetic sidecar or publication. */
public record AuditabilityCatalogDraft(int schemaVersion, Kind kind, String evidenceVersion,
        String baseCatalogVersion, String baseContentSha256, List<ScopedOption> options) {
    public enum Kind { AUDITABILITY_CATALOG_DRAFT }
    public enum Emitter { IDENTITY_PROVIDER }

    public AuditabilityCatalogDraft {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported auditability draft version");
        Objects.requireNonNull(kind);
        identifier(evidenceVersion); identifier(baseCatalogVersion); digest(baseContentSha256);
        options = List.copyOf(options);
        if (options.isEmpty() || options.size() > 100) throw new IllegalArgumentException("Invalid auditability draft option count");
        var ids = new HashSet<String>();
        for (var option : options) if (!ids.add(option.scope().optionId()))
            throw new IllegalArgumentException("Use one explicit auditability scope per base option");
        options = options.stream().sorted(Comparator.comparing(o -> o.scope().optionId())).toList();
    }

    public record Scope(String optionId, String plan, String region, String configuration) {
        public Scope {
            identifier(optionId);
            // The exact draft labels are preserved; no wildcard, normalization or scope inference.
            ProviderCatalogDraft.text(plan, 120); ProviderCatalogDraft.text(region, 120);
            ProviderCatalogDraft.text(configuration, 120);
        }
        public static Scope of(ProviderCatalogDraft.Option option) {
            return new Scope(option.id(), option.plan(), option.region(), option.configuration());
        }
    }

    public record ScopedOption(Scope scope, List<Fact> facts) {
        public ScopedOption {
            Objects.requireNonNull(scope); facts = List.copyOf(facts);
            if (facts.size() > 6) throw new IllegalArgumentException("Too many auditability draft facts");
            var criteria = new HashSet<Criterion>();
            for (var fact : facts) if (!criteria.add(fact.criterion()))
                throw new IllegalArgumentException("Duplicate auditability criterion in one scope");
            facts = facts.stream().sorted(Comparator.comparing(Fact::criterion)).toList();
        }
    }

    public record Fact(Emitter emitter, Criterion criterion, Support support,
            @JsonProperty(required = true) Integer documentedMinimumRetentionDays,
            List<String> conditions, ProviderCatalogDraft.Evidence evidence) {
        public Fact {
            Objects.requireNonNull(emitter); Objects.requireNonNull(criterion); Objects.requireNonNull(support);
            if (documentedMinimumRetentionDays != null && (criterion != Criterion.AUDIT_LOG_RETENTION
                    || support != Support.SUPPORTED || documentedMinimumRetentionDays < 0
                    || documentedMinimumRetentionDays > AuditabilityRequirements.MAX_RETENTION_DAYS))
                throw new IllegalArgumentException("Only supported retention can record a bounded documented minimum");
            conditions = List.copyOf(conditions); Objects.requireNonNull(evidence);
            if (conditions.size() > 10 || new HashSet<>(conditions).size() != conditions.size())
                throw new IllegalArgumentException("Use at most ten unique auditability conditions");
            conditions.forEach(c -> ProviderCatalogDraft.text(c, 500));
            conditions = conditions.stream().sorted().toList();
        }
    }

    static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Use a SHA-256 content digest");
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid auditability draft identifier");
    }
}
