package io.authweave.core.assessment.persistence;

import org.jooq.JSONB;
import org.springframework.stereotype.Component;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Component
final class AssessmentProfileJsonCodec {

    private final ObjectMapper objectMapper;

    AssessmentProfileJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JSONB encode(ApplicationIdentityProfile profile) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(profile));
        } catch (JacksonException exception) {
            throw new AssessmentProfileSerializationException(
                    "Could not serialize the application identity profile",
                    exception);
        }
    }

    short schemaVersion(ApplicationIdentityProfile profile) {
        return (short) (profile.security().dataResidencyDetails().isUnrecorded() ? 1 : 2);
    }

    JsonNode snapshot(JSONB profile, short version) {
        if (version != 1 && version != 2) throw new UnsupportedAssessmentProfileVersionException(version);
        var node = objectMapper.readTree(profile.data());
        var details = node.path("security").get("dataResidencyDetails");
        if ((version == 1 && details != null) || (version == 2 && (details == null || details.isNull()))) {
            throw new AssessmentProfileSerializationException("Profile does not match its stored schema version",
                    new IllegalArgumentException("Residency details presence does not match schema version"));
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
