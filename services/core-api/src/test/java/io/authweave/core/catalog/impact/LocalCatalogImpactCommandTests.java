package io.authweave.core.catalog.impact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalCatalogImpactCommandTests {
    @Test void forwardsOnlyExplicitIdsAndRevisionWithoutReadingReportFiles() {
        var writer = mock(LocalCatalogImpactWriter.class); var command = new LocalCatalogImpactCommand(writer); var env = env();
        command.store(env);
        verify(writer).save(UUID.fromString(env.get("AUTHWEAVE_CATALOG_IMPACT_REPORT_ID")), UUID.fromString(env.get("AUTHWEAVE_CATALOG_PROPOSAL_ID")), 0L);
        for (String key : env.keySet()) { var missing = new HashMap<>(env); missing.remove(key); assertThrows(IllegalArgumentException.class, () -> command.store(missing)); }
        verifyNoMoreInteractions(writer);
    }
    @ParameterizedTest @ValueSource(strings = {"", "-1", "01", "1.5", " 1", "1e2", "9007199254740992", "99999999999999999999"})
    void rejectsInvalidRevisionWithoutEchoingInput(String value) {
        var env = env(); env.put("AUTHWEAVE_CATALOG_PROPOSAL_VERSION", value);
        assertThrows(IllegalArgumentException.class, () -> LocalCatalogImpactCommand.options(env));
    }
    @ParameterizedTest @ValueSource(strings = {"", "1-1-1-1-1", "sensitive-test-value", "33333333333343338333333333333333", " 33333333-3333-4333-8333-333333333333"})
    void requiresCanonicalUuidsAndDoesNotEchoBadValues(String value) {
        for (var key : new String[] {"AUTHWEAVE_CATALOG_IMPACT_REPORT_ID", "AUTHWEAVE_CATALOG_PROPOSAL_ID"}) {
            var env = env(); env.put(key, value);
            var error = assertThrows(IllegalArgumentException.class, () -> LocalCatalogImpactCommand.options(env));
            assertFalse(error.getMessage().contains("sensitive-test-value"));
        }
    }
    @Test void supportsTheSafeIntegerLimit() {
        var env = env(); env.put("AUTHWEAVE_CATALOG_PROPOSAL_VERSION", "9007199254740991");
        assertEquals(9007199254740991L, LocalCatalogImpactCommand.options(env).version());
    }
    private Map<String, String> env() {
        return new HashMap<>(Map.of("AUTHWEAVE_CATALOG_IMPACT_REPORT_ID", UUID.randomUUID().toString(),
                "AUTHWEAVE_CATALOG_PROPOSAL_ID", UUID.randomUUID().toString(), "AUTHWEAVE_CATALOG_PROPOSAL_VERSION", "0"));
    }
}
