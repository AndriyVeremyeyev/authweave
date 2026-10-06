package io.authweave.core.catalog.impact;

import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.config.InternalServiceCredentialFilter;

@RestController
public final class CatalogArchitectureConfigurationRegressionController {
    private final CatalogArchitectureConfigurationRegressionService service;
    private final InternalServiceCredentialFilter credentials;
    private final Clock clock;
    public CatalogArchitectureConfigurationRegressionController(CatalogArchitectureConfigurationRegressionService service,
            InternalServiceCredentialFilter credentials, Clock clock) { this.service = service; this.credentials = credentials; this.clock = clock; }
    @GetMapping("/internal/v1/catalog-architecture-configuration/regression-preflight")
    public ResponseEntity<CatalogArchitectureConfigurationRegressionService.Check> inspect(HttpServletRequest request) {
        int status = credentials.serviceCredentialStatus(request);
        if (status == 200 && (request.getQueryString() != null || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) status = 400;
        // Recheck after routing too, so an encoded path cannot bypass the internal credential guard.
        if (status != 200) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.inspectAt(clock.instant()));
    }
}
