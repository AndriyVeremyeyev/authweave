package io.authweave.core.catalog.proposal;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.config.InternalServiceCredentialFilter;

/** BFF-only, scoped curator index of current proposal revisions. */
@RestController
public final class CatalogProposalReviewController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogProposalRepository proposals;

    public CatalogProposalReviewController(InternalServiceCredentialFilter credentials,
            CatalogProposalRepository proposals) {
        this.credentials = credentials;
        this.proposals = proposals;
    }

    @GetMapping("/api/v1/catalog-change-proposals")
    public ResponseEntity<?> list(HttpServletRequest request,
            @RequestParam(required = false) String beforeCreatedAt,
            @RequestParam(required = false) String beforeId) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            return invalidCursor(request);
        }
        CatalogProposalReviewPage.Cursor cursor = null;
        if (beforeCreatedAt != null) {
            try {
                cursor = new CatalogProposalReviewPage.Cursor(Instant.parse(beforeCreatedAt),
                        UUID.fromString(beforeId));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                return invalidCursor(request);
            }
        }
        return ResponseEntity.ok(proposals.reviewPage(cursor));
    }

    private static ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Both proposal review cursor fields must be valid and supplied together.");
        problem.setType(URI.create("urn:authweave:problem:invalid-request"));
        problem.setTitle("Invalid request");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "invalid-request");
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
