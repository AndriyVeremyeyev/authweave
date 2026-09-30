package io.authweave.core.catalog.proposal;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.catalog.ProviderCatalog.Availability;
import io.authweave.core.catalog.ProviderCatalog.Support;
import io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogFactClaimTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Evidence evidence = new Evidence(URI.create("https://example.invalid/source"),
            Instant.parse("2026-09-30T12:00:00Z"), "Fictional source only.");

    @Test
    void capabilityValuesAreNotPromotedToSupportOrMandatoryClaims() {
        for (var availability : Availability.values()) {
            var claim = CatalogFactClaim.from(new CapabilityFact(availability, List.of("Pilot"), evidence));
            var json = mapper.readTree(mapper.writeValueAsString(claim));
            assertEquals("CAPABILITY", json.get("kind").asText());
            assertEquals(availability.name(), json.get("availability").asText());
            assertEquals(2, json.size());
        }
    }

    @Test
    void compatibilityPreservesUnknownAndUnsupportedWithoutProvenanceDuplication() {
        for (var support : Support.values()) {
            var claim = CatalogFactClaim.from(new CompatibilityFact(support, List.of(), evidence));
            var json = mapper.readTree(mapper.writeValueAsString(claim));
            assertEquals("COMPATIBILITY", json.get("kind").asText());
            assertEquals(support.name(), json.get("support").asText());
            assertEquals(2, json.size());
        }
    }

    @Test
    void residencyRetainsCoverageAndNormalizesOnlyCountryOrder() {
        var countries = new java.util.ArrayList<>(List.of("FR", "DE"));
        var claim = (CatalogFactClaim.Residency) CatalogFactClaim.from(
                new ResidencyFact(ResidencyCoverage.PARTIAL, countries, List.of(), evidence));
        assertEquals(ResidencyCoverage.PARTIAL, claim.coverage());
        assertEquals(List.of("DE", "FR"), claim.storageCountries());
        countries.clear();
        assertEquals(List.of("DE", "FR"), claim.storageCountries());
        assertThrows(UnsupportedOperationException.class, () -> claim.storageCountries().clear());
        var json = mapper.readTree(mapper.writeValueAsString(claim));
        assertEquals("RESIDENCY", json.get("kind").asText());
        assertEquals(3, json.size());
    }

    @Test
    void authenticationAvailabilityNeverSubstitutesForEnforcement() {
        for (var enforcement : Support.values()) {
            var claim = CatalogFactClaim.from(new AuthenticationFact(Support.SUPPORTED, enforcement, List.of(), evidence));
            var json = mapper.readTree(mapper.writeValueAsString(claim));
            assertEquals("AUTHENTICATION_CONTROL", json.get("kind").asText());
            assertEquals("SUPPORTED", json.get("availability").asText());
            assertEquals(enforcement.name(), json.get("enforcement").asText());
            assertEquals(3, json.size());
        }
    }
}
