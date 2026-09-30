package io.authweave.core.catalog.proposal;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.config.InternalServiceCredentialFilter;

/** BFF-only curator index and exact-revision candidate evidence review. */
@RestController
public final class CatalogProposalReviewController {
    private final InternalServiceCredentialFilter credentials;
    private final CatalogProposalRepository proposals;
    private final CatalogProposalEvidenceService evidence;

    public CatalogProposalReviewController(InternalServiceCredentialFilter credentials,
            CatalogProposalRepository proposals, CatalogProposalEvidenceService evidence) {
        this.credentials = credentials;
        this.proposals = proposals;
        this.evidence = evidence;
    }

    @GetMapping("/api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/evidence-review")
    public ResponseEntity<?> evidence(HttpServletRequest request, @PathVariable UUID proposalId,
            @PathVariable @Min(0) @Max(9007199254740991L) long version,
            @RequestParam(defaultValue = "0") @Min(0) @Max(6800) int offset) {
        int status = credentials.curatorStatus(request);
        if (status != 204) return ResponseEntity.status(status).build();
        return ResponseEntity.ok(evidence.review(proposalId, version, offset));
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
