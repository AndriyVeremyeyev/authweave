package io.authweave.core.assessment.domain;

import java.util.Objects;
import java.util.UUID;

public record AssessmentId(UUID value) {

    public AssessmentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static AssessmentId generate() {
        return new AssessmentId(UUID.randomUUID());
    }
}
