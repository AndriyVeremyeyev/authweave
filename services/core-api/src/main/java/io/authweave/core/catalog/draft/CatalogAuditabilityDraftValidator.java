package io.authweave.core.catalog.draft;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import io.authweave.core.catalog.draft.AuditabilityCatalogDraft.Fact;
import io.authweave.core.catalog.draft.AuditabilityCatalogDraft.Scope;
import io.authweave.core.catalog.draft.CatalogDraftValidation.Freshness;
import io.authweave.core.evaluation.EvidencePolicy;

/** Pure local validation and target addressing. No review, repository, evaluator or source fetcher. */
@Component
public final class CatalogAuditabilityDraftValidator {
    public static final String POLICY_VERSION = "catalog-auditability-draft-validation-1";
    private final Clock clock;
    private final CatalogDraftValidator baseValidator;
    public CatalogAuditabilityDraftValidator(Clock clock, CatalogDraftValidator baseValidator) {
        this.clock = clock; this.baseValidator = baseValidator;
    }
    public record Request(ProviderCatalogDraft baseDraft, AuditabilityCatalogDraft auditabilityDraft) {
        public Request { Objects.requireNonNull(baseDraft); Objects.requireNonNull(auditabilityDraft); }
    }
    public enum Status { VALID_DRAFT, INVALID_DRAFT }
    public enum IssueCode { BASE_DRAFT_INVALID, BASE_CONTENT_MISMATCH, BASE_VERSION_MISMATCH,
        UNKNOWN_OPTION, OPTION_SCOPE_MISMATCH, MISSING_OPTION_SCOPE }
    public record Issue(String optionId, String path, IssueCode code) {
        public Issue { Objects.requireNonNull(path); Objects.requireNonNull(code); }
    }

    /** Clock-independent address of this exact owner-supplied claim, not a source-page hash or authority. */
    public record ReviewBinding(String scope, String baseContentSha256, String auditabilityContentSha256,
            Scope optionScope, Fact fact) { }
    public record Target(String baseContentSha256, String auditabilityContentSha256,
            Scope scope, Fact fact, Freshness freshness) {
        public Target {
            AuditabilityCatalogDraft.digest(baseContentSha256); AuditabilityCatalogDraft.digest(auditabilityContentSha256);
            Objects.requireNonNull(scope); Objects.requireNonNull(fact); Objects.requireNonNull(freshness);
        }
        ReviewBinding binding() { return new ReviewBinding("AUDITABILITY_SOURCE_REVIEW_TARGET_V1", baseContentSha256, auditabilityContentSha256, scope, fact); }
        @JsonProperty public String factPath() { return "auditability." + fact.criterion().name(); }
        @JsonProperty public String evidenceStatus() { return "UNREVIEWED"; }
        @JsonProperty public String targetSha256() { return CatalogDraftCanonicalizer.sha256(binding()); }
    }

    public record Validation(Instant evaluatedAt, Status status, String evidenceVersion, String contentSha256,
            CatalogDraftValidation baseValidation, int optionCount, List<Issue> issues, List<Target> targets) {
        public Validation {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(status); Objects.requireNonNull(baseValidation);
            if (evidenceVersion == null || !evidenceVersion.matches("[a-z0-9][a-z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid evidence version");
            AuditabilityCatalogDraft.digest(contentSha256);
            issues = List.copyOf(issues); targets = List.copyOf(targets);
            if (optionCount < 1 || optionCount > 100 || targets.size() > optionCount * 6
                    || !evaluatedAt.equals(baseValidation.evaluatedAt())
                    || !CatalogDraftValidator.POLICY_VERSION.equals(baseValidation.policyVersion())
                    || !CatalogDraftCanonicalizer.VERSION.equals(baseValidation.canonicalizationVersion())
                    || baseValidation.catalogSchemaVersion() != 1 || baseValidation.sourceVerificationPerformed()
                    || baseValidation.approvalGranted() || baseValidation.writesPerformed() || baseValidation.evaluationReady()
                    || (status == Status.VALID_DRAFT) != issues.isEmpty()
                    || (status == Status.INVALID_DRAFT && !targets.isEmpty())
                    || (status == Status.VALID_DRAFT && (baseValidation.status() != CatalogDraftValidation.Status.VALID_DRAFT
                        || optionCount != baseValidation.optionCount())))
                throw new IllegalArgumentException("Inconsistent auditability draft validation");
            var keys = new HashSet<List<String>>();
            var optionScopes = new java.util.HashMap<String, Scope>();
            var baseIds = baseValidation.facts().stream().map(CatalogDraftValidation.FactReview::optionId).collect(java.util.stream.Collectors.toSet());
            for (var target : targets) {
                var previousScope = optionScopes.putIfAbsent(target.scope().optionId(), target.scope());
                if (!target.baseContentSha256().equals(baseValidation.contentSha256())
                        || !target.auditabilityContentSha256().equals(contentSha256)
                        || !baseIds.contains(target.scope().optionId())
                        || (previousScope != null && !previousScope.equals(target.scope()))
                        || !keys.add(List.of(target.scope().optionId(), target.factPath()))
                        || target.freshness() != freshness(target.fact().evidence().observedAt(), evaluatedAt))
                    throw new IllegalArgumentException("Unbound auditability source-review target");
            }
        }
        @JsonProperty public String scope() { return "CATALOG_AUDITABILITY_DRAFT_VALIDATION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String canonicalizationVersion() { return CatalogDraftCanonicalizer.VERSION; }
        @JsonProperty public int draftSchemaVersion() { return 1; }
        @JsonProperty public int targetCount() { return targets.size(); }
        @JsonProperty public boolean reviewTargetsAvailable() { return status == Status.VALID_DRAFT; }
        @JsonProperty public String reviewTargetSetSha256() { return reviewTargetsAvailable()
                ? CatalogDraftCanonicalizer.sha256(targets.stream().map(Target::binding).toList()) : null; }
        @JsonProperty public boolean sourceReviewWorkflowAvailable() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean candidateImpactPerformed() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }

    public Validation validate(Request request) { return validateAt(request, clock.instant()); }
    public Validation validateAt(Request request, Instant at) {
        var draft = request.auditabilityDraft();
        var base = baseValidator.validateAt(request.baseDraft(), at);
        var issues = new ArrayList<Issue>();
        if (base.status() != CatalogDraftValidation.Status.VALID_DRAFT) issues.add(new Issue(null, "baseDraft", IssueCode.BASE_DRAFT_INVALID));
        if (!draft.baseContentSha256().equals(base.contentSha256())) issues.add(new Issue(null, "baseContentSha256", IssueCode.BASE_CONTENT_MISMATCH));
        if (!draft.baseCatalogVersion().equals(request.baseDraft().catalogVersion())) issues.add(new Issue(null, "baseCatalogVersion", IssueCode.BASE_VERSION_MISMATCH));
        for (var option : draft.options()) {
            var matches = request.baseDraft().options().stream().filter(o -> o.id().equals(option.scope().optionId())).toList();
            if (matches.isEmpty()) issues.add(new Issue(option.scope().optionId(), "options.scope", IssueCode.UNKNOWN_OPTION));
            else if (matches.size() != 1 || !Scope.of(matches.getFirst()).equals(option.scope()))
                issues.add(new Issue(option.scope().optionId(), "options.scope", IssueCode.OPTION_SCOPE_MISMATCH));
        }
        request.baseDraft().options().stream().map(ProviderCatalogDraft.Option::id).distinct().forEach(id -> {
            if (draft.options().stream().noneMatch(o -> o.scope().optionId().equals(id)))
                issues.add(new Issue(id, "options.scope", IssueCode.MISSING_OPTION_SCOPE));
        });
        issues.sort(Comparator.comparing(Issue::optionId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Issue::path).thenComparing(i -> i.code().name()));
        String hash = CatalogDraftCanonicalizer.sha256(draft);
        var targets = new ArrayList<Target>();
        if (issues.isEmpty()) draft.options().forEach(o -> o.facts().forEach(f ->
                targets.add(new Target(base.contentSha256(), hash, o.scope(), f, freshness(f.evidence().observedAt(), at)))));
        return new Validation(at, issues.isEmpty() ? Status.VALID_DRAFT : Status.INVALID_DRAFT,
                draft.evidenceVersion(), hash, base, draft.options().size(), issues, targets);
    }
    private static Freshness freshness(Instant observed, Instant at) {
        return observed.isAfter(at) ? Freshness.FUTURE : observed.isBefore(at.minus(EvidencePolicy.MAX_AGE)) ? Freshness.STALE : Freshness.CURRENT;
    }
}
