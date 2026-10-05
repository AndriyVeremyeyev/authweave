package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.authweave.core.catalog.ProviderCatalog;

import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.FAIL;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.UNKNOWN;

/** A reason-coded summary of existing checks, never a score or final recommendation. */
public record HardConstraintPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, Instant evaluatedAt,
        String scope, boolean recommendationReady, List<String> deferredPaths, List<Candidate> candidates,
        @JsonInclude(JsonInclude.Include.NON_NULL) AuditabilityCapabilityPreflight auditability) {

    public static final String POLICY_VERSION = "hard-constraint-preflight-1";
    public static final String AUDITABILITY_POLICY_VERSION = "hard-constraint-preflight-2";

    public HardConstraintPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion,
            String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, Instant evaluatedAt,
            String scope, boolean recommendationReady, List<String> deferredPaths, List<Candidate> candidates) {
        this(workspaceId, assessmentId, assessmentVersion, catalogVersion, catalogKind, policyVersion, evaluatedAt,
                scope, recommendationReady, deferredPaths, candidates, null);
    }

    public HardConstraintPreflight {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }

    public enum Verdict { EXCLUDED, UNRESOLVED, PASSES_CHECKED_REQUIREMENTS }
    public enum Dimension { CAPABILITY, CONTEXT, RESIDENCY, AUTHENTICATION_CONTROL, COMPLIANCE_SCOPE, COVERAGE, AUDITABILITY }

    public record Finding(Dimension dimension, String profilePath, String reasonCode, String explanation) { }

    public record Candidate(String optionId, String displayName, String plan, String region,
            Verdict verdict, List<Finding> exclusionReasons, List<Finding> informationGaps) {
        public Candidate {
            exclusionReasons = List.copyOf(exclusionReasons);
            informationGaps = List.copyOf(informationGaps);
        }
    }

    public static HardConstraintPreflight from(EligibilityPreflightV4 source) {
        var candidates = source.candidates().stream().map(candidate -> summarize(candidate, source.complianceScopeCheck())).toList();
        return new HardConstraintPreflight(source.workspaceId(), source.assessmentId(), source.assessmentVersion(),
                source.catalogVersion(), source.catalogKind(), POLICY_VERSION, source.evaluatedAt(),
                "SYNTHETIC_HARD_CONSTRAINT_PREFLIGHT", false, source.deferredPaths(), candidates);
    }

    public static HardConstraintPreflight from(AuditabilityConstraintSnapshot snapshot) {
        var source = snapshot.eligibility();
        var audit = snapshot.auditability();
        var candidates = from(source).candidates().stream().map(candidate -> {
            var analysis = audit.candidates().stream()
                    .filter(c -> c.analysis().optionScope().optionId().equals(candidate.optionId())).findFirst().orElseThrow().analysis();
            var excluded = new ArrayList<>(candidate.exclusionReasons());
            var gaps = new ArrayList<>(candidate.informationGaps());
            if (analysis.checks().stream().anyMatch(c -> c.outcome() == CapabilityPreflight.Outcome.PASS))
                gaps.removeIf(f -> f.dimension() == Dimension.COVERAGE && f.reasonCode().equals("NO_AFFIRMATIVE_CHECKS"));
            for (var check : analysis.checks()) {
                String path = switch (check.reasonCode()) {
                    case REQUIREMENT_UNKNOWN, AUDIT_INTENT_UNCLEAR -> "security.auditability";
                    default -> "security.auditabilityRequirements";
                };
                add(excluded, gaps, check.outcome(), new Finding(Dimension.AUDITABILITY, path,
                        check.reasonCode().name(), auditExplanation(check, analysis.requirements().minimumRetentionDays())));
            }
            var verdict = !excluded.isEmpty() ? Verdict.EXCLUDED : !gaps.isEmpty() ? Verdict.UNRESOLVED : Verdict.PASSES_CHECKED_REQUIREMENTS;
            return new Candidate(candidate.optionId(), candidate.displayName(), candidate.plan(), candidate.region(), verdict, excluded, gaps);
        }).toList();
        // The capability checks are now active; deployed logging and compliance remain deferred.
        return new HardConstraintPreflight(source.workspaceId(), source.assessmentId(), source.assessmentVersion(),
                source.catalogVersion(), source.catalogKind(), AUDITABILITY_POLICY_VERSION, source.evaluatedAt(),
                "SYNTHETIC_HARD_CONSTRAINT_PREFLIGHT", false, source.deferredPaths(), candidates, audit);
    }

    private static String auditExplanation(AuditabilityEvaluator.Check check, Integer requestedDays) {
        String explanation = switch (check.reasonCode()) {
            case REQUIREMENT_UNKNOWN -> "Decide whether auditability is required before interpreting provider capabilities.";
            case AUDIT_INTENT_UNCLEAR -> "Clarify the forbidden auditability intent; disabling logs is not inferred.";
            case AUDIT_SCOPE_UNKNOWN -> "Auditability is required, but no criteria are selected. Empty scope is not an exemption.";
            case EVIDENCE_MISSING -> "No fact is recorded for this exact option scope; missing does not mean unsupported.";
            case EVIDENCE_UNREVIEWED -> "The synthetic fact is unreviewed and cannot establish support or incompatibility.";
            case EVIDENCE_FROM_FUTURE -> "The synthetic fact is dated after evaluation and cannot be used.";
            case EVIDENCE_STALE -> "The synthetic fact is more than 90 days old and cannot establish support or incompatibility.";
            case CAPABILITY_UNKNOWN -> "The usable synthetic fact records unknown capability support.";
            case CAPABILITY_UNAVAILABLE -> "The usable synthetic fact records unsupported capability in this exact option scope.";
            case RETENTION_DURATION_UNKNOWN -> "Retention is supported, but no documented minimum duration is recorded.";
            case RETENTION_BELOW_MINIMUM -> "Documented minimum retention of " + check.documentedMinimumRetentionDays()
                    + " days is below the requested " + requestedDays + " days; deployed retention is not verified.";
            default -> "No hard-constraint finding is produced for this criterion.";
        };
        return scoped(check.criterion().name(), explanation);
    }

    private static Candidate summarize(EligibilityPreflightV3.Candidate candidate, ComplianceScopeCheck compliance) {
        var excluded = new ArrayList<Finding>();
        var unknown = new ArrayList<Finding>();
        for (var check : candidate.capabilityChecks()) {
            add(excluded, unknown, check.outcome(), new Finding(Dimension.CAPABILITY,
                    check.profilePath(), check.reasonCode().name(),
                    scoped(check.capability().name(), check.explanation())));
        }
        for (var check : candidate.contextChecks()) {
            add(excluded, unknown, check.outcome(), new Finding(Dimension.CONTEXT,
                    check.profilePath(), check.reasonCode().name(),
                    check.requestedValue() == null ? check.explanation()
                            : scoped(check.requestedValue(), check.explanation())));
        }
        for (var check : candidate.residencyChecks()) {
            String scope = check.dataCategory() == null ? null : check.dataCategory().name();
            if (scope != null && !check.outsideAllowedCountries().isEmpty()) {
                scope += " (observed outside allowlist: " + String.join(", ", check.outsideAllowedCountries()) + ")";
            }
            add(excluded, unknown, check.outcome(), new Finding(Dimension.RESIDENCY,
                    check.profilePath(), check.reasonCode().name(),
                    scope == null ? check.explanation() : scoped(scope, check.explanation())));
        }
        for (var check : candidate.authenticationControlChecks()) {
            String scope = check.control().name();
            if (check.client() != null) {
                scope += " / " + check.client().name();
            }
            if (check.population() != null) {
                scope += " / " + check.population().name();
            }
            add(excluded, unknown, check.outcome(), new Finding(Dimension.AUTHENTICATION_CONTROL,
                    check.profilePath(), check.reasonCode().name(), scoped(scope, check.explanation())));
        }
        add(excluded, unknown, compliance.outcome(), new Finding(Dimension.COMPLIANCE_SCOPE,
                compliance.profilePath(), compliance.reasonCode().name(), compliance.explanation()));
        var verdict = switch (candidate.status()) {
            case DOES_NOT_MATCH -> Verdict.EXCLUDED;
            case NEEDS_INFORMATION -> Verdict.UNRESOLVED;
            case MATCHES_CHECKED_REQUIREMENTS -> Verdict.PASSES_CHECKED_REQUIREMENTS;
        };
        if (verdict == Verdict.EXCLUDED && excluded.isEmpty()) {
            throw new IllegalStateException("An excluded option must have a failed checked constraint");
        }
        if (verdict == Verdict.UNRESOLVED && unknown.isEmpty()) {
            unknown.add(new Finding(Dimension.COVERAGE, "assessment", "NO_AFFIRMATIVE_CHECKS",
                    "No checked requirement establishes a positive match; clarify the assessment before comparison."));
        }
        if (verdict == Verdict.PASSES_CHECKED_REQUIREMENTS && (!excluded.isEmpty() || !unknown.isEmpty())) {
            throw new IllegalStateException("A passing option cannot have failed or unknown checked constraints");
        }
        return new Candidate(candidate.optionId(), candidate.displayName(), candidate.plan(), candidate.region(),
                verdict, excluded, unknown);
    }

    private static void add(List<Finding> excluded, List<Finding> unknown,
            CapabilityPreflight.Outcome outcome, Finding finding) {
        if (outcome == FAIL) excluded.add(finding);
        if (outcome == UNKNOWN) unknown.add(finding);
    }

    private static String scoped(String scope, String explanation) {
        return scope + ": " + explanation;
    }
}
