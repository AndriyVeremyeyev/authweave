package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.config.InternalServiceCredentialFilter;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;

/** Protected Core-only workflow; a BFF form is a separate slice. Never a publication action. */
@RestController
public class CatalogBootstrapReviewController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogBootstrapReviewService reviews;
    public CatalogBootstrapReviewController(InternalServiceCredentialFilter credentials, CatalogBootstrapReviewService reviews) {
        this.credentials = credentials; this.reviews = reviews;
    }
    @PostMapping("/api/v1/catalog-bootstrap-reviews")
    public ResponseEntity<CatalogBootstrapReview> record(@RequestBody CatalogBootstrapReviewRequest body, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        var actor = new CuratorActor(request.getHeader("X-AuthWeave-Oidc-Issuer"), request.getHeader("X-AuthWeave-Oidc-Subject"),
                request.getHeader("X-AuthWeave-Curator-Project-Id"), request.getHeader("X-AuthWeave-Curator-Org-Id"),
                Instant.parse(request.getHeader("X-AuthWeave-Authenticated-At")));
        var result = reviews.record(body, actor);
        return ResponseEntity.status(result.created() ? 201 : 200).header("Cache-Control", "no-store").body(result.review());
    }
    @GetMapping("/api/v1/catalog-bootstrap-reviews/{reviewId}")
    public ResponseEntity<CatalogBootstrapReview> get(@PathVariable UUID reviewId,
            @RequestParam @Pattern(regexp = "[a-f0-9]{64}") String expectedSha256, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reviews.get(reviewId, expectedSha256));
    }
}
