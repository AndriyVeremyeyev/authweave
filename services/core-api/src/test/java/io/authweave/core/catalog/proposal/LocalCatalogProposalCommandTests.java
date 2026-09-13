package io.authweave.core.catalog.proposal;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LocalCatalogProposalCommandTests {
    @Test void acceptsExplicitAbsolutePathsAndSafeVersions() {
        var path = "/tmp/catalog proposal.json";
        assertNull(LocalCatalogProposalCommand.options(Map.of("AUTHWEAVE_CATALOG_PROPOSAL_FILE", path)).expectedVersion());
        for (String version : new String[] {"0", "1", "9007199254740991"}) {
            var options = LocalCatalogProposalCommand.options(Map.of("AUTHWEAVE_CATALOG_PROPOSAL_FILE", path, "AUTHWEAVE_CATALOG_EXPECTED_VERSION", version));
            assertEquals(path, options.path().toString()); assertEquals(Long.valueOf(version), options.expectedVersion());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"", "-1", "01", "1.0", "1e2", " 1", "9007199254740992", "99999999999999999999999"})
    void rejectsInvalidVersions(String version) {
        assertThrows(IllegalArgumentException.class, () -> LocalCatalogProposalCommand.options(Map.of(
                "AUTHWEAVE_CATALOG_PROPOSAL_FILE", "/tmp/proposal.json", "AUTHWEAVE_CATALOG_EXPECTED_VERSION", version)));
    }
    @Test void requiresAnExplicitLocalAbsolutePath() {
        assertThrows(IllegalArgumentException.class, () -> LocalCatalogProposalCommand.options(Map.of()));
        for (String path : new String[] {"", " ", "relative.json", "https://example.invalid/proposal.json", "bad\u0000path"}) {
            assertThrows(IllegalArgumentException.class, () -> LocalCatalogProposalCommand.options(Map.of("AUTHWEAVE_CATALOG_PROPOSAL_FILE", path)));
        }
    }
}
