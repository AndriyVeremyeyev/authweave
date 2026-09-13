package io.authweave.core.catalog.impact;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;

@RestController
public final class CatalogScenarioImpactController {
    private final CatalogScenarioImpactService service;
    private final CatalogScenarioReplay replay;
    public CatalogScenarioImpactController(CatalogScenarioImpactService service, CatalogScenarioReplay replay) {
        this.service = service; this.replay = replay;
    }
    @PostMapping("/api/v1/catalog-change-proposals/scenario-impact-preview")
    public CatalogScenarioImpact preview(@RequestBody CatalogChangePreviewRequest request) { return service.analyze(request); }

    @GetMapping("/api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/scenario-impact-preview")
    public CatalogScenarioImpact stored(@PathVariable UUID proposalId, @PathVariable @Min(0) @Max(9007199254740991L) long version) {
        return replay.analyze(proposalId, version);
    }
}
