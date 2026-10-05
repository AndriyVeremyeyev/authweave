package io.authweave.core.evaluation;

import java.util.HashSet;
import java.util.Objects;

/** One assessment read and evaluation instant, with one exact audit scope per base option. */
public record AuditabilityConstraintSnapshot(EligibilityPreflightV4 eligibility,
        AuditabilityCapabilityPreflight auditability) {
    public AuditabilityConstraintSnapshot {
        Objects.requireNonNull(eligibility); Objects.requireNonNull(auditability);
        if (!eligibility.workspaceId().equals(auditability.workspaceId())
                || !eligibility.assessmentId().equals(auditability.assessmentId())
                || eligibility.assessmentVersion() != auditability.assessmentVersion()
                || !eligibility.catalogVersion().equals(auditability.baseCatalogVersion())
                || eligibility.catalogKind() != auditability.catalogKind()
                || !eligibility.evaluatedAt().equals(auditability.evaluatedAt())
                || eligibility.candidates().size() != auditability.candidates().size())
            throw new IllegalArgumentException("Unbound auditability constraint snapshot");
        var ids = new HashSet<String>();
        for (var candidate : auditability.candidates()) {
            var scope = candidate.analysis().optionScope();
            var base = eligibility.candidates().stream().filter(c -> c.optionId().equals(scope.optionId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Foreign auditability constraint option"));
            if (!ids.add(scope.optionId()) || !base.plan().equals(scope.plan()) || !base.region().equals(scope.region())
                    || !base.displayName().equals(candidate.displayName()))
                throw new IllegalArgumentException("Ambiguous or mismatched auditability constraint scope");
        }
        if (eligibility.candidates().stream().map(EligibilityPreflightV3.Candidate::optionId).distinct().count() != ids.size())
            throw new IllegalArgumentException("Incomplete auditability constraint inventory");
    }
}
