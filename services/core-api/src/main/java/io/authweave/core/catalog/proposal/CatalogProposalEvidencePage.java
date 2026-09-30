package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.draft.CatalogChangePreview;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;

/** Current date review of recorded candidate evidence for an immutable proposal revision. */
public record CatalogProposalEvidencePage(UUID proposalId, long proposalVersion, String proposalSha256,
        String catalogVersion, Instant evaluatedAt, String policyVersion, long maxEvidenceAgeDays,
        boolean sourceVerificationPerformed, boolean approvalGranted, boolean writesPerformed,
        boolean evaluationReady, int factCount, CatalogChangePreview.FreshnessCounts freshness,
        int offset, List<Item> items, Integer nextOffset) {
    public CatalogProposalEvidencePage { items = List.copyOf(items); }
    public record Item(String optionId, String path, CatalogChangePreview.OptionScope scope, CatalogFactClaim claim,
            CatalogDraftValidation.ReviewStatus evidenceStatus, CatalogDraftValidation.Freshness freshness,
            List<String> conditions, ProviderCatalogDraft.Evidence evidence) {
        public Item { conditions = List.copyOf(conditions); }
    }
}
