package io.authweave.core.config;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import io.authweave.core.PostgresIntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-internal-token-000000000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123456789012345678",
        "AUTHWEAVE_OIDC_ORG_ID=987654321098765432"
})
@AutoConfigureMockMvc
class CatalogCuratorAuthorizationIntegrationTests extends PostgresIntegrationTest {
    private static final String PATH = "/internal/v1/catalog-curator/authorization";
    private static final String TOKEN = "Bearer synthetic-internal-token-000000000000000000000";

    @Autowired private MockMvc mvc;

    @Test
    void probeRequiresCredentialScopedRoleAndFreshAuthentication() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081")
                        .header("X-AuthWeave-Oidc-Subject", "synthetic-curator"))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).header("Authorization", TOKEN)
                        .header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081")
                        .header("X-AuthWeave-Oidc-Subject", "synthetic-curator")
                        .header("X-AuthWeave-Curator-Role", "catalog_curator")
                        .header("X-AuthWeave-Curator-Project-Id", "123456789012345678")
                        .header("X-AuthWeave-Curator-Org-Id", "987654321098765432")
                        .header("X-AuthWeave-Authenticated-At", Instant.now().toString()))
                .andExpect(status().isNoContent());
    }
}
