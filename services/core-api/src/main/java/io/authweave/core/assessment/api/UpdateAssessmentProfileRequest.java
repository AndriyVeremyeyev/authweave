package io.authweave.core.assessment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateAssessmentProfileRequest(
        @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion,
        @NotNull @Valid ApplicationIdentityProfileRequest profile) {
}
