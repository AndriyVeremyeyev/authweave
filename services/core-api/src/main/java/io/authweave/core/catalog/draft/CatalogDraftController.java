package io.authweave.core.catalog.draft;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CatalogDraftController {
    private final CatalogDraftValidator validator;
    public CatalogDraftController(CatalogDraftValidator validator) { this.validator = validator; }

    @PostMapping("/api/v1/catalog-drafts/validate")
    public CatalogDraftValidation validate(@RequestBody ProviderCatalogDraft draft) { return validator.validate(draft); }
}
