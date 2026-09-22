package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.authweave.core.catalog.ProviderCatalog;

import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.FAIL;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.UNKNOWN;

/** A reason-coded summary of existing checks, never a score or final recommendation. */
public record HardConstraintPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion, Instant evaluatedAt,
        String scope, boolean recommendationReady, List<String> deferredPaths, List<Candidate> candidates) {

    public static final String POLICY_VERSION = "hard-constraint-preflight-1";

    public HardConstraintPreflight {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }

    public enum Verdict { EXCLUDED, UNRESOLVED, PASSES_CHECKED_REQUIREMENTS }
    public enum Dimension { CAPABILITY, CONTEXT, RESIDENCY, AUTHENTICATION_CONTROL, COMPLIANCE_SCOPE, COVERAGE }

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

    private static Candidate summarize(EligibilityPreflightV3.Candidate candidate, ComplianceScopeCheck compliance) {
        var excluded = new ArrayList<Finding>();
        var unknown = new ArrayList<Finding>();
        for (var check : candidate.capabilityChecks()) {
            add(excluded, unknown, check.outcome(), new Finding(Dimension.CAPABILITY,
                    check.profilePath(), check.reasonCode().name(), check.explanation()));
        }
        for (var check : candidate.contextChecks()) {
            add(excluded, unknown, check.outcome(), new Finding(Dimension.CONTEXT,
                    check.profilePath(), check.reasonCode().name(), check.explanation()));
        }
        for (var check : candidate.residencyChecks()) {
            add(excluded, unknown, check.outcome(), new Finding(Dimension.RESIDENCY,
                    check.profilePath(), check.reasonCode().name(), check.explanation()));
        }
        for (var check : candidate.authenticationControlChecks()) {
            add(excluded, unknown, check.outcome(), new Finding(Dimension.AUTHENTICATION_CONTROL,
                    check.profilePath(), check.reasonCode().name(), check.explanation()));
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
}
