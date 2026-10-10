package io.authweave.core.catalog.publication;

import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.config.InternalServiceCredentialFilter;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;

/** Core-only protected route; no browser, AI or identity role changes. */
@RestController
@Profile("catalog-proposal-publication")
public class CatalogProposalPublicationController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogProposalPublisher publisher;
    public CatalogProposalPublicationController(InternalServiceCredentialFilter credentials, CatalogProposalPublisher publisher) {
        this.credentials = credentials; this.publisher = publisher;
    }
    @PostMapping("/internal/v1/catalog-curator/proposal-publications")
    public ResponseEntity<CatalogProposalPublisher.Receipt> publish(@RequestBody CatalogProposalPublicationRequest body, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        var actor = new CuratorActor(request.getHeader("X-AuthWeave-Oidc-Issuer"), request.getHeader("X-AuthWeave-Oidc-Subject"),
                request.getHeader("X-AuthWeave-Curator-Project-Id"), request.getHeader("X-AuthWeave-Curator-Org-Id"),
                Instant.parse(request.getHeader("X-AuthWeave-Authenticated-At")));
        var result = publisher.publish(body, actor);
        return ResponseEntity.status(result.created() ? 201 : 200).header("Cache-Control", "no-store").body(result.receipt());
    }
}
