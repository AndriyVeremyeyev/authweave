package io.authweave.core.assessment.persistence;

import java.time.Instant;
import java.util.UUID;

import io.authweave.core.assessment.domain.AssessmentStatus;

public record AssessmentListItem(UUID id, AssessmentStatus status, long version,
        Instant createdAt, Instant updatedAt) {
}
