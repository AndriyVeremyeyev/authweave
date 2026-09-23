package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.authweave.core.config.InternalServiceCredentialFilter;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;

/** The filter and this handler both verify the curator assertion before any DB write. */
@RestController
public class CatalogProposalRejectionController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogProposalRejectionWriter writer;

    public CatalogProposalRejectionController(InternalServiceCredentialFilter credentials,
            CatalogProposalRejectionWriter writer) {
        this.credentials = credentials;
        this.writer = writer;
    }

    @PostMapping("/api/v1/catalog-change-proposals/{proposalId}/decisions/rejection")
    public ResponseEntity<CatalogProposalRejection> reject(@PathVariable UUID proposalId,
            @Valid @RequestBody CatalogProposalRejectionRequest body, HttpServletRequest request) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        var actor = new CuratorActor(request.getHeader("X-AuthWeave-Oidc-Issuer"),
                request.getHeader("X-AuthWeave-Oidc-Subject"),
                request.getHeader("X-AuthWeave-Curator-Project-Id"),
                request.getHeader("X-AuthWeave-Curator-Org-Id"),
                Instant.parse(request.getHeader("X-AuthWeave-Authenticated-At")));
        return ResponseEntity.status(201).body(writer.reject(proposalId, body, actor));
    }
}
