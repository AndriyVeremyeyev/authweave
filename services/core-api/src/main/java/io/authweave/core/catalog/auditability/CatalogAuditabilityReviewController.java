package io.authweave.core.catalog.auditability;

import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.config.InternalServiceCredentialFilter;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;

/** Core-only scoped curator workflow. No BFF form, proposal mutation, evidence promotion or publisher. */
@RestController
public final class CatalogAuditabilityReviewController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogAuditabilityReviewService reviews;
    private final CatalogAuditabilityImpactService impacts;
    public CatalogAuditabilityReviewController(InternalServiceCredentialFilter credentials, CatalogAuditabilityReviewService reviews,
            CatalogAuditabilityImpactService impacts) {
        this.credentials = credentials; this.reviews = reviews; this.impacts = impacts;
    }
    @PostMapping("/internal/v1/catalog-curator/auditability-impact/preview")
    public ResponseEntity<CatalogAuditabilityImpactService.Impact> impact(@RequestBody CatalogAuditabilityImpactService.Request body,
            HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        if (request.getQueryString() != null) return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(impacts.preview(body));
    }
    @PostMapping("/internal/v1/catalog-curator/auditability-reviews")
    public ResponseEntity<CatalogAuditabilityReview> record(@RequestBody CatalogAuditabilityReviewRequest body, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        if (request.getQueryString() != null) return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        var actor = new CuratorActor(request.getHeader("X-AuthWeave-Oidc-Issuer"), request.getHeader("X-AuthWeave-Oidc-Subject"),
                request.getHeader("X-AuthWeave-Curator-Project-Id"), request.getHeader("X-AuthWeave-Curator-Org-Id"),
                Instant.parse(request.getHeader("X-AuthWeave-Authenticated-At")));
        var result = reviews.record(body, actor);
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore()).body(result.review());
    }
    @GetMapping("/internal/v1/catalog-curator/auditability-reviews/{reviewId}")
    public ResponseEntity<CatalogAuditabilityReview> get(@PathVariable UUID reviewId,
            @RequestParam(required = false) String expectedSha256, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        if (request.getParameterMap().size() != 1 || request.getParameterValues("expectedSha256") == null
                || request.getParameterValues("expectedSha256").length != 1 || expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")
                || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.get(reviewId, expectedSha256));
    }
}
