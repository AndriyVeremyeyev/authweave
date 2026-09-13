package io.authweave.core.catalog.impact;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.proposal.CatalogProposalRepository;
import io.authweave.core.catalog.proposal.CatalogProposalException;

@RestController
public final class CatalogScenarioImpactController {
    private final CatalogScenarioImpactService service;
    private final CatalogProposalRepository repository;
    private final ObjectMapper mapper;
    public CatalogScenarioImpactController(CatalogScenarioImpactService service, CatalogProposalRepository repository, ObjectMapper mapper) {
        this.service = service; this.repository = repository; this.mapper = mapper;
    }
    @PostMapping("/api/v1/catalog-change-proposals/scenario-impact-preview")
    public CatalogScenarioImpact preview(@RequestBody CatalogChangePreviewRequest request) { return service.analyze(request); }

    @GetMapping("/api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/scenario-impact-preview")
    public CatalogScenarioImpact stored(@PathVariable UUID proposalId, @PathVariable @Min(0) @Max(9007199254740991L) long version) {
        var snapshot = repository.revision(proposalId, version);
        try {
            if (snapshot.requestSchemaVersion() != 1) throw new IllegalArgumentException("Unsupported stored format");
            var request = mapper.treeToValue(snapshot.request(), CatalogChangePreviewRequest.class);
            if (request == null || !request.proposalId().equals(proposalId)) throw new IllegalArgumentException("Stored ID mismatch");
            return service.analyze(request, version, snapshot.proposalSha256());
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            throw new CatalogProposalException(CatalogProposalException.Reason.REPLAY_UNAVAILABLE);
        }
    }
}
