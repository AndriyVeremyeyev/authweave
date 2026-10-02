package io.authweave.core.assessment.persistence;

import org.jooq.JSONB;
import org.springframework.stereotype.Component;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class AssessmentProfileJsonCodec {

    private final ObjectMapper objectMapper;

    AssessmentProfileJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JSONB encode(ApplicationIdentityProfile profile) {
        try {
            ObjectNode tree = objectMapper.valueToTree(profile);
            if (schemaVersion(profile) >= 3) {
                ((ObjectNode) tree.get("security")).set("dataResidencyDetails",
                        objectMapper.valueToTree(profile.security().dataResidencyDetails()));
            }
            if (schemaVersion(profile) >= 4) {
                ((ObjectNode) tree.get("security")).set("authenticationControls",
                        objectMapper.valueToTree(profile.security().authenticationControls()));
            }
            if (schemaVersion(profile) >= 5) {
                ((ObjectNode) tree.get("security")).set("complianceScopeStatus",
                        objectMapper.valueToTree(profile.security().complianceScopeStatus()));
            }
            if (schemaVersion(profile) >= 6) {
                ((ObjectNode) tree.get("operations")).set("usagePlanning",
                        objectMapper.valueToTree(profile.operations().usagePlanning()));
                ((ObjectNode) tree.get("security")).set("auditabilityRequirements",
                        objectMapper.valueToTree(profile.security().auditabilityRequirements()));
            }
            return JSONB.valueOf(objectMapper.writeValueAsString(tree));
        } catch (JacksonException exception) {
            throw new AssessmentProfileSerializationException(
                    "Could not serialize the application identity profile",
                    exception);
        }
    }

    short schemaVersion(ApplicationIdentityProfile profile) {
        return profile.minimumSchemaVersion();
    }

    JsonNode snapshot(JSONB profile, short version) {
        if (version < 1 || version > 6) throw new UnsupportedAssessmentProfileVersionException(version);
        var node = objectMapper.readTree(profile.data());
        var details = node.path("security").get("dataResidencyDetails");
        var controls = node.path("security").get("authenticationControls");
        var complianceScope = node.path("security").get("complianceScopeStatus");
        var usagePlanning = node.path("operations").get("usagePlanning");
        var auditability = node.path("security").get("auditabilityRequirements");
        if ((version == 1 && details != null) || (version >= 2 && (details == null || details.isNull()))
                || (version < 3 && controls != null) || (version >= 3 && (controls == null || controls.isNull()))
                || (version < 4 && complianceScope != null) || (version >= 4 && (complianceScope == null || complianceScope.isNull()))
                || (version < 5 && usagePlanning != null) || (version >= 5 && (usagePlanning == null || usagePlanning.isNull()))
                || (version < 6 && auditability != null) || (version == 6 && (auditability == null || auditability.isNull()))) {
            throw new AssessmentProfileSerializationException("Profile does not match its stored schema version",
                    new IllegalArgumentException("Profile details presence does not match schema version"));
        }
        return node;
    }

    ApplicationIdentityProfile decode(JSONB profile, short version) {
        try {
            return objectMapper.treeToValue(snapshot(profile, version), ApplicationIdentityProfile.class);
        } catch (JacksonException exception) {
            throw new AssessmentProfileSerializationException(
                    "Could not deserialize the application identity profile",
                    exception);
        }
    }
}
