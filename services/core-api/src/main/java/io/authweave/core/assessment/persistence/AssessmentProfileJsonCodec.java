package io.authweave.core.assessment.persistence;

import org.jooq.JSONB;
import org.springframework.stereotype.Component;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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

    ApplicationIdentityProfile decode(JSONB profile) {
        try {
            return objectMapper.readValue(profile.data(), ApplicationIdentityProfile.class);
        } catch (JacksonException exception) {
            throw new AssessmentProfileSerializationException(
                    "Could not deserialize the application identity profile",
                    exception);
        }
    }
}
