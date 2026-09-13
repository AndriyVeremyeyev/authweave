package io.authweave.core.catalog.proposal;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Loopback-only reads. Proposal writes are deliberately not exposed before curator authorization. */
@RestController
public class CatalogProposalController {
    private final CatalogProposalRepository repository;
    public CatalogProposalController(CatalogProposalRepository repository) { this.repository = repository; }
    @GetMapping("/api/v1/catalog-change-proposals/{proposalId}")
    public CatalogProposalSnapshot current(@PathVariable UUID proposalId) { return repository.current(proposalId); }
    @GetMapping("/api/v1/catalog-change-proposals/{proposalId}/revisions")
    public CatalogProposalPage<CatalogProposalSnapshot> revisions(@PathVariable UUID proposalId,
            @RequestParam(required = false) @Min(0) @Max(9007199254740991L) Long afterVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return repository.revisions(proposalId, afterVersion, limit);
    }
    @GetMapping("/api/v1/catalog-change-proposals/{proposalId}/events")
    public CatalogProposalPage<CatalogProposalEvent> events(@PathVariable UUID proposalId,
            @RequestParam(required = false) @Min(0) @Max(9007199254740991L) Long afterVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return repository.events(proposalId, afterVersion, limit);
    }
}
