package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.config.InternalServiceCredentialFilter;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;

@RestController
public class CatalogFactReviewController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogFactReviewWriter writer;
    private final CatalogFactReviewRepository history;
    public CatalogFactReviewController(InternalServiceCredentialFilter credentials, CatalogFactReviewWriter writer,
            CatalogFactReviewRepository history) {
        this.credentials = credentials; this.writer = writer; this.history = history;
    }

    @GetMapping("/api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/fact-reviews")
    public ResponseEntity<CatalogFactReviewPage> history(@PathVariable UUID proposalId,
            @PathVariable @Min(0) @Max(9007199254740991L) long version,
            @RequestParam(defaultValue = "0") @Min(0) @Max(9007199254740991L) long afterReviewNumber,
            HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(history.page(proposalId, version, afterReviewNumber));
    }

    @PostMapping("/api/v1/catalog-change-proposals/{proposalId}/fact-reviews")
    public ResponseEntity<CatalogFactReview> record(@PathVariable UUID proposalId,
            @Valid @RequestBody CatalogFactReviewRequest body, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        var actor = new CuratorActor(request.getHeader("X-AuthWeave-Oidc-Issuer"),
                request.getHeader("X-AuthWeave-Oidc-Subject"), request.getHeader("X-AuthWeave-Curator-Project-Id"),
                request.getHeader("X-AuthWeave-Curator-Org-Id"),
                Instant.parse(request.getHeader("X-AuthWeave-Authenticated-At")));
        var result = writer.record(proposalId, body, actor);
        return ResponseEntity.status(result.created() ? 201 : 200).header("Cache-Control", "no-store").body(result.review());
    }
}
