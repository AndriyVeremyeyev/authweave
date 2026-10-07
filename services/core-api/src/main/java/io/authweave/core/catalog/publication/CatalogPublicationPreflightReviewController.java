package io.authweave.core.catalog.publication;

import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.config.InternalServiceCredentialFilter;

/** Fresh curator-authorized reads only. No caller-supplied baseline, evidence or readiness assertions. */
@RestController
public final class CatalogPublicationPreflightReviewController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogPublicationPreflight preflight;
    public CatalogPublicationPreflightReviewController(InternalServiceCredentialFilter credentials, CatalogPublicationPreflight preflight) {
        this.credentials = credentials; this.preflight = preflight;
    }
    @GetMapping("/internal/v1/catalog-curator/proposals/{id}/revisions/{version}/publication-preflight")
    public ResponseEntity<?> proposal(HttpServletRequest request, @PathVariable UUID id,
            @PathVariable @Min(0) @Max(9007199254740991L) long version,
            @RequestParam @Pattern(regexp = "[a-f0-9]{64}") String expectedSha256) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return response(status, null);
        if (!exactQuery(request)) return response(400, null);
        return response(200, CatalogPublicationPreflightReview.from(preflight.proposal(id, version, expectedSha256, null), id, version, expectedSha256));
    }
    @GetMapping("/internal/v1/catalog-curator/bootstrap-reviews/{id}/publication-preflight")
    public ResponseEntity<?> bootstrap(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam @Pattern(regexp = "[a-f0-9]{64}") String expectedSha256) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return response(status, null);
        if (!exactQuery(request)) return response(400, null);
        return response(200, CatalogPublicationPreflightReview.from(preflight.bootstrap(id, expectedSha256), id, null, expectedSha256));
    }
    private static boolean exactQuery(HttpServletRequest request) {
        return request.getParameterMap().keySet().equals(Set.of("expectedSha256"))
                && request.getParameterValues("expectedSha256").length == 1 && request.getContentLengthLong() <= 0
                && request.getHeader("Transfer-Encoding") == null;
    }
    private static ResponseEntity<?> response(int status, CatalogPublicationPreflightReview body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
