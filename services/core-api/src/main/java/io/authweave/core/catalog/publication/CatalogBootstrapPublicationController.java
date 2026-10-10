package io.authweave.core.catalog.publication;

import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.config.InternalServiceCredentialFilter;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;

/** No browser/AI route. The existing service credential, scoped curator assertion and fresh reauthentication guard apply. */
@RestController
@Profile("catalog-bootstrap-publication")
public class CatalogBootstrapPublicationController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogBootstrapPublisher publisher;
    public CatalogBootstrapPublicationController(InternalServiceCredentialFilter credentials, CatalogBootstrapPublisher publisher) {
        this.credentials = credentials; this.publisher = publisher;
    }
    @PostMapping("/internal/v1/catalog-curator/bootstrap-publications")
    public ResponseEntity<CatalogBootstrapPublisher.Receipt> publish(@RequestBody CatalogBootstrapPublicationRequest body, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        var actor = new CuratorActor(request.getHeader("X-AuthWeave-Oidc-Issuer"), request.getHeader("X-AuthWeave-Oidc-Subject"),
                request.getHeader("X-AuthWeave-Curator-Project-Id"), request.getHeader("X-AuthWeave-Curator-Org-Id"),
                Instant.parse(request.getHeader("X-AuthWeave-Authenticated-At")));
        var result = publisher.publish(body, actor);
        return ResponseEntity.status(result.created() ? 201 : 200).header("Cache-Control", "no-store").body(result.receipt());
    }
}
