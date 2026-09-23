package io.authweave.core.catalog.proposal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Explicit optimistic binding to the current proposal revision; no free-text rationale. */
public record CatalogProposalRejectionRequest(
        @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion,
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String expectedSha256,
        @NotNull ReasonCode reasonCode) {
    public enum ReasonCode { INSUFFICIENT_EVIDENCE, INACCURATE_FACTS, OUT_OF_SCOPE, OTHER }
}
