package io.authweave.core.catalog;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import io.authweave.core.catalog.AuditabilityFacts.Emitter;
import io.authweave.core.catalog.AuditabilityFacts.Fact;
import io.authweave.core.catalog.AuditabilityFacts.Scope;

/** Versioned synthetic sidecar. Never a published catalog, configuration inventory or trust upgrade. */
public record AuditabilityCatalog(int schemaVersion, String evidenceVersion, String baseCatalogVersion,
        ProviderCatalog.Kind kind, List<ScopedOption> options) {
    public AuditabilityCatalog {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported auditability evidence version");
        for (var version : new String[] { evidenceVersion, baseCatalogVersion }) {
            if (version == null || !version.matches("[a-z0-9][a-z0-9.-]{0,99}"))
                throw new IllegalArgumentException("Invalid auditability catalog version");
        }
        Objects.requireNonNull(kind);
        options = List.copyOf(options);
        if (options.isEmpty() || options.size() > 100) throw new IllegalArgumentException("Invalid auditability scope count");
        var scopes = new HashSet<Scope>();
        for (var option : options) if (!scopes.add(option.scope())) throw new IllegalArgumentException("Duplicate auditability scope");
        options = options.stream().sorted(Comparator.comparing((ScopedOption option) -> option.scope().optionId())
                .thenComparing(option -> option.scope().configuration())).toList();
    }

    public record ScopedOption(Scope scope, List<Fact> facts) {
        public ScopedOption {
            Objects.requireNonNull(scope); facts = List.copyOf(facts);
            if (facts.size() > 6) throw new IllegalArgumentException("Too many auditability facts");
            var criteria = new HashSet<io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion>();
            for (var fact : facts) {
                if (!scope.equals(fact.scope()) || fact.emitter() != Emitter.IDENTITY_PROVIDER || !criteria.add(fact.criterion()))
                    throw new IllegalArgumentException("Use unique identity-provider facts in the exact enclosing scope");
            }
            facts = facts.stream().sorted(Comparator.comparing(Fact::criterion)).toList();
        }
    }

    /** An absent scoped fact is allowed; an absent/foreign option binding is not silently invented. */
    public void validateBase(ProviderCatalog base) {
        if (!baseCatalogVersion.equals(base.catalogVersion()) || kind != base.kind())
            throw new IllegalArgumentException("Auditability evidence is bound to a different base catalog");
        var bound = new HashSet<String>();
        for (var option : options) {
            var scope = option.scope();
            var target = base.options().stream().filter(candidate -> candidate.id().equals(scope.optionId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown auditability option"));
            if (!target.plan().equals(scope.plan()) || !target.region().equals(scope.region()))
                throw new IllegalArgumentException("Auditability evidence plan/region do not match the base option");
            bound.add(target.id());
        }
        if (bound.size() != base.options().size()) throw new IllegalArgumentException("Every base option needs an explicit auditability scope");
    }
}
