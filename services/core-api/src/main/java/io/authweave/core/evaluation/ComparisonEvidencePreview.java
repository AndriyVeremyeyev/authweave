package io.authweave.core.evaluation;

import java.util.Comparator;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;

/** The same synthetic catalog used by this comparison, not a published or verified provider baseline. */
public record ComparisonEvidencePreview(SyntheticComparison comparison, ProviderCatalog catalog) {
    public static final String POLICY_VERSION = "comparison-evidence-preview-1";
    public ComparisonEvidencePreview {
        Objects.requireNonNull(comparison); Objects.requireNonNull(catalog);
        if (catalog.kind() != ProviderCatalog.Kind.SYNTHETIC || comparison.catalogKind() != catalog.kind()
                || !catalog.catalogVersion().equals(comparison.catalogVersion())
                || !SyntheticComparison.AUDITABILITY_POLICY_VERSION.equals(comparison.policyVersion())
                || comparison.auditability() == null || comparison.rankingPerformed() || comparison.recommendationReady())
            throw new IllegalArgumentException("Use the exact synthetic comparison catalog");
        var options = catalog.options().stream().sorted(Comparator.comparing(ProviderCatalog.Option::id)).toList();
        if (options.size() != comparison.candidates().size()) throw new IllegalArgumentException("Unbound catalog options");
        for (int i = 0; i < options.size(); i++) {
            var option = options.get(i); var candidate = comparison.candidates().get(i);
            if (!option.id().equals(candidate.optionId()) || !option.displayName().equals(candidate.displayName())
                    || !option.plan().equals(candidate.plan()) || !option.region().equals(candidate.region())
                    || candidate.capabilityPreferences().stream().anyMatch(p -> !Objects.equals(p.evidence(), option.facts().get(p.capability()))))
                throw new IllegalArgumentException("Unbound catalog evidence");
        }
    }
    @JsonProperty public int schemaVersion() { return 1; }
    @JsonProperty public String scope() { return "SYNTHETIC_COMPARISON_EVIDENCE"; }
    @JsonProperty public String policyVersion() { return POLICY_VERSION; }
    @JsonProperty public String catalogSha256() { return CatalogDraftCanonicalizer.sha256(catalog); }
    @JsonProperty public boolean sourceVerificationPerformed() { return false; }
    @JsonProperty public boolean publicationReady() { return false; }
    @JsonProperty public boolean recommendationReady() { return false; }
    @JsonProperty public boolean writesPerformed() { return false; }
}
