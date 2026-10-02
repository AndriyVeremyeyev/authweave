package io.authweave.core.assessment.api;

import java.time.Instant;
import java.util.UUID;
import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.persistence.PersistedAssessment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A v6 read projects unrecorded legacy inputs; it never migrates stored snapshots. */
public record AssessmentV6Response(UUID id, UUID workspaceId, AssessmentStatus status, long version,
        Instant createdAt, Instant updatedAt, int profileSchemaVersion, ObjectNode profile) {
    static AssessmentV6Response from(PersistedAssessment persisted, ObjectMapper mapper) {
        var profile = persisted.assessment().profile();
        ObjectNode tree = mapper.valueToTree(profile);
        var security = (ObjectNode) tree.get("security");
        security.set("dataResidencyDetails", mapper.valueToTree(profile.security().dataResidencyDetails()));
        security.set("authenticationControls", mapper.valueToTree(profile.security().authenticationControls()));
        security.set("complianceScopeStatus", mapper.valueToTree(profile.security().complianceScopeStatus()));
        security.set("auditabilityRequirements", mapper.valueToTree(profile.security().auditabilityRequirements()));
        ((ObjectNode) tree.get("operations")).set("usagePlanning", mapper.valueToTree(profile.operations().usagePlanning()));
        return new AssessmentV6Response(persisted.assessment().id().value(), persisted.assessment().workspaceId().value(),
                persisted.assessment().status(), persisted.version(), persisted.createdAt(), persisted.updatedAt(), 6, tree);
    }
}
