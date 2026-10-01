package io.authweave.core.catalog.publication;

import java.util.HashSet;
import org.springframework.stereotype.Component;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import static io.authweave.core.catalog.publication.CatalogBootstrapReviewException.Reason.INVALID_REQUEST;

/** Completeness means a manual verdict for every recorded fact, not a complete provider catalog or source truth. */
@Component
final class CatalogBootstrapReviewValidator {
    private final CatalogDraftValidator drafts;
    CatalogBootstrapReviewValidator(CatalogDraftValidator drafts) { this.drafts = drafts; }
    CatalogBootstrapReview.Counts validate(CatalogBootstrapReviewRequest request) {
        var candidate = request.candidate();
        if (!request.expectedCandidateSha256().equals(CatalogDraftCanonicalizer.sha256(candidate))) invalid();
        var validation = drafts.validate(candidate);
        if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT || validation.factCount() == 0) invalid();
        var targets = new HashSet<Target>();
        validation.facts().forEach(f -> targets.add(new Target(f.optionId(), f.path())));
        var selected = new HashSet<Target>();
        int supporting = 0, contradicting = 0, insufficient = 0;
        for (var observation : request.observations()) {
            var target = new Target(observation.optionId(), observation.factPath());
            if (!targets.contains(target) || !selected.add(target)) invalid();
            switch (observation.verdict()) {
                case SOURCE_SUPPORTS_CLAIM -> supporting++;
                case SOURCE_DOES_NOT_SUPPORT_CLAIM -> contradicting++;
                case INSUFFICIENT_EVIDENCE -> insufficient++;
            }
        }
        if (!targets.equals(selected)) invalid();
        return new CatalogBootstrapReview.Counts(supporting, contradicting, insufficient);
        // Stale/future evidence can be reviewed, but observations never refresh it or grant publication eligibility.
    }
    private record Target(String optionId, String path) { }
    private static void invalid() { throw new CatalogBootstrapReviewException(INVALID_REQUEST); }
}
