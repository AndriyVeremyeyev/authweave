package io.authweave.core.assessment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateAssessmentProfileV4Request(
        @NotNull @PositiveOrZero @jakarta.validation.constraints.Max(9007199254740991L) Long expectedVersion,
        @NotNull @Valid ApplicationIdentityProfileV4Request profile) { }
