package io.authweave.core.assessment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record UpdateAssessmentProfileV6Request(
        @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion,
        @NotNull @Valid ApplicationIdentityProfileV6Request profile) { }
