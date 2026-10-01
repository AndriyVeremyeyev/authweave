package io.authweave.core.catalog.publication;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;

/** A distinct, explicit whole-candidate human assertion; not a proposal revision or a publication. */
public record CatalogBootstrapReviewRequest(int schemaVersion, UUID reviewId, String expectedCandidateSha256,
        ProviderCatalogDraft candidate, List<Observation> observations, Confirmation confirmation) {
    public CatalogBootstrapReviewRequest {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported bootstrap review schema");
        Objects.requireNonNull(reviewId); Objects.requireNonNull(candidate); Objects.requireNonNull(confirmation);
        if (expectedCandidateSha256 == null || !expectedCandidateSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid candidate digest");
        observations = List.copyOf(observations);
        if (observations.isEmpty() || observations.size() > 6800) throw new IllegalArgumentException("Invalid observation count");
    }
    public enum Confirmation { MANUAL_BOOTSTRAP_SOURCE_REVIEW }
    public record Observation(String optionId, String factPath, Verdict verdict) {
        public Observation {
            if (optionId == null || !optionId.matches("[a-z0-9][a-z0-9.-]{0,99}") || factPath == null || factPath.length() > 200
                    || !factPath.matches("(facts\\.[A-Z0-9_]+|compatibility\\.(applications|clients|populations|tenancy|membership)\\.[A-Z0-9_]+|residency\\.[A-Z0-9_]+|authenticationControls\\.[A-Z0-9_]+\\.[A-Z0-9_]+\\.[A-Z0-9_]+)")) {
                throw new IllegalArgumentException("Invalid observation target");
            }
            Objects.requireNonNull(verdict);
        }
    }
}
