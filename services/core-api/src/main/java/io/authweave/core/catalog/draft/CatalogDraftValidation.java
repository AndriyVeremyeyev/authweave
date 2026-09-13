package io.authweave.core.catalog.draft;

import java.time.Instant;
import java.util.List;

public record CatalogDraftValidation(String scope, String policyVersion, String canonicalizationVersion,
        String catalogVersion, int catalogSchemaVersion, Instant evaluatedAt, Status status, String contentSha256,
        boolean sourceVerificationPerformed, boolean approvalGranted, boolean writesPerformed, boolean evaluationReady,
        int optionCount, int factCount, List<Issue> issues, List<FactReview> facts) {
    public CatalogDraftValidation { issues = List.copyOf(issues); facts = List.copyOf(facts); }
    public enum Status { VALID_DRAFT, INVALID_DRAFT }
    public enum IssueCode {
        DUPLICATE_OPTION_ID, DUPLICATE_OPTION_SCOPE, NO_FACTS_RECORDED,
        INVALID_COUNTRY, RESIDENCY_COVERAGE_INCONSISTENT, AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY
    }
    public enum Freshness { CURRENT, STALE, FUTURE }
    public enum ReviewStatus { UNREVIEWED }
    /** Paths are relative to the option, addressed by optionId, not mutable array indexes. */
    public record Issue(String optionId, String path, IssueCode code, String explanation) { }
    public record FactReview(String optionId, String path, ReviewStatus evidenceStatus, Freshness freshness,
            List<String> conditions, ProviderCatalogDraft.Evidence evidence) {
        public FactReview { conditions = List.copyOf(conditions); }
    }
}
