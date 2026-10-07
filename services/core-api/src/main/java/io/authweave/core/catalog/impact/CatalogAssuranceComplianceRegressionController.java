package io.authweave.core.catalog.impact;

import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.config.InternalServiceCredentialFilter;

@RestController
public final class CatalogAssuranceComplianceRegressionController {
    private final CatalogAssuranceComplianceRegressionService service;
    private final InternalServiceCredentialFilter credentials;
    private final Clock clock;
    public CatalogAssuranceComplianceRegressionController(CatalogAssuranceComplianceRegressionService service, InternalServiceCredentialFilter credentials, Clock clock) {
        this.service = service; this.credentials = credentials; this.clock = clock;
    }
    @GetMapping("/internal/v1/catalog-assurance-compliance/regression-preflight")
    public ResponseEntity<CatalogAssuranceComplianceRegressionService.Check> inspect(HttpServletRequest request) {
        int status = credentials.serviceCredentialStatus(request);
        if (status == 200 && (request.getQueryString() != null || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) status = 400;
        if (status != 200) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.inspectAt(clock.instant()));
    }
}
