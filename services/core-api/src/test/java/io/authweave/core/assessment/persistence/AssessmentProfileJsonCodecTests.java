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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void evaluationProjectionPreservesEveryExistingWireArrayAndOnlyAddsMissingLegacyFields(int version) throws Exception {
        tools.jackson.databind.JsonNode fixture;
        try (var input = new org.springframework.core.io.ClassPathResource("catalog/scoped-impact-scenarios.v1.json").getInputStream()) { fixture = mapper.readTree(input); }
        var original = (ObjectNode) fixture.get(0).get("profile").deepCopy();
        ((ObjectNode) original.at("/security/dataResidencyDetails")).set("allowedCountries", mapper.readTree("[\"FR\",\"DE\"]"));
        ((ObjectNode) original.get("security")).set("complianceTargets", mapper.readTree("[\"GDPR\",\"SOC_2\"]"));
        ((ObjectNode) original.get("security")).put("complianceScopeStatus", "TARGETS_IDENTIFIED");
        ((ObjectNode) original.get("security")).set("auditabilityRequirements", mapper.readTree(
                "{\"selectedCriteria\":[\"AUDIT_LOG_RETENTION\",\"AUTHENTICATION_FAILURE_EVENTS\"],\"minimumRetentionDays\":30}"));
        // Domain inputs are sets, but exact wire/profile hashes retain their original array order.
        // Both opposing orders must survive; the old Set round-trip cannot satisfy both in any JVM.
        for (int permutation = 0; permutation < 2; permutation++) {
            var input = original.deepCopy();
            if (permutation == 1) for (String path : new String[] {"/application/clients", "/audience/populations",
                    "/security/dataResidencyDetails/dataCategories", "/security/dataResidencyDetails/allowedCountries",
                    "/security/complianceTargets", "/security/auditabilityRequirements/selectedCriteria"}) {
                var array = (tools.jackson.databind.node.ArrayNode) input.at(path); var values = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
                array.forEach(values::add); java.util.Collections.reverse(values); array.removeAll(); values.forEach(array::add);
            }
            var security = (ObjectNode) input.get("security");
            if (version < 2) security.remove("dataResidencyDetails");
            if (version < 3) security.remove("authenticationControls");
            if (version < 4) security.remove("complianceScopeStatus");
            if (version < 5) ((ObjectNode) input.get("operations")).remove("usagePlanning");
            if (version < 6) security.remove("auditabilityRequirements");
            var stored = JSONB.valueOf(mapper.writeValueAsString(input)); var before = stored.data();
            var decoded = codec.decode(stored, (short) version); var expected = input.deepCopy();
            var expectedSecurity = (ObjectNode) expected.get("security");
            if (version < 2) expectedSecurity.set("dataResidencyDetails", mapper.valueToTree(DataResidencyDetails.unknown()));
            if (version < 3) expectedSecurity.set("authenticationControls", mapper.valueToTree(AuthenticationControls.unknown()));
            if (version < 4) expectedSecurity.put("complianceScopeStatus", "UNKNOWN");
            if (version < 5) ((ObjectNode) expected.get("operations")).set("usagePlanning", mapper.valueToTree(UsagePlanning.unknown()));
            if (version < 6) expectedSecurity.set("auditabilityRequirements", mapper.valueToTree(AuditabilityRequirements.unspecified()));
            var projected = codec.evaluationSnapshot(stored, (short) version);
            assertEquals(expected, projected); assertEquals(before, stored.data()); assertEquals(input, codec.snapshot(stored, (short) version));
            assertEquals(decoded, mapper.treeToValue(projected, ApplicationIdentityProfile.class));
            assertEquals(projected, codec.evaluationSnapshot(stored, (short) version));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(AuditabilityRequirements.Criterion.class)
    void everyExplicitAuditCriterionUsesV6AndCannotBeMislabeledAsAnOlderSnapshot(AuditabilityRequirements.Criterion criterion) {
        var base = ApplicationIdentityProfile.unknown(); var s = base.security();
        var requirements = new AuditabilityRequirements(Set.of(criterion),
                criterion == AuditabilityRequirements.Criterion.AUDIT_LOG_RETENTION ? 30 : null);
        var security = new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(),
                RequirementCriticality.REQUIRED, s.dataResidency(), s.assurance(), s.complianceTargets(),
                s.dataResidencyDetails(), s.authenticationControls(), s.complianceScopeStatus(), requirements);
        var profile = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(), security, base.operations());
        var encoded = codec.encode(profile);
        assertEquals(6, profile.minimumSchemaVersion()); assertEquals(6, codec.schemaVersion(profile));
        assertEquals(profile, codec.decode(encoded, (short) 6));
        assertEquals(encoded, codec.encode(codec.decode(encoded, (short) 6)));
        for (short older : new short[] {1, 2, 3, 4, 5})
            assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(encoded, older));
        for (String path : new String[] {"security/auditabilityRequirements", "security/dataResidencyDetails",
                "security/authenticationControls", "security/complianceScopeStatus", "operations/usagePlanning"}) {
            var parts = path.split("/"); var malformed = (ObjectNode) mapper.readTree(encoded.data());
            ((ObjectNode) malformed.get(parts[0])).remove(parts[1]);
            assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(JSONB.valueOf(malformed.toString()), (short) 6));
            ((ObjectNode) malformed.get(parts[0])).putNull(parts[1]);
            assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(JSONB.valueOf(malformed.toString()), (short) 6));
        }
        var malformed = (ObjectNode) mapper.readTree(encoded.data());
        ((ObjectNode) malformed.at("/security/auditabilityRequirements")).remove("minimumRetentionDays");
        assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(JSONB.valueOf(malformed.toString()), (short) 6));
        var o = base.operations();
        var withUsage = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(), security,
                new OperationalConstraints(o.hosting(), o.deploymentTarget(), o.identityExpertise(), o.budgetSensitivity(),
                        new UsagePlanning("Synthetic pilot", java.util.List.of(), java.util.Map.of())));
        assertEquals(6, codec.schemaVersion(withUsage)); assertEquals(withUsage, codec.decode(codec.encode(withUsage), (short) 6));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5})
    void legacyAuditabilityCriticalityNeverInfersDetailedScopeOrMutatesItsStoredBytes(int version) {
        var base = ApplicationIdentityProfile.unknown(); var s = base.security(); var o = base.operations();
        var profile = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), RequirementCriticality.REQUIRED,
                        s.dataResidency(), s.assurance(), s.complianceTargets(),
                        version == 2 ? new DataResidencyDetails(Set.of("DE"), Set.of()) : s.dataResidencyDetails(),
                        version == 3 ? new AuthenticationControls(RequirementCriticality.REQUIRED, RequirementCriticality.UNKNOWN, RequirementCriticality.UNKNOWN) : s.authenticationControls(),
                        version == 4 ? ComplianceScopeStatus.NONE_IDENTIFIED : s.complianceScopeStatus()),
                version == 5 ? new OperationalConstraints(o.hosting(), o.deploymentTarget(), o.identityExpertise(), o.budgetSensitivity(),
                        new UsagePlanning("Synthetic pilot", java.util.List.of(), java.util.Map.of())) : o);
        var encoded = codec.encode(profile); assertEquals(version, codec.schemaVersion(profile));
        var decoded = codec.decode(encoded, (short) version);
        assertEquals(AuditabilityRequirements.unspecified(), decoded.security().auditabilityRequirements());
        assertTrue(decoded.security().auditabilityRequirements().isUnrecorded());
        assertFalse(mapper.readTree(encoded.data()).at("/security").has("auditabilityRequirements"));
        assertEquals(encoded, codec.encode(decoded)); assertEquals(profile, decoded);
    }

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
        assertThrows(UnsupportedAssessmentProfileVersionException.class, () -> codec.decode(oldJson, (short) 7));
        ObjectNode malformed = (ObjectNode) mapper.readTree(expandedJson.data()).deepCopy();
        ((ObjectNode) malformed.get("security")).putNull("dataResidencyDetails");
        assertThrows(AssessmentProfileSerializationException.class,
                () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 2));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    void legacyFormatsKeepBothEmptyAndNonEmptyTargetsWithoutInferringScope(int format) {
        var base = ApplicationIdentityProfile.unknown();
        var s = base.security();
        for (var targets : java.util.List.of(Set.<SecurityRequirements.ComplianceTarget>of(), Set.of(SecurityRequirements.ComplianceTarget.GDPR))) {
            var profile = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                    new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), s.auditability(),
                    s.dataResidency(), s.assurance(), targets,
                    format == 2 ? new DataResidencyDetails(Set.of("DE"), Set.of()) : DataResidencyDetails.unknown(),
                    format == 3 ? new AuthenticationControls(RequirementCriticality.REQUIRED, RequirementCriticality.UNKNOWN,
                            RequirementCriticality.UNKNOWN) : AuthenticationControls.unknown()), base.operations());
            var encoded = codec.encode(profile);
            assertEquals(format, codec.schemaVersion(profile));
            assertFalse(mapper.readTree(encoded.data()).get("security").has("complianceScopeStatus"));
            var decoded = codec.decode(encoded, (short) format);
            assertEquals(profile, decoded);
            assertEquals(ComplianceScopeStatus.UNKNOWN, decoded.security().complianceScopeStatus());
            assertEquals(targets, decoded.security().complianceTargets());
            assertEquals(encoded, codec.encode(decoded));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ComplianceScopeStatus.class, names = {"NONE_IDENTIFIED", "TARGETS_IDENTIFIED"})
    void recordedScopeUsesV4AndRejectsMislabeledOrIncompleteSnapshots(ComplianceScopeStatus scope) {
        var base = ApplicationIdentityProfile.unknown();
        var s = base.security();
        var profile = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), s.auditability(),
                s.dataResidency(), s.assurance(), scope == ComplianceScopeStatus.TARGETS_IDENTIFIED
                        ? Set.of(SecurityRequirements.ComplianceTarget.SOC_2) : Set.of(),
                s.dataResidencyDetails(), s.authenticationControls(), scope), base.operations());
        var encoded = codec.encode(profile);
        assertEquals(4, codec.schemaVersion(profile));
        assertEquals(profile, codec.decode(encoded, (short) 4));
        assertTrue(mapper.readTree(encoded.data()).at("/security/authenticationControls").isObject());
        assertTrue(mapper.readTree(encoded.data()).at("/security/dataResidencyDetails").isObject());
        for (short format : new short[] {1, 2, 3}) {
            assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(encoded, format));
        }
        for (String field : new String[] {"dataResidencyDetails", "authenticationControls", "complianceScopeStatus"}) {
            ObjectNode malformed = (ObjectNode) mapper.readTree(encoded.data());
            ((ObjectNode) malformed.get("security")).remove(field);
            assertThrows(AssessmentProfileSerializationException.class,
                    () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 4));
            ((ObjectNode) malformed.get("security")).putNull(field);
            assertThrows(AssessmentProfileSerializationException.class,
                    () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 4));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4})
    void legacyOperationsStayUnknownWithoutDefaultingAnyVolume(int format) {
        var base = ApplicationIdentityProfile.unknown();
        var s = base.security();
        var profile = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                new SecurityRequirements(s.multiFactorAuthentication(), s.browserTokenExposureMinimization(), s.auditability(),
                s.dataResidency(), s.assurance(), s.complianceTargets(),
                format == 2 ? new DataResidencyDetails(Set.of("DE"), Set.of()) : s.dataResidencyDetails(),
                format == 3 ? new AuthenticationControls(RequirementCriticality.REQUIRED, RequirementCriticality.UNKNOWN,
                        RequirementCriticality.UNKNOWN) : s.authenticationControls(),
                format == 4 ? ComplianceScopeStatus.NONE_IDENTIFIED : ComplianceScopeStatus.UNKNOWN), base.operations());
        var encoded = codec.encode(profile);
        assertEquals(format, codec.schemaVersion(profile));
        assertFalse(mapper.readTree(encoded.data()).at("/operations").has("usagePlanning"));
        var decoded = codec.decode(encoded, (short) format);
        assertEquals(profile, decoded);
        assertEquals(UsagePlanning.unknown(), decoded.operations().usagePlanning());
        assertEquals(encoded, codec.encode(decoded));
    }

    @Test
    void usageMetadataAndExplicitZeroRequireV5WithoutLosingOlderSecurityFields() {
        var base = ApplicationIdentityProfile.unknown();
        var o = base.operations();
        for (var usage : java.util.List.of(new UsagePlanning("Projected pilot", java.util.List.of(), java.util.Map.of()),
                new UsagePlanning("", java.util.List.of("No workload clients planned."), java.util.Map.of()),
                new UsagePlanning("", java.util.List.of(), java.util.Map.of(UsagePlanning.Metric.MONTHLY_M2M_TOKEN_ISSUANCES,
                        new UsagePlanning.Quantity(UsagePlanning.Basis.ASSUMED, 0L))))) {
            var profile = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                    base.security(), new OperationalConstraints(o.hosting(), o.deploymentTarget(), o.identityExpertise(), o.budgetSensitivity(), usage));
            var encoded = codec.encode(profile);
            assertEquals(5, codec.schemaVersion(profile));
            assertEquals(profile, codec.decode(encoded, (short) 5));
            for (short version : new short[] {1, 2, 3, 4}) {
                assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(encoded, version));
            }
            for (String path : new String[] {"operations/usagePlanning", "security/dataResidencyDetails",
                    "security/authenticationControls", "security/complianceScopeStatus"}) {
                var parts = path.split("/");
                ObjectNode malformed = (ObjectNode) mapper.readTree(encoded.data());
                ((ObjectNode) malformed.get(parts[0])).remove(parts[1]);
                assertThrows(AssessmentProfileSerializationException.class,
                        () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 5));
                ((ObjectNode) malformed.get(parts[0])).putNull(parts[1]);
                assertThrows(AssessmentProfileSerializationException.class,
                        () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 5));
            }
        }
    }

    @Test
    void historicalSnapshotsAreReadWithoutCurrentDomainValidationOrUpcasting() {
        var profile = mapper.valueToTree(ApplicationIdentityProfile.unknown());
        ((ObjectNode) profile.get("application")).put("type", "FUTURE_CLASSIFICATION");
        var json = JSONB.valueOf(mapper.writeValueAsString(profile));
        assertEquals(profile, codec.snapshot(json, (short) 1));
        assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(json, (short) 1));
    }

    @Test
    void controlsUseV3EvenWithoutResidencyAndCannotBeMisreadAsAnOlderFormat() {
        var base = ApplicationIdentityProfile.unknown();
        var s = base.security();
        var profile = new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(),
                base.provisioning(), new SecurityRequirements(s.multiFactorAuthentication(),
                s.browserTokenExposureMinimization(), s.auditability(), s.dataResidency(), s.assurance(),
                s.complianceTargets(), s.dataResidencyDetails(), new AuthenticationControls(
                        RequirementCriticality.REQUIRED, RequirementCriticality.UNKNOWN, RequirementCriticality.PREFERRED)),
                base.operations());
        var json = codec.encode(profile);
        assertEquals(3, codec.schemaVersion(profile));
        assertEquals(profile, codec.decode(json, (short) 3));
        assertTrue(mapper.readTree(json.data()).at("/security/dataResidencyDetails/allowedCountries").isArray());
        assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(json, (short) 1));
        assertThrows(AssessmentProfileSerializationException.class, () -> codec.decode(json, (short) 2));
        assertEquals(AuthenticationControls.unknown(), codec.decode(codec.encode(base), (short) 1).security().authenticationControls());
        for (String field : new String[] {"authenticationControls", "dataResidencyDetails"}) {
            ObjectNode malformed = (ObjectNode) mapper.readTree(json.data());
            ((ObjectNode) malformed.get("security")).remove(field);
            assertThrows(AssessmentProfileSerializationException.class,
                    () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 3));
            ((ObjectNode) malformed.get("security")).putNull(field);
            assertThrows(AssessmentProfileSerializationException.class,
                    () -> codec.decode(JSONB.valueOf(mapper.writeValueAsString(malformed)), (short) 3));
        }
    }
}
