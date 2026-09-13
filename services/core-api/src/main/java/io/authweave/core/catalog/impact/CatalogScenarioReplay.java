package io.authweave.core.catalog.impact;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.proposal.CatalogProposalRepository;
import io.authweave.core.catalog.proposal.CatalogProposalException;

/** Shared exact-revision replay for read-only preview and the explicit local report writer. */
@Service
public final class CatalogScenarioReplay {
    private final CatalogScenarioImpactService service;
    private final CatalogProposalRepository repository;
    private final ObjectMapper mapper;
    public CatalogScenarioReplay(CatalogScenarioImpactService service, CatalogProposalRepository repository, ObjectMapper mapper) {
        this.service = service; this.repository = repository; this.mapper = mapper;
    }
    public CatalogScenarioImpact analyze(UUID proposalId, long version) {
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
