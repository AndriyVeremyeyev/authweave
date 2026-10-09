package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.AuditabilityFacts.Emitter;
import io.authweave.core.catalog.AuditabilityFacts.Fact;
import io.authweave.core.catalog.AuditabilityFacts.Scope;
import io.authweave.core.catalog.ProviderCatalog.Support;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome;

/** Pure, synthetic scoped provider-capability checks. Not AuthWeave's own audit trail or compliance. */
public final class AuditabilityEvaluator {
    public static final String POLICY_VERSION = "auditability-capability-preflight-1";
    public static final List<String> DEFERRED_BOUNDARIES = List.of("eventRecordContentAndScope",
            "auditRecordIntegrity", "auditAccessControls", "loggingFailureHandling",
            "exportDeliveryAndRetrieval", "deployedLoggingConfiguration", "complianceEvidence");
    public record Definition(Criterion criterion, String description) { }
    public static final List<Definition> DEFINITIONS = List.of(
            new Definition(Criterion.AUTHENTICATION_SUCCESS_EVENTS, "Provider logging of successful authentication events in this option scope."),
            new Definition(Criterion.AUTHENTICATION_FAILURE_EVENTS, "Provider logging of failed authentication events in this option scope."),
            new Definition(Criterion.ADMINISTRATIVE_CHANGE_EVENTS, "Provider logging of administrative identity configuration changes."),
            new Definition(Criterion.PROVISIONING_CHANGE_EVENTS, "Provider logging of identity provisioning changes; not proof of lifecycle execution."),
            new Definition(Criterion.AUDIT_LOG_EXPORT, "Documented provider log export capability; not proof of delivery to an external sink."),
            new Definition(Criterion.AUDIT_LOG_RETENTION, "Documented minimum provider log retention meeting an explicitly requested duration."));
    private AuditabilityEvaluator() { }

    public enum Reason {
        NO_REQUIREMENT, PREFERENCE_NOT_SCORED, REQUIREMENT_UNKNOWN, AUDIT_INTENT_UNCLEAR,
        AUDIT_SCOPE_UNKNOWN, CRITERION_NOT_SELECTED, EVIDENCE_MISSING, EVIDENCE_UNREVIEWED,
        EVIDENCE_FROM_FUTURE, EVIDENCE_STALE, CAPABILITY_UNKNOWN, CAPABILITY_UNAVAILABLE,
        DOCUMENTED_CAPABILITY_AVAILABLE, RETENTION_DURATION_UNKNOWN, RETENTION_BELOW_MINIMUM, RETENTION_MEETS_MINIMUM
    }
    public enum Status { MATCHES_CHECKED_REQUIREMENTS, DOES_NOT_MATCH, NEEDS_INFORMATION, NOT_APPLIED }

    public record Check(Criterion criterion, Outcome outcome, Reason reasonCode, Integer documentedMinimumRetentionDays) {
        public Check {
            Objects.requireNonNull(criterion); Objects.requireNonNull(outcome); Objects.requireNonNull(reasonCode);
            if (outcome != AuditabilityEvaluator.outcome(reasonCode)) throw new IllegalArgumentException("Inconsistent auditability outcome");
            boolean duration = reasonCode == Reason.RETENTION_BELOW_MINIMUM || reasonCode == Reason.RETENTION_MEETS_MINIMUM;
            if (duration ? criterion != Criterion.AUDIT_LOG_RETENTION || documentedMinimumRetentionDays == null
                    || documentedMinimumRetentionDays < 0 || documentedMinimumRetentionDays > io.authweave.core.catalog.AuditabilityFacts.MAX_RETENTION_DAYS
                    : documentedMinimumRetentionDays != null) throw new IllegalArgumentException("Unbound auditability duration");
            if ((reasonCode == Reason.RETENTION_DURATION_UNKNOWN && criterion != Criterion.AUDIT_LOG_RETENTION)
                    || (reasonCode == Reason.DOCUMENTED_CAPABILITY_AVAILABLE && criterion == Criterion.AUDIT_LOG_RETENTION))
                throw new IllegalArgumentException("Use the criterion-specific auditability reason");
        }
    }

    public record Analysis(Scope optionScope, RequirementCriticality criticality, AuditabilityRequirements requirements,
            Instant evaluatedAt, List<Check> checks) {
        public Analysis {
            Objects.requireNonNull(optionScope); Objects.requireNonNull(criticality);
            Objects.requireNonNull(requirements); Objects.requireNonNull(evaluatedAt); checks = List.copyOf(checks);
            if (checks.size() != Criterion.values().length) throw new IllegalArgumentException("Incomplete auditability analysis");
            for (int i = 0; i < checks.size(); i++) {
                var check = checks.get(i);
                var expected = scopeReason(criticality, requirements, Criterion.values()[i]);
                if (check.criterion() != Criterion.values()[i]
                        || (expected != null ? check.reasonCode() != expected : scopeReasonCode(check.reasonCode()))
                        || (check.reasonCode() == Reason.RETENTION_BELOW_MINIMUM
                            && check.documentedMinimumRetentionDays() >= requirements.minimumRetentionDays())
                        || (check.reasonCode() == Reason.RETENTION_MEETS_MINIMUM
                            && check.documentedMinimumRetentionDays() < requirements.minimumRetentionDays()))
                    throw new IllegalArgumentException("Inconsistent auditability scope or threshold");
            }
        }
        @JsonProperty public Status status() {
            if (checks.stream().anyMatch(c -> c.outcome() == Outcome.FAIL)) return Status.DOES_NOT_MATCH;
            if (checks.stream().anyMatch(c -> c.outcome() == Outcome.UNKNOWN)) return Status.NEEDS_INFORMATION;
            return checks.stream().anyMatch(c -> c.outcome() == Outcome.PASS) ? Status.MATCHES_CHECKED_REQUIREMENTS : Status.NOT_APPLIED;
        }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String analysisBasis() { return "SYNTHETIC_SCOPED_PROVIDER_CAPABILITY_EVIDENCE"; }
        @JsonProperty public List<String> deferredBoundaries() { return DEFERRED_BOUNDARIES; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean complianceVerified() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }

    public static Analysis evaluate(RequirementCriticality criticality, AuditabilityRequirements requirements,
            Scope target, List<Fact> facts, Instant at) {
        Objects.requireNonNull(criticality); Objects.requireNonNull(requirements);
        Objects.requireNonNull(target); Objects.requireNonNull(at);
        var byCriterion = new EnumMap<Criterion, Fact>(Criterion.class);
        // Reject pooled/ambiguous evidence even for unselected criteria or non-required criticalities.
        for (var fact : List.copyOf(facts)) {
            if (!target.equals(fact.scope()) || fact.emitter() != Emitter.IDENTITY_PROVIDER
                    || byCriterion.putIfAbsent(fact.criterion(), fact) != null)
                throw new IllegalArgumentException("Use unique identity-provider facts bound to the exact option scope");
        }
        var checks = Arrays.stream(Criterion.values()).map(criterion -> {
            var reason = scopeReason(criticality, requirements, criterion);
            Integer duration = null;
            if (reason == null) {
                var fact = byCriterion.get(criterion);
                var problem = EvidencePolicy.problem(fact, at);
                if (problem != null) reason = Reason.valueOf(problem.name());
                else return documentedClaim(criterion, fact.support(), fact.documentedMinimumRetentionDays(),
                        criterion == Criterion.AUDIT_LOG_RETENTION ? requirements.minimumRetentionDays() : null);
            }
            return new Check(criterion, outcome(reason), reason, duration);
        }).toList();
        return new Analysis(target, criticality, requirements, at, checks);
    }

    /** Claim arithmetic only, AFTER the caller applies exact scope, source and freshness gates.
     * It does not create a REVIEWED fact or certify logging configuration. */
    public static Check documentedClaim(Criterion criterion, Support support, Integer documentedDays, Integer requestedDays) {
        Objects.requireNonNull(criterion); Objects.requireNonNull(support);
        if (documentedDays != null && (criterion != Criterion.AUDIT_LOG_RETENTION || support != Support.SUPPORTED
                || documentedDays < 0 || documentedDays > AuditabilityRequirements.MAX_RETENTION_DAYS)
                || criterion == Criterion.AUDIT_LOG_RETENTION && (requestedDays == null || requestedDays < 0 || requestedDays > AuditabilityRequirements.MAX_RETENTION_DAYS)
                || criterion != Criterion.AUDIT_LOG_RETENTION && requestedDays != null)
            throw new IllegalArgumentException("Use exact criterion-specific retention thresholds");
        var reason = support == Support.UNSUPPORTED ? Reason.CAPABILITY_UNAVAILABLE : support == Support.UNKNOWN ? Reason.CAPABILITY_UNKNOWN
                : criterion != Criterion.AUDIT_LOG_RETENTION ? Reason.DOCUMENTED_CAPABILITY_AVAILABLE
                : documentedDays == null ? Reason.RETENTION_DURATION_UNKNOWN
                : documentedDays < requestedDays ? Reason.RETENTION_BELOW_MINIMUM : Reason.RETENTION_MEETS_MINIMUM;
        return new Check(criterion, outcome(reason), reason,
                reason == Reason.RETENTION_BELOW_MINIMUM || reason == Reason.RETENTION_MEETS_MINIMUM ? documentedDays : null);
    }

    private static Reason scopeReason(RequirementCriticality criticality, AuditabilityRequirements requirements, Criterion criterion) {
        return switch (criticality) {
            case NOT_REQUIRED -> Reason.NO_REQUIREMENT;
            case PREFERRED -> Reason.PREFERENCE_NOT_SCORED;
            case UNKNOWN -> Reason.REQUIREMENT_UNKNOWN;
            case FORBIDDEN -> Reason.AUDIT_INTENT_UNCLEAR;
            case REQUIRED -> requirements.selectedCriteria().isEmpty() ? Reason.AUDIT_SCOPE_UNKNOWN :
                    !requirements.selectedCriteria().contains(criterion) ? Reason.CRITERION_NOT_SELECTED : null;
        };
    }
    private static boolean scopeReasonCode(Reason reason) {
        return switch (reason) {
            case NO_REQUIREMENT, PREFERENCE_NOT_SCORED, REQUIREMENT_UNKNOWN, AUDIT_INTENT_UNCLEAR,
                    AUDIT_SCOPE_UNKNOWN, CRITERION_NOT_SELECTED -> true;
            default -> false;
        };
    }
    private static Outcome outcome(Reason reason) {
        return switch (reason) {
            case NO_REQUIREMENT, PREFERENCE_NOT_SCORED, CRITERION_NOT_SELECTED -> Outcome.NOT_APPLIED;
            case CAPABILITY_UNAVAILABLE, RETENTION_BELOW_MINIMUM -> Outcome.FAIL;
            case DOCUMENTED_CAPABILITY_AVAILABLE, RETENTION_MEETS_MINIMUM -> Outcome.PASS;
            default -> Outcome.UNKNOWN;
        };
    }
}
