package io.authweave.core.catalog.publication;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;

/** Validates recorded integrity, not curator authentication, source truth or authority to use an active catalog. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogPublicationLookup {
    public static final int MAX_LINEAGE_DEPTH = 64;
    public static final long MAX_READ_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SAFE_VERSION = 9007199254740991L;
    private final CatalogPublicationRepository repository;
    private final ObjectMapper mapper;
    private final CatalogSnapshotInspector inspector;

    CatalogPublicationLookup(CatalogPublicationRepository repository, ObjectMapper mapper, CatalogSnapshotInspector inspector) {
        this.repository = repository; this.mapper = mapper; this.inspector = inspector;
    }
    public enum Status { VALIDATED_STORED_LINEAGE, UNAVAILABLE }
    public enum Reason {
        NONE, NOT_FOUND, REFERENCE_MISMATCH, STORED_FORMAT_INVALID, STORED_BINDING_INVALID,
        PROPOSAL_BINDING_INVALID, LINEAGE_INVALID, LINEAGE_LIMIT_EXCEEDED, READ_BUDGET_EXCEEDED
    }
    public enum Authority { VERIFIED_PUBLICATION_WORKFLOW_UNAVAILABLE }
    /** Successful lineage is requested-to-root. Failed lookups release no manifest or partial lineage. */
    public record Result(Status status, Reason reason, PublishedCatalogSnapshot snapshot,
            List<PublishedCatalogSnapshot.Reference> lineage) {
        public Result {
            Objects.requireNonNull(status); Objects.requireNonNull(reason); lineage = List.copyOf(lineage);
            if (status == Status.VALIDATED_STORED_LINEAGE
                    ? reason != Reason.NONE || snapshot == null || lineage.isEmpty() || lineage.size() > MAX_LINEAGE_DEPTH
                        || !lineage.getFirst().equals(new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), snapshot.catalog().catalogVersion(), snapshot.snapshotSha256()))
                    : reason == Reason.NONE || snapshot != null || !lineage.isEmpty()) {
                throw new IllegalArgumentException("Inconsistent publication lookup result");
            }
        }
        @JsonProperty
        public boolean storedIntegrityValidated() { return status == Status.VALIDATED_STORED_LINEAGE; }
        @JsonProperty
        public Authority authority() { return Authority.VERIFIED_PUBLICATION_WORKFLOW_UNAVAILABLE; }
        @JsonProperty
        public boolean baselineVerified() { return false; }
        @JsonProperty
        public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty
        public boolean approvalGranted() { return false; }
        @JsonProperty
        public boolean writesPerformed() { return false; }
        @JsonProperty
        public boolean evaluationReady() { return false; }
    }
    public record BaselineComparison(Result lookup, boolean suppliedBaseDigestMatches, boolean suppliedBaseContentMatches) {
        public BaselineComparison {
            Objects.requireNonNull(lookup);
            if (!lookup.storedIntegrityValidated() && (suppliedBaseDigestMatches || suppliedBaseContentMatches)) {
                throw new IllegalArgumentException("Unavailable registry content cannot match a supplied baseline");
            }
        }
        @JsonProperty
        public boolean baselineVerified() { return false; }
    }

    public Result lookup(PublishedCatalogSnapshot.Reference reference) {
        Objects.requireNonNull(reference);
        try { return validated(reference); }
        catch (Invalid failure) { return new Result(Status.UNAVAILABLE, failure.reason, null, List.of()); }
        // Database errors propagate; never pretend an outage is a missing or trusted baseline.
    }

    /** The snapshot is loaded by its exact registry tuple, never accepted from the caller. */
    public BaselineComparison compareBaseline(PublishedCatalogSnapshot.Reference reference, CatalogChangePreviewRequest supplied) {
        Objects.requireNonNull(supplied);
        var result = lookup(reference);
        if (!result.storedIntegrityValidated()) return new BaselineComparison(result, false, false);
        var baseDigest = CatalogDraftCanonicalizer.sha256(supplied.base());
        return new BaselineComparison(result, baseDigest.equals(supplied.expectedBaseSha256()),
                baseDigest.equals(CatalogDraftCanonicalizer.sha256(result.snapshot().catalog().asDraft())));
    }

    private Result validated(PublishedCatalogSnapshot.Reference requested) {
        var lineage = new ArrayList<PublishedCatalogSnapshot.Reference>();
        var ids = new HashSet<UUID>(); var labels = new HashSet<String>();
        var next = requested; Node child = null; PublishedCatalogSnapshot selected = null;
        long remaining = MAX_READ_BYTES;
        while (next != null) {
            require(lineage.size() < MAX_LINEAGE_DEPTH, Reason.LINEAGE_LIMIT_EXCEEDED);
            require(ids.add(next.snapshotId()) && labels.add(next.catalogVersion()), Reason.LINEAGE_INVALID);
            var row = repository.find(next.snapshotId(), remaining);
            require(row != null, child == null ? Reason.NOT_FOUND : Reason.LINEAGE_INVALID);
            var stored = row.snapshot();
            require(stored != null && next.snapshotId().equals(stored.id()) && next.catalogVersion().equals(stored.catalogVersion())
                    && next.snapshotSha256().equals(stored.snapshotSha256()), child == null ? Reason.REFERENCE_MISMATCH : Reason.LINEAGE_INVALID);
            long requestBytes = row.proposal() == null ? 0 : row.proposal().requestBytes();
            require(stored.manifestBytes() > 0 && stored.manifestBytes() <= CatalogPublicationRepository.MAX_JSON_BYTES
                    && requestBytes >= 0 && requestBytes <= CatalogPublicationRepository.MAX_JSON_BYTES
                    && stored.manifestBytes() <= remaining - requestBytes, Reason.READ_BUDGET_EXCEEDED);
            remaining -= stored.manifestBytes() + requestBytes;
            var node = validate(row);
            require(repository.successors(stored.id()) <= 1, Reason.LINEAGE_INVALID);
            if (child != null) {
                require(!node.snapshot.publication().publishedAt().isAfter(child.snapshot.publication().publishedAt()), Reason.LINEAGE_INVALID);
                require(child.request != null && CatalogDraftCanonicalizer.sha256(child.request.base())
                        .equals(CatalogDraftCanonicalizer.sha256(node.snapshot.catalog().asDraft())), Reason.PROPOSAL_BINDING_INVALID);
            }
            if (selected == null) selected = node.snapshot;
            lineage.add(next); next = node.snapshot.previousSnapshot(); child = node;
            if (next == null) require(repository.roots().equals(List.of(node.snapshot.snapshotId())), Reason.LINEAGE_INVALID);
        }
        return new Result(Status.VALIDATED_STORED_LINEAGE, Reason.NONE, selected, lineage);
    }

    private Node validate(CatalogPublicationRecord row) {
        var stored = row.snapshot(); var snapshot = decode(stored.manifest(), PublishedCatalogSnapshot.class);
        require(inspector.inspect(snapshot).status() == CatalogSnapshotInspector.Status.VALID_SNAPSHOT_FORMAT, Reason.STORED_FORMAT_INVALID);
        require(snapshot.snapshotId().equals(stored.id()) && snapshot.catalog().catalogVersion().equals(stored.catalogVersion())
                && snapshot.contentSha256().equals(stored.contentSha256()) && snapshot.snapshotSha256().equals(stored.snapshotSha256())
                && snapshot.publication().decisionId().equals(stored.decisionId())
                && snapshot.publication().publishedAt().equals(stored.publishedAt()), Reason.STORED_BINDING_INVALID);
        var parent = snapshot.previousSnapshot();
        require(parent == null ? stored.previousId() == null && stored.previousVersion() == null && stored.previousSha256() == null
                : parent.snapshotId().equals(stored.previousId()) && parent.catalogVersion().equals(stored.previousVersion())
                    && parent.snapshotSha256().equals(stored.previousSha256()), Reason.STORED_BINDING_INVALID);
        var decision = row.decision();
        require(decision != null && stored.decisionId().equals(decision.id()) && stored.id().equals(decision.snapshotId())
                && ("CURATED_BOOTSTRAP".equals(decision.kind()) || "PROPOSAL_APPROVAL".equals(decision.kind()))
                && Objects.equals(stored.decisionKind(), decision.kind()) && stored.catalogVersion().equals(decision.catalogVersion())
                && stored.contentSha256().equals(decision.contentSha256()) && stored.snapshotSha256().equals(decision.snapshotSha256())
                && stored.publishedAt().equals(decision.publishedAt()) && decision.recordedAt() != null
                && !decision.publishedAt().isBefore(decision.recordedAt().minusSeconds(30))
                && !decision.publishedAt().isAfter(decision.recordedAt().plusSeconds(30)), Reason.STORED_BINDING_INVALID);
        audit(row.event(), decision);
        if (parent == null) {
            require("CURATED_BOOTSTRAP".equals(decision.kind()) && decision.proposalId() == null
                    && decision.proposalVersion() == null && decision.proposalSha256() == null && row.proposal() == null, Reason.PROPOSAL_BINDING_INVALID);
            return new Node(snapshot, null);
        }
        require("PROPOSAL_APPROVAL".equals(decision.kind()), Reason.PROPOSAL_BINDING_INVALID);
        var proposal = row.proposal();
        require(proposal != null && decision.proposalId() != null && decision.proposalId().equals(proposal.id())
                && Objects.equals(decision.proposalVersion(), proposal.version()) && proposal.version() != null
                && proposal.version() >= 0 && proposal.version() <= MAX_SAFE_VERSION
                && Objects.equals(decision.proposalSha256(), proposal.sha256()) && "PROPOSED".equals(proposal.state())
                && Short.valueOf((short) 1).equals(proposal.schemaVersion()) && !proposal.rejected(), Reason.PROPOSAL_BINDING_INVALID);
        var request = decode(proposal.request(), CatalogChangePreviewRequest.class);
        require(request.proposalId().equals(proposal.id()) && CatalogDraftCanonicalizer.sha256(request).equals(proposal.sha256())
                && CatalogDraftCanonicalizer.sha256(request.base()).equals(request.expectedBaseSha256())
                && CatalogDraftCanonicalizer.sha256(request.candidate()).equals(CatalogDraftCanonicalizer.sha256(snapshot.catalog().asDraft())),
                Reason.PROPOSAL_BINDING_INVALID);
        return new Node(snapshot, request);
    }

    private static void audit(CatalogPublicationRecord.Event event, CatalogPublicationRecord.Decision decision) {
        require(event != null && event.id() != null && decision.id().equals(event.decisionId())
                && decision.snapshotId().equals(event.snapshotId()) && decision.snapshotSha256().equals(event.snapshotSha256())
                && decision.kind().equals(event.decisionKind()) && "catalog.published".equals(event.action())
                && "CURATOR".equals(event.actorType()) && text(event.issuer(), 2048) && text(event.subject(), 256)
                && digits(event.projectId()) && digits(event.orgId()) && event.correlationId() != null
                && "SUCCEEDED".equals(event.outcome()) && event.authenticatedAt() != null && event.occurredAt() != null
                && !event.authenticatedAt().isBefore(event.occurredAt().minusSeconds(900))
                && !event.authenticatedAt().isAfter(event.occurredAt().plusSeconds(30)), Reason.STORED_BINDING_INVALID);
    }
    private <T> T decode(String json, Class<T> type) {
        require(json != null, Reason.STORED_FORMAT_INVALID);
        try { var value = mapper.readValue(json, type); require(value != null, Reason.STORED_FORMAT_INVALID); return value; }
        catch (RuntimeException failure) { throw new Invalid(Reason.STORED_FORMAT_INVALID); }
    }
    private static boolean text(String value, int max) { return value != null && !value.strip().isEmpty() && value.length() <= max; }
    private static boolean digits(String value) { return value != null && value.matches("[0-9]{1,40}"); }
    private record Node(PublishedCatalogSnapshot snapshot, CatalogChangePreviewRequest request) { }
    private static void require(boolean condition, Reason reason) { if (!condition) throw new Invalid(reason); }
    private static final class Invalid extends RuntimeException {
        final Reason reason;
        Invalid(Reason reason) { super(reason.name(), null, false, false); this.reason = reason; }
    }
}
