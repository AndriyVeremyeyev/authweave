package io.authweave.core.assessment.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssessmentEvent(
        UUID id,
        UUID workspaceId,
        UUID assessmentId,
        long version,
        Long previousVersion,
        String action,
        String actorType,
        String actorId,
        UUID correlationId,
        String outcome,
        List<String> changedSections,
        Instant occurredAt) {

    public AssessmentEvent {
        changedSections = List.copyOf(changedSections);
    }
}
