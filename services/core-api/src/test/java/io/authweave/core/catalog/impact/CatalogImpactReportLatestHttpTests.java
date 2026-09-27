package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.proposal.LocalCatalogProposalWriter;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The special latest route is scoped to one revision and never recalculates a report. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local-catalog-write", "local-catalog-impact-write"})
class CatalogImpactReportLatestHttpTests extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired LocalCatalogProposalWriter proposals;
    @Autowired LocalCatalogImpactWriter impacts;

    @Test void latestIsAbsentThenReturnsTheHighestNumberedStoredRunForOnlyThatRevision() throws Exception {
        var input = (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json").toFile());
        var id = UUID.randomUUID(); input.put("proposalId", id.toString());
        var request = mapper.treeToValue(input, CatalogChangePreviewRequest.class);
        proposals.save(request, null);
        var path = "/api/v1/catalog-change-proposals/" + id + "/revisions/0/impact-reports/latest";
        mvc.perform(get(path)).andExpect(status().isNoContent());
        var first = impacts.save(UUID.randomUUID(), id, 0).report();
        var second = impacts.save(UUID.randomUUID(), id, 0).report();
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(second.reportId().toString()))
                .andExpect(jsonPath("$.reportNumber").value(second.reportNumber()))
                .andExpect(jsonPath("$.proposalSha256").value(second.proposalSha256()))
                .andExpect(jsonPath("$.report.approvalGranted").value(false));
        mvc.perform(get("/api/v1/catalog-change-proposals/" + id + "/revisions/1/impact-reports/latest"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/catalog-change-proposals/" + UUID.randomUUID() +
                "/revisions/0/impact-reports/latest")).andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.reportId(), second.reportId());
    }
}
