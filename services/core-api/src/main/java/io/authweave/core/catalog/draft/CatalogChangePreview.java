package io.authweave.core.catalog.draft;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;

/** Review material only. No authorized decision, persistence, publication or downstream impact analysis. */
public record CatalogChangePreview(String scope, String policyVersion, String canonicalizationVersion,
        UUID proposalId, String proposalSha256, String rationale, ProposalState proposalState, Instant evaluatedAt,
        Status status, boolean diffComputed, boolean catalogVersionChanged,
        boolean baselineVerified, boolean sourceVerificationPerformed, boolean approvalGranted,
        boolean writesPerformed, boolean evaluationReady, boolean impactAnalysisPerformed,
        DraftReview baseReview, DraftReview candidateReview, List<Blocker> blockers,
        List<String> affectedOptionIds, List<OptionChange> optionChanges, List<FactChange> factChanges) {
    public CatalogChangePreview {
        blockers = List.copyOf(blockers); affectedOptionIds = List.copyOf(affectedOptionIds);
        optionChanges = List.copyOf(optionChanges); factChanges = List.copyOf(factChanges);
    }
    public enum ProposalState { PROPOSED }
    public enum Status { BLOCKED, NO_CONTENT_CHANGES, REVIEW_REQUIRED }
    public enum Blocker { BASE_DRAFT_INVALID, CANDIDATE_DRAFT_INVALID, BASE_DIGEST_MISMATCH, CATALOG_VERSION_REUSED }
    public enum ChangeType { ADDED, REMOVED, MODIFIED }
    public enum FactKind { CAPABILITY, COMPATIBILITY, RESIDENCY, AUTHENTICATION_CONTROL }
    public enum Aspect { PRESENCE, CLAIM, CONDITIONS, SOURCE_URL, OBSERVED_AT, EVIDENCE_SUMMARY }

    public record DraftReview(String catalogVersion, String contentSha256, CatalogDraftValidation.Status status,
            int optionCount, int factCount, FreshnessCounts freshness, List<CatalogDraftValidation.Issue> issues) {
        public DraftReview { issues = List.copyOf(issues); }
        static DraftReview from(CatalogDraftValidation validation) {
            int current = 0, stale = 0, future = 0;
            for (var fact : validation.facts()) {
                switch (fact.freshness()) { case CURRENT -> current++; case STALE -> stale++; case FUTURE -> future++; }
            }
            return new DraftReview(validation.catalogVersion(), validation.contentSha256(), validation.status(),
                    validation.optionCount(), validation.factCount(), new FreshnessCounts(current, stale, future), validation.issues());
        }
    }
    public record FreshnessCounts(int current, int stale, int future) { }
    public record OptionScope(String providerId, String product, String plan, Deployment deployment, String region, String configuration) {
        static OptionScope from(Option option) {
            return option == null ? null : new OptionScope(option.providerId(), option.product(), option.plan(),
                    option.deployment(), option.region(), option.configuration());
        }
    }
    public record OptionChange(String optionId, ChangeType changeType, OptionScope before, OptionScope after,
            boolean requiresAllFactsReview) { }
    public record FactChange(String optionId, String path, FactKind factKind, ChangeType changeType,
            List<Aspect> aspects, CatalogDraftValidation.ReviewStatus evidenceStatus, ProposedFact before, ProposedFact after) {
        public FactChange { aspects = List.copyOf(aspects); }
    }
}
