package io.authweave.core.assessment.api;

import java.time.Instant;
import java.util.UUID;

import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.persistence.PersistedAssessment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** profileSchemaVersion describes this representation; history retains its stored format. */
public record AssessmentV3Response(UUID id, UUID workspaceId, AssessmentStatus status, long version,
        Instant createdAt, Instant updatedAt, int profileSchemaVersion, ObjectNode profile) {

    static AssessmentV3Response from(PersistedAssessment persisted, ObjectMapper mapper) {
        var profile = persisted.assessment().profile();
        ObjectNode tree = mapper.valueToTree(profile);
        ((ObjectNode) tree.get("security")).set("dataResidencyDetails",
                mapper.valueToTree(profile.security().dataResidencyDetails()));
        ((ObjectNode) tree.get("security")).set("authenticationControls",
                mapper.valueToTree(profile.security().authenticationControls()));
        return new AssessmentV3Response(persisted.assessment().id().value(), persisted.assessment().workspaceId().value(),
                persisted.assessment().status(), persisted.version(), persisted.createdAt(), persisted.updatedAt(), 3, tree);
    }
}
