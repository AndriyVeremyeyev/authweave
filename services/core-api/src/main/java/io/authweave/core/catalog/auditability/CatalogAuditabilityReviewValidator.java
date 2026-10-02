package io.authweave.core.catalog.auditability;

import java.util.HashSet;
import org.springframework.stereotype.Component;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException.Reason.INVALID_REQUEST;

/** Addresses every recorded claim only. Completeness is not truth, all criteria coverage or publication. */
@Component
final class CatalogAuditabilityReviewValidator {
    private final CatalogAuditabilityDraftValidator drafts;
    CatalogAuditabilityReviewValidator(CatalogAuditabilityDraftValidator drafts) { this.drafts = drafts; }
    CatalogAuditabilityReview.Counts validate(CatalogAuditabilityReviewRequest request) {
        var validation = drafts.validate(request.candidate());
        if (validation.status() != CatalogAuditabilityDraftValidator.Status.VALID_DRAFT || validation.targetCount() == 0
                || !request.expectedBaseContentSha256().equals(validation.baseValidation().contentSha256())
                || !request.expectedAuditabilityContentSha256().equals(validation.contentSha256())
                || !request.expectedTargetSetSha256().equals(validation.reviewTargetSetSha256())) invalid();
        var targets = new HashSet<Target>();
        validation.targets().forEach(t -> targets.add(new Target(t.scope().optionId(), t.fact().criterion(), t.targetSha256())));
        var selected = new HashSet<Target>();
        int supporting = 0, contradicting = 0, insufficient = 0;
        for (var observation : request.observations()) {
            var target = new Target(observation.optionId(), observation.criterion(), observation.expectedTargetSha256());
            if (!targets.contains(target) || !selected.add(target)) invalid();
            switch (observation.verdict()) {
                case SOURCE_SUPPORTS_CLAIM -> supporting++;
                case SOURCE_DOES_NOT_SUPPORT_CLAIM -> contradicting++;
                case INSUFFICIENT_EVIDENCE -> insufficient++;
            }
        }
        if (!selected.equals(targets)) invalid();
        // Freshness is deliberately not refreshed or promoted by a manual verdict, including stale/future sources.
        return new CatalogAuditabilityReview.Counts(supporting, contradicting, insufficient);
    }
    private record Target(String optionId, Criterion criterion, String digest) { }
    private static void invalid() { throw new CatalogAuditabilityReviewException(INVALID_REQUEST); }
}
