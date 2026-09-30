package io.authweave.core.catalog.proposal;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** An explicit human observation, not a fact-trust or publication command. */
public record CatalogFactReviewRequest(
        @NotNull UUID reviewId,
        @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion,
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedSha256,
        @NotNull @Pattern(regexp = "[a-z0-9][a-z0-9.-]{0,99}") String optionId,
        @NotNull @Pattern(regexp = "(facts\\.[A-Z0-9_]+|compatibility\\.(applications|clients|populations|tenancy|membership)\\.[A-Z0-9_]+|residency\\.[A-Z0-9_]+|authenticationControls\\.[A-Z0-9_]+\\.[A-Z0-9_]+\\.[A-Z0-9_]+)")
        @jakarta.validation.constraints.Size(max = 200) String factPath,
        @NotNull Verdict verdict,
        @NotNull Confirmation confirmation) {
    public enum Verdict { SOURCE_SUPPORTS_CLAIM, SOURCE_DOES_NOT_SUPPORT_CLAIM, INSUFFICIENT_EVIDENCE }
    public enum Confirmation { MANUAL_SOURCE_REVIEW }
}
