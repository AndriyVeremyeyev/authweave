package io.authweave.core.assessment.persistence;

import java.time.Instant;
import java.util.Objects;

import io.authweave.core.assessment.domain.Assessment;

public record PersistedAssessment(
        Assessment assessment,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public PersistedAssessment {
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
