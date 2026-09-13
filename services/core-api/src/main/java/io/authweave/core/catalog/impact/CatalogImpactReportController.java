package io.authweave.core.catalog.impact;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

/** Historical loopback reads only; no HTTP write or curator decision. */
@RestController
@RequestMapping("/api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/impact-reports")
public final class CatalogImpactReportController {
    private final CatalogImpactReportRepository reports;
    public CatalogImpactReportController(CatalogImpactReportRepository reports) { this.reports = reports; }
    @GetMapping
    public CatalogImpactReportRepository.Page list(@PathVariable UUID proposalId,
            @PathVariable @Min(0) @Max(9007199254740991L) long version,
            @RequestParam(required = false) @Min(0) @Max(9007199254740991L) Long afterReportNumber,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return reports.list(proposalId, version, afterReportNumber, limit);
    }
    @GetMapping("/{reportId}")
    public CatalogImpactReport get(@PathVariable UUID proposalId, @PathVariable @Min(0) @Max(9007199254740991L) long version, @PathVariable UUID reportId) {
        return reports.get(proposalId, version, reportId);
    }
    @GetMapping("/{reportId}/event")
    public CatalogImpactReportEvent event(@PathVariable UUID proposalId, @PathVariable @Min(0) @Max(9007199254740991L) long version, @PathVariable UUID reportId) {
        return reports.event(proposalId, version, reportId);
    }
}
