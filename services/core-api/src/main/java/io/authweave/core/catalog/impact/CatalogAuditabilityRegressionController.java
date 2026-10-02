package io.authweave.core.catalog.impact;

import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.config.InternalServiceCredentialFilter;

@RestController
public final class CatalogAuditabilityRegressionController {
    private final CatalogAuditabilityRegressionService service;
    private final InternalServiceCredentialFilter credentials;
    private final Clock clock;
    private final CatalogProfileImpactCoverageV6Service coverage;
    public CatalogAuditabilityRegressionController(CatalogAuditabilityRegressionService service, InternalServiceCredentialFilter credentials,
            Clock clock, CatalogProfileImpactCoverageV6Service coverage) {
        this.service = service; this.credentials = credentials; this.clock = clock; this.coverage = coverage;
    }
    @GetMapping("/internal/v1/catalog-auditability/regression-preflight")
    public ResponseEntity<CatalogAuditabilityRegressionService.Check> inspect(HttpServletRequest request) {
        // Recheck at the controller boundary, including when routing decodes an encoded path.
        int status = requestStatus(request);
        if (status != 200) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.inspectAt(clock.instant()));
    }
    @GetMapping("/internal/v1/catalog-profile-impact/coverage-preflight")
    public ResponseEntity<CatalogProfileImpactCoverageV6Service.Check> coverage(HttpServletRequest request) {
        int status = requestStatus(request);
        if (status != 200) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(coverage.inspectAt(clock.instant()));
    }
    private int requestStatus(HttpServletRequest request) {
        int credential = credentials.serviceCredentialStatus(request);
        if (credential != 200) return credential;
        return request.getQueryString() != null || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null ? 400 : 200;
    }
}
