package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;
import io.authweave.core.catalog.proposal.CatalogProposalException;
import io.authweave.core.evaluation.ClaimRules;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.*;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.Outcome.*;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.Reason.*;

/** Isolated what-if probes: never creates a reviewed ProviderCatalog, runs source fetches or writes state. */
@Service
public final class CatalogImpactService {
    public static final String POLICY_VERSION = "catalog-impact-preview-1";
    private final CatalogChangePreviewService previews;
    public CatalogImpactService(CatalogChangePreviewService previews) { this.previews = previews; }
    public CatalogImpactPreview analyze(CatalogChangePreviewRequest request) { return analyze(request, null, null); }

    CatalogImpactPreview analyze(CatalogChangePreviewRequest request, Long storedVersion, String storedDigest) {
        var preview = previews.preview(request);
        if (storedDigest != null && !storedDigest.equals(preview.proposalSha256())) {
            throw new CatalogProposalException(CatalogProposalException.Reason.REPLAY_UNAVAILABLE);
        }
        boolean blocked = !preview.diffComputed();
        var cases = new ArrayList<CaseImpact>(); var uncovered = new ArrayList<UncoveredChange>();
        if (!blocked) {
            var before = options(request.base()); var after = options(request.candidate());
            var scopes = preview.optionChanges().stream().map(CatalogChangePreview.OptionChange::optionId).collect(Collectors.toSet());
            var changes = new TreeMap<String, Map<String, CatalogChangePreview.FactChange>>();
            for (var change : preview.factChanges()) changes.computeIfAbsent(change.optionId(), ignored -> new TreeMap<>()).put(change.path(), change);
            var coveredPaths = CatalogImpactCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath).collect(Collectors.toSet());
            for (var change : preview.factChanges()) if (!scopes.contains(change.optionId()) && !coveredPaths.contains(change.path())) {
                uncovered.add(new UncoveredChange(change.optionId(), change.path(), "NO_PROBE_FOR_FACT_PATH"));
            }
            for (var id : new TreeSet<>(preview.affectedOptionIds())) {
                var oldOption = before.get(id); var newOption = after.get(id);
                var oldFacts = oldOption == null ? Map.<String, ProposedFact>of() : CatalogDraftFacts.entries(oldOption);
                var newFacts = newOption == null ? Map.<String, ProposedFact>of() : CatalogDraftFacts.entries(newOption);
                if (scopes.contains(id)) {
                    var allPaths = new TreeSet<>(oldFacts.keySet()); allPaths.addAll(newFacts.keySet());
                    for (var path : allPaths) if (!coveredPaths.contains(path)) uncovered.add(new UncoveredChange(id, path, "NO_PROBE_FOR_FACT_PATH"));
                }
                for (var probe : CatalogImpactCases.PROBES) {
                    var change = changes.getOrDefault(id, Map.of()).get(probe.factPath());
                    boolean scopeChanged = scopes.contains(id);
                    if (!scopeChanged && change == null) continue;
                    var oldSide = side(probe, oldOption != null, oldFacts.get(probe.factPath()), preview.evaluatedAt());
                    var newSide = side(probe, newOption != null, newFacts.get(probe.factPath()), preview.evaluatedAt());
                    cases.add(new CaseImpact(probe.id(), id, probe.factPath(), scopeChanged,
                            change == null ? List.of() : change.aspects(),
                            oldSide.conditionalOutcome() != newSide.conditionalOutcome() || oldSide.reason() != newSide.reason(), oldSide, newSide));
                }
            }
            uncovered.sort(java.util.Comparator.comparing(UncoveredChange::optionId).thenComparing(UncoveredChange::factPath));
        }
        return new CatalogImpactPreview("CATALOG_RULE_PROBE_IMPACT", POLICY_VERSION, ClaimRules.VERSION,
                CatalogImpactCases.VERSION, CatalogImpactCases.SHA256, "ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS", preview.evaluatedAt(),
                request.proposalId(), preview.proposalSha256(), storedVersion, storedDigest != null,
                blocked ? Status.BLOCKED : Status.ANALYZED, !blocked, !blocked,
                false, false, false, false, false, false, false, preview, CatalogImpactCases.PROBES, cases, uncovered);
    }

    private static Map<String, Option> options(ProviderCatalogDraft draft) {
        var result = new TreeMap<String, Option>(); draft.options().forEach(option -> result.put(option.id(), option)); return result;
    }
    static Side side(CatalogImpactCases.Probe probe, boolean optionPresent, ProposedFact fact, Instant at) {
        var freshness = fact == null ? null : fact.evidence().observedAt().isAfter(at) ? CatalogDraftValidation.Freshness.FUTURE
                : fact.evidence().observedAt().isBefore(at.minus(EvidencePolicy.MAX_AGE)) ? CatalogDraftValidation.Freshness.STALE : CatalogDraftValidation.Freshness.CURRENT;
        Outcome outcome = INDETERMINATE; Reason reason;
        if (!optionPresent) reason = OPTION_ABSENT;
        else if (probe.criticality() == io.authweave.core.assessment.domain.profile.RequirementCriticality.NOT_REQUIRED) {
            outcome = NOT_APPLIED; reason = NO_REQUIREMENT;
        } else if (probe.criticality() == io.authweave.core.assessment.domain.profile.RequirementCriticality.PREFERRED) {
            outcome = NOT_APPLIED; reason = PREFERENCE_NOT_SCORED;
        } else if (probe.criticality() == io.authweave.core.assessment.domain.profile.RequirementCriticality.UNKNOWN) reason = REQUIREMENT_UNKNOWN;
        else if (fact == null) reason = FACT_MISSING;
        else {
            switch (fact) {
                case CapabilityFact value -> {
                    outcome = conditional(ClaimRules.capability(probe.criticality(), value.availability()));
                    reason = outcome == INDETERMINATE ? CLAIM_UNKNOWN
                            : probe.criticality() == io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED
                                ? outcome == WOULD_SATISFY ? REQUIRED_CLAIM_AVAILABLE : REQUIRED_CLAIM_UNAVAILABLE
                                : outcome == WOULD_SATISFY ? FORBIDDEN_CLAIM_AVOIDABLE : FORBIDDEN_CLAIM_UNAVOIDABLE;
                }
                case CompatibilityFact value -> {
                    outcome = conditional(ClaimRules.compatibility(value.support()));
                    reason = outcome == INDETERMINATE ? CLAIM_UNKNOWN : outcome == WOULD_SATISFY ? CONTEXT_SUPPORTED : CONTEXT_UNSUPPORTED;
                }
                case ResidencyFact value -> {
                    outcome = conditional(ClaimRules.residency(value.coverage(), value.storageCountries(), probe.allowedCountries()));
                    reason = outcome == WOULD_SATISFY ? STORAGE_COMPLETE_WITHIN_ALLOWLIST : outcome == WOULD_VIOLATE ? STORAGE_OUTSIDE_ALLOWLIST
                            : value.coverage() == io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage.PARTIAL ? STORAGE_INCOMPLETE : CLAIM_UNKNOWN;
                }
                case AuthenticationFact value -> {
                    outcome = conditional(ClaimRules.authentication(value.availability(), value.enforcement()));
                    reason = outcome == WOULD_SATISFY ? CONTROL_ENFORCEABLE
                            : value.availability() == io.authweave.core.catalog.ProviderCatalog.Support.UNSUPPORTED ? CONTROL_UNAVAILABLE
                            : value.enforcement() == io.authweave.core.catalog.ProviderCatalog.Support.UNSUPPORTED ? ENFORCEMENT_UNSUPPORTED : CLAIM_UNKNOWN;
                }
                default -> throw new IllegalArgumentException("Unsupported asserted fact");
            }
        }
        return new Side(optionPresent, fact != null, outcome, reason, freshness, fact != null && !fact.conditions().isEmpty());
    }
    private static Outcome conditional(io.authweave.core.evaluation.CapabilityPreflight.Outcome value) {
        return switch (value) { case PASS -> WOULD_SATISFY; case FAIL -> WOULD_VIOLATE; case UNKNOWN -> INDETERMINATE; case NOT_APPLIED -> NOT_APPLIED; };
    }
}
