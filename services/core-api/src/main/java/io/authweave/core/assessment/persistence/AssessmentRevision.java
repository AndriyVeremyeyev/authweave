package io.authweave.core.assessment.persistence;

import java.time.Instant;
import java.util.UUID;

import io.authweave.core.assessment.domain.AssessmentStatus;
import tools.jackson.databind.JsonNode;

/** A historical snapshot, not a mutable aggregate revalidated against today's rules. */
public record AssessmentRevision(
        UUID workspaceId,
        UUID assessmentId,
        long version,
        AssessmentStatus status,
        short profileSchemaVersion,
        JsonNode profile,
        Origin origin,
        Instant recordedAt) {

    public enum Origin { CREATED, UPDATED, BASELINE }
}
