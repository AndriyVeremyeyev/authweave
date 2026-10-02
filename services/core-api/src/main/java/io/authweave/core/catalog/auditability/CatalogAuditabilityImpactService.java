package io.authweave.core.catalog.auditability;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.AuditabilityCatalogDraft;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidation.Freshness;
import io.authweave.core.catalog.impact.CatalogAuditabilityRegressionCases;
import io.authweave.core.catalog.impact.CatalogScopedProfileCases;
import io.authweave.core.catalog.impact.CatalogImpactPreview.Outcome;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.evaluation.AuditabilityEvaluator;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException.Reason.CONFLICT;

/** Conditional comparison of two exact stored human reviews. Neither side is a trusted publication baseline. */
@Service
public class CatalogAuditabilityImpactService {
    public static final String POLICY_VERSION = "catalog-auditability-candidate-impact-1";
    private final CatalogAuditabilityReviewService reviews;
    private final CatalogAuditabilityRegressionCases scenarios;
    private final Clock clock;

    public CatalogAuditabilityImpactService(CatalogAuditabilityReviewService reviews,
            CatalogAuditabilityRegressionCases scenarios, Clock clock) {
        this.reviews = reviews; this.scenarios = scenarios; this.clock = clock;
    }

    public record Request(int schemaVersion, UUID beforeReviewId, String expectedBeforeReviewSha256,
            UUID afterReviewId, String expectedAfterReviewSha256) {
        public Request {
            if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported auditability impact request version");
            Objects.requireNonNull(beforeReviewId); Objects.requireNonNull(afterReviewId);
            CatalogAuditabilityReviewRequest.digest(expectedBeforeReviewSha256);
            CatalogAuditabilityReviewRequest.digest(expectedAfterReviewSha256);
        }
    }
    public enum Reason { CRITERION_NOT_SELECTED, FACT_MISSING, CLAIM_FROM_FUTURE, CLAIM_STALE,
        CLAIM_UNKNOWN, CLAIM_UNAVAILABLE, CONDITIONS_UNVERIFIED, CLAIM_AVAILABLE,
        RETENTION_DURATION_UNKNOWN, RETENTION_BELOW_MINIMUM, RETENTION_MEETS_MINIMUM }

    public record Side(Outcome conditionalOutcome, Reason reason, String factSha256, String targetSha256,
            Verdict sourceVerdict, Instant observedAt, Freshness freshness, boolean conditionsRecorded,
            Integer documentedMinimumRetentionDays) {
        public Side {
            Objects.requireNonNull(conditionalOutcome); Objects.requireNonNull(reason);
            Outcome expected = switch (reason) {
                case CRITERION_NOT_SELECTED -> Outcome.NOT_APPLIED;
                case CLAIM_UNAVAILABLE, RETENTION_BELOW_MINIMUM -> Outcome.WOULD_VIOLATE;
                case CLAIM_AVAILABLE, RETENTION_MEETS_MINIMUM -> Outcome.WOULD_SATISFY;
                default -> Outcome.INDETERMINATE;
            };
            if (conditionalOutcome != expected || (factSha256 == null ? targetSha256 != null || sourceVerdict != null
                    || observedAt != null || freshness != null || conditionsRecorded || documentedMinimumRetentionDays != null
                    : targetSha256 == null || sourceVerdict == null || observedAt == null || freshness == null))
                throw new IllegalArgumentException("Unbound conditional auditability side");
            if (factSha256 != null) {
                CatalogAuditabilityReviewRequest.digest(factSha256); CatalogAuditabilityReviewRequest.digest(targetSha256);
            }
            boolean duration = reason == Reason.RETENTION_BELOW_MINIMUM || reason == Reason.RETENTION_MEETS_MINIMUM;
            if (duration != (documentedMinimumRetentionDays != null) || duration && (documentedMinimumRetentionDays < 0
                    || documentedMinimumRetentionDays > AuditabilityRequirements.MAX_RETENTION_DAYS))
                throw new IllegalArgumentException("Unbound conditional retention result");
        }
    }
    public record Check(Criterion criterion, Side before, Side after) {
        public Check { Objects.requireNonNull(criterion); Objects.requireNonNull(before); Objects.requireNonNull(after); }
        @JsonProperty public boolean factChanged() { return !Objects.equals(before.factSha256(), after.factSha256()); }
        @JsonProperty public boolean conditionalResultChanged() {
            return before.conditionalOutcome() != after.conditionalOutcome() || before.reason() != after.reason()
                    || !Objects.equals(before.documentedMinimumRetentionDays(), after.documentedMinimumRetentionDays());
        }
    }
    public record Scenario(String scenarioId, String profileSha256, AuditabilityRequirements requirements,
            AuditabilityCatalogDraft.Scope optionScope, List<Check> checks) {
        public Scenario {
            Objects.requireNonNull(requirements); Objects.requireNonNull(optionScope); checks = List.copyOf(checks);
            CatalogAuditabilityReviewRequest.digest(profileSha256);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || requirements.isUnrecorded())
                throw new IllegalArgumentException("Use an explicit scoped auditability scenario");
            if (checks.size() != Criterion.values().length) throw new IllegalArgumentException("Incomplete conditional auditability case");
            for (int i = 0; i < checks.size(); i++) if (checks.get(i).criterion() != Criterion.values()[i])
                throw new IllegalArgumentException("Use canonical conditional auditability criteria");
        }
    }
    public record Impact(Instant evaluatedAt, String scenarioSetSha256, CatalogAuditabilityReview beforeReview,
            CatalogAuditabilityReview afterReview, List<Scenario> scenarios) {
        public Impact {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(beforeReview); Objects.requireNonNull(afterReview);
            CatalogAuditabilityReviewRequest.digest(scenarioSetSha256); scenarios = List.copyOf(scenarios);
            if (!beforeReview.baseContentSha256().equals(afterReview.baseContentSha256())
                    || scenarios.size() != beforeReview.optionCount() * CatalogScopedProfileCases.COUNT || beforeReview.optionCount() != afterReview.optionCount()
                    || scenarios.stream().map(s -> List.of(s.scenarioId(), s.optionScope().optionId())).distinct().count() != scenarios.size())
                throw new IllegalArgumentException("Inconsistent conditional auditability inventory");
            var scopes = scenarios.stream().map(Scenario::optionScope).distinct().toList();
            if (scopes.size() != beforeReview.optionCount()) throw new IllegalArgumentException("Incomplete option scope inventory");
            for (var optionScope : scopes) if (!java.util.Set.copyOf(scenarios.stream().filter(s -> s.optionScope().equals(optionScope))
                    .map(Scenario::scenarioId).toList()).equals(CatalogScopedProfileCases.IDS))
                throw new IllegalArgumentException("Incomplete scoped auditability scenarios");
        }
        @JsonProperty public String scope() { return "CONDITIONAL_AUDITABILITY_CANDIDATE_CHANGE_IMPACT"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String scenarioSetVersion() { return CatalogAuditabilityRegressionCases.VERSION; }
        @JsonProperty public int profileSchemaVersion() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String analysisBasis() { return "ASSUMING_UNTRUSTED_DRAFT_CLAIMS_WITH_UNVERIFIED_CONDITIONS_INDETERMINATE"; }
        @JsonProperty public String analysisSha256() { return CatalogDraftCanonicalizer.sha256(List.of(POLICY_VERSION,
                evaluatedAt, scenarioSetVersion(), scenarioSetSha256, profileSchemaVersion(), profileSchemaSha256(), beforeReview, afterReview, scenarios)); }
        @JsonProperty public int checkedCases() { return scenarios.size(); }
        @JsonProperty public int checkedCriteria() { return scenarios.size() * Criterion.values().length; }
        @JsonProperty public int changedFacts() { return (int) scenarios.stream().flatMap(s -> s.checks().stream()
                .filter(Check::factChanged).map(c -> List.of(s.optionScope().optionId(), c.criterion().name()))).distinct().count(); }
        @JsonProperty public int changedChecks() { return (int) scenarios.stream().flatMap(s -> s.checks().stream()).filter(Check::conditionalResultChanged).count(); }
        @JsonProperty public List<String> deferredBoundaries() { return AuditabilityEvaluator.DEFERRED_BOUNDARIES; }
        @JsonProperty public boolean storedReviewsVerified() { return true; }
        @JsonProperty public boolean candidateChangesEvaluated() { return true; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean factTrustChanged() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean complianceVerified() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Impact preview(Request request) {
        Objects.requireNonNull(request);
        var before = reviews.reviewed(request.beforeReviewId(), request.expectedBeforeReviewSha256());
        var after = reviews.reviewed(request.afterReviewId(), request.expectedAfterReviewSha256());
        if (!before.review().baseContentSha256().equals(after.review().baseContentSha256()))
            throw new CatalogAuditabilityReviewException(CONFLICT);
        Instant at = clock.instant();
        var rows = new ArrayList<Scenario>();
        for (var definition : scenarios.definitions()) {
            for (var option : before.request().candidate().auditabilityDraft().options()) {
                var checks = java.util.Arrays.stream(Criterion.values()).map(criterion -> new Check(criterion,
                        side(before.request(), option.scope().optionId(), criterion, definition.requirements(), at),
                        side(after.request(), option.scope().optionId(), criterion, definition.requirements(), at))).toList();
                rows.add(new Scenario(definition.scenarioId(), definition.profileSha256(), definition.requirements(), option.scope(), checks));
            }
        }
        return new Impact(at, scenarios.sha256(), before.review(), after.review(), rows);
    }

    static Side side(CatalogAuditabilityReviewRequest request, String optionId, Criterion criterion,
            AuditabilityRequirements requirements, Instant at) {
        var option = request.candidate().auditabilityDraft().options().stream().filter(o -> o.scope().optionId().equals(optionId)).findFirst().orElseThrow();
        var fact = option.facts().stream().filter(f -> f.criterion() == criterion).findFirst().orElse(null);
        var observation = request.observations().stream().filter(o -> o.optionId().equals(optionId) && o.criterion() == criterion).findFirst().orElse(null);
        if ((fact == null) != (observation == null)) throw new IllegalArgumentException("Missing exact source-review observation");
        Freshness freshness = fact == null ? null : fact.evidence().observedAt().isAfter(at) ? Freshness.FUTURE
                : fact.evidence().observedAt().isBefore(at.minus(EvidencePolicy.MAX_AGE)) ? Freshness.STALE : Freshness.CURRENT;
        Reason reason; Integer duration = null;
        if (!requirements.selectedCriteria().contains(criterion)) reason = Reason.CRITERION_NOT_SELECTED;
        else if (fact == null) reason = Reason.FACT_MISSING;
        else if (freshness == Freshness.FUTURE) reason = Reason.CLAIM_FROM_FUTURE;
        else if (freshness == Freshness.STALE) reason = Reason.CLAIM_STALE;
        else if (fact.support() == io.authweave.core.catalog.ProviderCatalog.Support.UNKNOWN) reason = Reason.CLAIM_UNKNOWN;
        else if (!fact.conditions().isEmpty()) reason = Reason.CONDITIONS_UNVERIFIED;
        else if (fact.support() == io.authweave.core.catalog.ProviderCatalog.Support.UNSUPPORTED) reason = Reason.CLAIM_UNAVAILABLE;
        else if (criterion != Criterion.AUDIT_LOG_RETENTION) reason = Reason.CLAIM_AVAILABLE;
        else if (fact.documentedMinimumRetentionDays() == null) reason = Reason.RETENTION_DURATION_UNKNOWN;
        else {
            duration = fact.documentedMinimumRetentionDays();
            reason = duration < requirements.minimumRetentionDays() ? Reason.RETENTION_BELOW_MINIMUM : Reason.RETENTION_MEETS_MINIMUM;
        }
        Outcome outcome = switch (reason) {
            case CRITERION_NOT_SELECTED -> Outcome.NOT_APPLIED;
            case CLAIM_UNAVAILABLE, RETENTION_BELOW_MINIMUM -> Outcome.WOULD_VIOLATE;
            case CLAIM_AVAILABLE, RETENTION_MEETS_MINIMUM -> Outcome.WOULD_SATISFY;
            default -> Outcome.INDETERMINATE;
        };
        return new Side(outcome, reason, fact == null ? null : CatalogDraftCanonicalizer.sha256(fact),
                observation == null ? null : observation.expectedTargetSha256(), observation == null ? null : observation.verdict(),
                fact == null ? null : fact.evidence().observedAt(), freshness, fact != null && !fact.conditions().isEmpty(), duration);
        // Manual verdicts remain visible human assertions; they never promote a claim or change the hypothetical assumption.
    }
}
