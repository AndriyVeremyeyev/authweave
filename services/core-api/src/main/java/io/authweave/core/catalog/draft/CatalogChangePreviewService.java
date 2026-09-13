package io.authweave.core.catalog.draft;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.CatalogChangePreview.*;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;

/** Pure comparison of supplied drafts. This service cannot approve, publish, fetch sources or write domain state. */
@Service
public final class CatalogChangePreviewService {
    public static final String POLICY_VERSION = "catalog-change-preview-1";
    private final CatalogDraftValidator validator;
    private final Clock clock;
    public CatalogChangePreviewService(CatalogDraftValidator validator, Clock clock) { this.validator = validator; this.clock = clock; }

    public CatalogChangePreview preview(CatalogChangePreviewRequest request) {
        var at = clock.instant();
        var base = DraftReview.from(validator.validateAt(request.base(), at));
        var candidate = DraftReview.from(validator.validateAt(request.candidate(), at));
        var blockers = new ArrayList<Blocker>();
        if (base.status() != CatalogDraftValidation.Status.VALID_DRAFT) blockers.add(Blocker.BASE_DRAFT_INVALID);
        if (candidate.status() != CatalogDraftValidation.Status.VALID_DRAFT) blockers.add(Blocker.CANDIDATE_DRAFT_INVALID);
        if (!base.contentSha256().equals(request.expectedBaseSha256())) blockers.add(Blocker.BASE_DIGEST_MISMATCH);
        boolean versionChanged = !base.catalogVersion().equals(candidate.catalogVersion());
        if (!versionChanged && !base.contentSha256().equals(candidate.contentSha256())) blockers.add(Blocker.CATALOG_VERSION_REUSED);
        var optionChanges = new ArrayList<OptionChange>();
        var factChanges = new ArrayList<FactChange>();
        var affected = new TreeSet<String>();
        if (blockers.isEmpty()) {
            var previous = options(request.base()); var proposed = options(request.candidate());
            var ids = new TreeSet<>(previous.keySet()); ids.addAll(proposed.keySet());
            for (var id : ids) {
                var oldOption = previous.get(id); var newOption = proposed.get(id);
                var beforeScope = OptionScope.from(oldOption); var afterScope = OptionScope.from(newOption);
                if (!Objects.equals(beforeScope, afterScope)) {
                    optionChanges.add(new OptionChange(id, changeType(oldOption, newOption), beforeScope, afterScope, true));
                    affected.add(id);
                }
                Map<String, ProposedFact> oldFacts = oldOption == null ? Map.of() : CatalogDraftFacts.entries(oldOption);
                Map<String, ProposedFact> newFacts = newOption == null ? Map.of() : CatalogDraftFacts.entries(newOption);
                var paths = new TreeSet<>(oldFacts.keySet()); paths.addAll(newFacts.keySet());
                for (var path : paths) {
                    var before = CatalogDraftFacts.normalized(oldFacts.get(path));
                    var after = CatalogDraftFacts.normalized(newFacts.get(path));
                    if (CatalogDraftCanonicalizer.json(before).equals(CatalogDraftCanonicalizer.json(after))) continue;
                    var aspects = aspects(before, after);
                    factChanges.add(new FactChange(id, path, kind(before == null ? after : before), changeType(before, after),
                            aspects, CatalogDraftValidation.ReviewStatus.UNREVIEWED, before, after));
                    affected.add(id);
                }
            }
        }
        return new CatalogChangePreview("CATALOG_CHANGE_PREVIEW", POLICY_VERSION, CatalogDraftCanonicalizer.VERSION,
                request.proposalId(), CatalogDraftCanonicalizer.sha256(request), request.rationale(), ProposalState.PROPOSED, at,
                !blockers.isEmpty() ? Status.BLOCKED : affected.isEmpty() ? Status.NO_CONTENT_CHANGES : Status.REVIEW_REQUIRED,
                blockers.isEmpty(), versionChanged, false, false, false, false, false, false,
                base, candidate, blockers, List.copyOf(affected), optionChanges, factChanges);
    }

    private static Map<String, Option> options(ProviderCatalogDraft draft) {
        var result = new TreeMap<String, Option>(); draft.options().forEach(option -> result.put(option.id(), option)); return result;
    }
    private static ChangeType changeType(Object before, Object after) {
        return before == null ? ChangeType.ADDED : after == null ? ChangeType.REMOVED : ChangeType.MODIFIED;
    }
    private static FactKind kind(ProposedFact fact) {
        return switch (fact) {
            case CapabilityFact ignored -> FactKind.CAPABILITY;
            case CompatibilityFact ignored -> FactKind.COMPATIBILITY;
            case ResidencyFact ignored -> FactKind.RESIDENCY;
            case AuthenticationFact ignored -> FactKind.AUTHENTICATION_CONTROL;
            default -> throw new IllegalArgumentException("Unsupported proposed fact type");
        };
    }
    private static Object claim(ProposedFact fact) {
        return switch (fact) {
            case CapabilityFact value -> value.availability();
            case CompatibilityFact value -> value.support();
            case ResidencyFact value -> List.of(value.coverage(), value.storageCountries());
            case AuthenticationFact value -> List.of(value.availability(), value.enforcement());
            default -> throw new IllegalArgumentException("Unsupported proposed fact type");
        };
    }
    private static List<Aspect> aspects(ProposedFact before, ProposedFact after) {
        if (before == null || after == null) return List.of(Aspect.PRESENCE);
        var result = new ArrayList<Aspect>();
        if (!claim(before).equals(claim(after))) result.add(Aspect.CLAIM);
        if (!before.conditions().equals(after.conditions())) result.add(Aspect.CONDITIONS);
        if (!before.evidence().sourceUrl().toString().equals(after.evidence().sourceUrl().toString())) result.add(Aspect.SOURCE_URL);
        if (!before.evidence().observedAt().equals(after.evidence().observedAt())) result.add(Aspect.OBSERVED_AT);
        if (!before.evidence().summary().equals(after.evidence().summary())) result.add(Aspect.EVIDENCE_SUMMARY);
        return result;
    }
}
