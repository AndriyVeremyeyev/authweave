package io.authweave.core.evaluation;

import java.time.Duration;
import java.time.Instant;

import io.authweave.core.catalog.ProviderCatalog.Evidence;
import io.authweave.core.catalog.ProviderCatalog.EvidenceStatus;

/** Shared evidence gate: absence or unusable evidence cannot establish support or incompatibility. */
public final class EvidencePolicy {
    public static final Duration MAX_AGE = Duration.ofDays(90);
    private EvidencePolicy() { }

    public enum Problem {
        EVIDENCE_MISSING("No fact is recorded for this plan, region and requirement."),
        EVIDENCE_UNREVIEWED("The fact has not been reviewed and cannot establish a match or exclusion."),
        EVIDENCE_FROM_FUTURE("The observation is later than the evaluation instant and cannot be used."),
        EVIDENCE_STALE("The observation is older than the 90-day policy; review it before deciding.");

        private final String explanation;
        Problem(String explanation) { this.explanation = explanation; }
        public String explanation() { return explanation; }
    }

    public static Problem problem(Evidence evidence, Instant at) {
        if (evidence == null) return Problem.EVIDENCE_MISSING;
        if (evidence.evidenceStatus() != EvidenceStatus.REVIEWED) return Problem.EVIDENCE_UNREVIEWED;
        if (evidence.observedAt().isAfter(at)) return Problem.EVIDENCE_FROM_FUTURE;
        if (evidence.observedAt().isBefore(at.minus(MAX_AGE))) return Problem.EVIDENCE_STALE;
        return null;
    }
}
