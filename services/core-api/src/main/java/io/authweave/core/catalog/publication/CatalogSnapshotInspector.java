package io.authweave.core.catalog.publication;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftValidator;

/** Offline format and content comparison only. No registry, decision verifier, publisher, source fetcher or evaluator. */
@Component
public final class CatalogSnapshotInspector {
    public static final String POLICY_VERSION = "catalog-snapshot-format-inspection-1";
    private final CatalogDraftValidator validator;
    public CatalogSnapshotInspector(CatalogDraftValidator validator) { this.validator = validator; }
    public enum Status { VALID_SNAPSHOT_FORMAT, INVALID_SNAPSHOT_FORMAT }
    public enum Issue {
        CONTENT_DIGEST_MISMATCH, SNAPSHOT_DIGEST_MISMATCH, CATALOG_CONTENT_INVALID,
        DUPLICATE_EVIDENCE_STATUS_TARGET, MISSING_EVIDENCE_STATUS_TARGET, UNKNOWN_EVIDENCE_STATUS_TARGET,
        PREVIOUS_SNAPSHOT_SELF_REFERENCE, CATALOG_VERSION_REUSED, EVIDENCE_AFTER_DECLARED_PUBLICATION
    }
    public record Inspection(UUID snapshotId, String catalogVersion, String policyVersion, Status status,
            String computedContentSha256, String computedSnapshotSha256, int factCount, List<Issue> issues,
            boolean baselineVerified, boolean sourceVerificationPerformed, boolean approvalGranted,
            boolean writesPerformed, boolean evaluationReady) {
        public Inspection { issues = List.copyOf(issues); }
    }
    public enum Authority { TRUSTED_PUBLICATION_LOOKUP_UNAVAILABLE }
    public record BaselineInspection(Inspection snapshot, boolean referenceMatches, boolean suppliedBaseDigestMatches,
            boolean suppliedBaseContentMatches, Authority authority, boolean baselineVerified) { }

    public Inspection inspect(PublishedCatalogSnapshot snapshot) {
        var validation = validator.validate(snapshot.catalog().asDraft());
        var issues = new ArrayList<Issue>();
        String contentDigest = snapshot.computedContentSha256(), manifestDigest = snapshot.computedSnapshotSha256();
        if (!snapshot.contentSha256().equals(contentDigest)) issues.add(Issue.CONTENT_DIGEST_MISMATCH);
        if (!snapshot.snapshotSha256().equals(manifestDigest)) issues.add(Issue.SNAPSHOT_DIGEST_MISMATCH);
        if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT) issues.add(Issue.CATALOG_CONTENT_INVALID);
        var recorded = new HashSet<Target>();
        for (var fact : validation.facts()) recorded.add(new Target(fact.optionId(), fact.path()));
        var declared = new HashSet<Target>();
        for (var status : snapshot.factEvidenceStatuses()) {
            if (!declared.add(new Target(status.optionId(), status.factPath()))) {
                if (!issues.contains(Issue.DUPLICATE_EVIDENCE_STATUS_TARGET)) issues.add(Issue.DUPLICATE_EVIDENCE_STATUS_TARGET);
            }
        }
        if (!declared.containsAll(recorded)) issues.add(Issue.MISSING_EVIDENCE_STATUS_TARGET);
        if (!recorded.containsAll(declared)) issues.add(Issue.UNKNOWN_EVIDENCE_STATUS_TARGET);
        var parent = snapshot.previousSnapshot();
        if (parent != null) {
            if (parent.snapshotId().equals(snapshot.snapshotId())) issues.add(Issue.PREVIOUS_SNAPSHOT_SELF_REFERENCE);
            if (parent.catalogVersion().equals(snapshot.catalog().catalogVersion())) issues.add(Issue.CATALOG_VERSION_REUSED);
        }
        if (validation.facts().stream().anyMatch(fact -> fact.evidence().observedAt().isAfter(snapshot.publication().publishedAt()))) {
            issues.add(Issue.EVIDENCE_AFTER_DECLARED_PUBLICATION);
        }
        return new Inspection(snapshot.snapshotId(), snapshot.catalog().catalogVersion(), POLICY_VERSION,
                issues.isEmpty() ? Status.VALID_SNAPSHOT_FORMAT : Status.INVALID_SNAPSHOT_FORMAT,
                contentDigest, manifestDigest, validation.factCount(), issues,
                false, false, false, false, false);
    }

    /** Exact declared identity/content agreement still cannot authenticate a baseline without authoritative Core storage. */
    public BaselineInspection inspectBaseline(PublishedCatalogSnapshot snapshot, PublishedCatalogSnapshot.Reference reference,
            CatalogChangePreviewRequest request) {
        var inspection = inspect(snapshot);
        boolean referenceMatches = snapshot.snapshotId().equals(reference.snapshotId())
                && snapshot.catalog().catalogVersion().equals(reference.catalogVersion())
                && snapshot.snapshotSha256().equals(reference.snapshotSha256());
        String supplied = CatalogDraftCanonicalizer.sha256(request.base());
        boolean digestMatches = supplied.equals(request.expectedBaseSha256());
        boolean contentMatches = supplied.equals(CatalogDraftCanonicalizer.sha256(snapshot.catalog().asDraft()));
        return new BaselineInspection(inspection, referenceMatches, digestMatches, contentMatches,
                Authority.TRUSTED_PUBLICATION_LOOKUP_UNAVAILABLE, false);
    }
    private record Target(String optionId, String factPath) { }
}
