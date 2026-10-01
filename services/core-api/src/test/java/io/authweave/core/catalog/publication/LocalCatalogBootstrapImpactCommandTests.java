package io.authweave.core.catalog.publication;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalCatalogBootstrapImpactCommandTests {
    private final LocalCatalogBootstrapImpactWriter writer = mock(LocalCatalogBootstrapImpactWriter.class);
    private final LocalCatalogBootstrapImpactCommand command = new LocalCatalogBootstrapImpactCommand(writer);
    private Map<String, String> valid() { return new HashMap<>(Map.of(
            "AUTHWEAVE_CATALOG_BOOTSTRAP_IMPACT_REPORT_ID", UUID.randomUUID().toString(),
            "AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_ID", UUID.randomUUID().toString(),
            "AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_SHA256", "a".repeat(64))); }
    @Test void exactInputOnlyPassesIdentityAndDigestToWriter() {
        var env = valid(); command.store(env);
        verify(writer).save(UUID.fromString(env.get("AUTHWEAVE_CATALOG_BOOTSTRAP_IMPACT_REPORT_ID")), UUID.fromString(env.get("AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_ID")), "a".repeat(64));
    }
    @ParameterizedTest @ValueSource(strings = {"", "1-1-1-1-1", "secret-value", " 00000000-0000-0000-0000-000000000000", "00000000-0000-0000-0000-000000000000\n"})
    void bothUuidInputsRejectNonCanonicalValuesWithoutEchoingThem(String value) {
        for (var key : java.util.List.of("AUTHWEAVE_CATALOG_BOOTSTRAP_IMPACT_REPORT_ID", "AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_ID")) {
            var env = valid(); env.put(key, value); var error = assertThrows(IllegalArgumentException.class, () -> command.store(env));
            assertTrue(error.getMessage().contains(key)); assertFalse(error.getMessage().contains("secret-value"));
        }
        verifyNoInteractions(writer);
    }
    @ParameterizedTest @ValueSource(strings = {"", "not-a-digest", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", " aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidHashesAndMissingInputsNeverReachWriter(String value) {
        var env = valid(); env.put("AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_SHA256", value);
        assertThrows(IllegalArgumentException.class, () -> command.store(env));
        for (var key : valid().keySet()) { var missing = valid(); missing.remove(key); assertThrows(IllegalArgumentException.class, () -> command.store(missing)); }
        verifyNoInteractions(writer);
    }
}
