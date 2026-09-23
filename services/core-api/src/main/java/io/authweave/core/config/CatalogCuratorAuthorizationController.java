package io.authweave.core.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only proof of the BFF-to-Core curator boundary; it grants no catalog mutation. */
@RestController
final class CatalogCuratorAuthorizationController {
    private final InternalServiceCredentialFilter credentials;

    CatalogCuratorAuthorizationController(InternalServiceCredentialFilter credentials) {
        this.credentials = credentials;
    }

    @GetMapping("/internal/v1/catalog-curator/authorization")
    ResponseEntity<Void> verify(HttpServletRequest request) {
        return ResponseEntity.status(credentials.curatorStatus(request)).build();
    }
}
