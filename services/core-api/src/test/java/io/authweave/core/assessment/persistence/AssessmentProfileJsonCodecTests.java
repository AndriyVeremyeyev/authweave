package io.authweave.core.assessment.persistence;

import java.util.Set;

import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

import io.authweave.core.assessment.domain.profile.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentProfileJsonCodecTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AssessmentProfileJsonCodec codec = new AssessmentProfileJsonCodec(mapper);

    @Test
    void usesTheSmallestLosslessFormatAndRejectsMislabeledSnapshots() {
        var legacy = ApplicationIdentityProfile.unknown();
        var oldJson = codec.encode(legacy);
        assertEquals(1, codec.schemaVersion(legacy));
        assertFalse(mapper.readTree(oldJson.data()).get("security").has("dataResidencyDetails"));
        assertEquals(legacy, codec.decode(oldJson, (short) 1));
        assertEquals(DataResidencyDetails.unknown(), codec.decode(oldJson, (short) 1).security().dataResidencyDetails());

        var security = legacy.security();
        var expanded = new ApplicationIdentityProfile(legacy.application(), legacy.audience(), legacy.protocols(),
                legacy.provisioning(), new SecurityRequirements(security.multiFactorAuthentication(),
                security.browserTokenExposureMinimization(), security.auditability(), RequirementCriticality.REQUIRED,
                security.assurance(), security.complianceTargets(),
                new DataResidencyDetails(Set.of("DE"), Set.of(DataResidencyDetails.DataCategory.USER_PROFILES))), legacy.operations());
        var expandedJson = codec.encode(expanded);
        assertEquals(2, codec.schemaVersion(expanded));
        assertEquals(expanded, codec.decode(expandedJson, (short) 2));
        assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(expandedJson, (short) 1));
        assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(oldJson, (short) 2));
        assertThrows(UnsupportedAssessmentProfileVersionException.class, () -> codec.decode(oldJson, (short) 3));
        ObjectNode malformed = (ObjectNode) mapper.readTree(expandedJson.data()).deepCopy();
        ((ObjectNode) malformed.get("security")).putNull("dataResidencyDetails");
        assertThrows(AssessmentProfileSerializationException.class,
                () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 2));
    }

    @Test
    void historicalSnapshotsAreReadWithoutCurrentDomainValidationOrUpcasting() {
        var profile = mapper.valueToTree(ApplicationIdentityProfile.unknown());
        ((ObjectNode) profile.get("application")).put("type", "FUTURE_CLASSIFICATION");
        var json = JSONB.valueOf(mapper.writeValueAsString(profile));
        assertEquals(profile, codec.snapshot(json, (short) 1));
        assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(json, (short) 1));
    }
}
