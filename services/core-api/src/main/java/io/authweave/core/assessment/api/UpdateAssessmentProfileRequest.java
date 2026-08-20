package io.authweave.core.assessment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;

public record UpdateAssessmentProfileRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @Valid ApplicationIdentityProfile profile) {
}
