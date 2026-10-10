package io.authweave.core.catalog.publication;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import io.authweave.core.PostgresIntegrationTest;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-disabled-publication-token-000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123", "AUTHWEAVE_OIDC_ORG_ID=456"})
@AutoConfigureMockMvc
class CatalogBootstrapPublicationDisabledTests extends PostgresIntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Test void publisherAndHttpRouteAreAbsentByDefaultEvenForAFreshCurator() throws Exception {
        assertTrue(context.getBeansOfType(CatalogBootstrapPublisher.class).isEmpty());
        assertTrue(context.getBeansOfType(CatalogBootstrapPublicationController.class).isEmpty());
        mvc.perform(post("/internal/v1/catalog-curator/bootstrap-publications")
                .header("Authorization", "Bearer synthetic-disabled-publication-token-000000000000000")
                .header("X-AuthWeave-Oidc-Issuer", "https://identity.example.invalid")
                .header("X-AuthWeave-Oidc-Subject", "fictional-curator")
                .header("X-AuthWeave-Curator-Role", "catalog_curator")
                .header("X-AuthWeave-Curator-Project-Id", "123").header("X-AuthWeave-Curator-Org-Id", "456")
                .header("X-AuthWeave-Authenticated-At", java.time.Instant.now().toString())
                .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
}
