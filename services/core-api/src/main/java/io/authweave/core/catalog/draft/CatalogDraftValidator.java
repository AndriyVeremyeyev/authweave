package io.authweave.core.catalog.draft;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;
import io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage;
import io.authweave.core.catalog.ProviderCatalog.Support;
import io.authweave.core.evaluation.EvidencePolicy;
import io.authweave.core.catalog.draft.CatalogDraftValidation.*;

/** Stateless local dry run. Deliberately has no repository, publisher, source fetcher or evaluator dependency. */
@Component
public final class CatalogDraftValidator {
    public static final String POLICY_VERSION = "catalog-draft-validation-1";
    private final Clock clock;
    public CatalogDraftValidator(Clock clock) { this.clock = clock; }

    public CatalogDraftValidation validate(ProviderCatalogDraft draft) {
        return validateAt(draft, clock.instant());
    }

    CatalogDraftValidation validateAt(ProviderCatalogDraft draft, Instant at) {
        var issues = new ArrayList<Issue>();
        var facts = new ArrayList<FactReview>();
        var ids = new HashSet<String>();
        var scopes = new HashSet<List<String>>();
        var options = draft.options().stream().sorted(Comparator.comparing(ProviderCatalogDraft.Option::id)
                .thenComparing(CatalogDraftCanonicalizer::json)).toList();
        for (var option : options) {
            if (!ids.add(option.id())) issues.add(new Issue(option.id(), "id", IssueCode.DUPLICATE_OPTION_ID, "Option identifiers must be unique."));
            if (!scopes.add(List.of(option.providerId(), option.product(), option.plan(), option.deployment().name(), option.region(), option.configuration()))) {
                issues.add(new Issue(option.id(), "scope", IssueCode.DUPLICATE_OPTION_SCOPE, "This product, plan, deployment, region and configuration scope occurs more than once."));
            }
            var entries = CatalogDraftFacts.entries(option);
            if (entries.isEmpty()) issues.add(new Issue(option.id(), "facts", IssueCode.NO_FACTS_RECORDED, "Record at least one proposed fact for this option; omitted facts remain unknown."));
            entries.forEach((path, fact) -> {
                if (fact instanceof ProviderCatalogDraft.ResidencyFact residency) {
                    if (!Set.of(Locale.getISOCountries()).containsAll(residency.storageCountries())) {
                        issues.add(new Issue(option.id(), path, IssueCode.INVALID_COUNTRY, "Use recognized ISO 3166-1 alpha-2 country codes."));
                    }
                    if ((residency.coverage() == ResidencyCoverage.UNKNOWN) != residency.storageCountries().isEmpty()) {
                        issues.add(new Issue(option.id(), path, IssueCode.RESIDENCY_COVERAGE_INCONSISTENT, "Unknown coverage requires no destinations; complete or partial coverage requires destinations."));
                    }
                }
                if (fact instanceof ProviderCatalogDraft.AuthenticationFact control
                        && control.enforcement() == Support.SUPPORTED && control.availability() != Support.SUPPORTED) {
                    issues.add(new Issue(option.id(), path, IssueCode.AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY,
                            "A control cannot be enforceable when its availability is unsupported or unknown."));
                }
                var observed = fact.evidence().observedAt();
                var freshness = observed.isAfter(at) ? Freshness.FUTURE
                        : observed.isBefore(at.minus(EvidencePolicy.MAX_AGE)) ? Freshness.STALE : Freshness.CURRENT;
                facts.add(new FactReview(option.id(), path, ReviewStatus.UNREVIEWED, freshness,
                        fact.conditions().stream().sorted().toList(), fact.evidence()));
            });
        }
        issues.sort(Comparator.comparing(Issue::optionId).thenComparing(Issue::path).thenComparing(issue -> issue.code().name()));
        facts.sort(Comparator.comparing(FactReview::optionId).thenComparing(FactReview::path));
        return new CatalogDraftValidation("CATALOG_DRAFT_VALIDATION", POLICY_VERSION, CatalogDraftCanonicalizer.VERSION,
                draft.catalogVersion(), draft.schemaVersion(), at, issues.isEmpty() ? Status.VALID_DRAFT : Status.INVALID_DRAFT,
                CatalogDraftCanonicalizer.sha256(draft), false, false, false, false,
                draft.options().size(), facts.size(), issues, facts);
    }
}
