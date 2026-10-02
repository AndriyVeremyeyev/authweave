package io.authweave.core.catalog.draft;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.config.InternalServiceCredentialFilter;

@RestController
public final class CatalogAuditabilityDraftController {
    private final CatalogAuditabilityDraftValidator validator;
    private final InternalServiceCredentialFilter credentials;
    public CatalogAuditabilityDraftController(CatalogAuditabilityDraftValidator validator, InternalServiceCredentialFilter credentials) {
        this.validator = validator; this.credentials = credentials;
    }
    @PostMapping("/internal/v1/catalog-auditability/drafts/validate")
    public ResponseEntity<CatalogAuditabilityDraftValidator.Validation> validate(
            HttpServletRequest request, @RequestBody CatalogAuditabilityDraftValidator.Request input) {
        int status = credentials.serviceCredentialStatus(request);
        if (status != 200) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        if (request.getQueryString() != null) return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(validator.validate(input));
    }
}
