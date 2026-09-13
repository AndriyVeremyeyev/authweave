package io.authweave.core.catalog.draft;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CatalogChangePreviewController {
    private final CatalogChangePreviewService service;
    public CatalogChangePreviewController(CatalogChangePreviewService service) { this.service = service; }

    @PostMapping("/api/v1/catalog-change-proposals/preview")
    public CatalogChangePreview preview(@RequestBody CatalogChangePreviewRequest request) { return service.preview(request); }
}
