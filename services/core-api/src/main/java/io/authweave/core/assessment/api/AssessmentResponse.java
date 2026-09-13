package io.authweave.core.assessment.api;

import java.time.Instant;
import java.util.UUID;

import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.persistence.PersistedAssessment;

public record AssessmentResponse(
        UUID id,
        UUID workspaceId,
        AssessmentStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        ApplicationIdentityProfile profile) {

    static AssessmentResponse from(PersistedAssessment persisted) {
        if (!persisted.assessment().profile().security().dataResidencyDetails().isUnrecorded()) {
            throw new io.authweave.core.assessment.application.ProfileUpgradeRequiredException();
        }
        return new AssessmentResponse(
                persisted.assessment().id().value(),
                persisted.assessment().workspaceId().value(),
                persisted.assessment().status(),
                persisted.version(),
                persisted.createdAt(),
                persisted.updatedAt(),
                persisted.assessment().profile());
    }
}
