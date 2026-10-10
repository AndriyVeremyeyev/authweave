package io.authweave.core.catalog.impact;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.catalog.publication.CatalogBootstrapPublicationReader;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;
import io.authweave.core.catalog.publication.CatalogVerifiedPublication;
import io.authweave.core.catalog.publication.CatalogProposalPublicationReader;

/** Core-only input adapter. Accepts an exact reference, not caller-provided manifests/assertions.
 * Historical publication provenance is verified; current evidence freshness remains a kernel check.
 * Does not cast into ProviderCatalog.SYNTHETIC, activate an assessment or pin/save a result. */
@Service
public class TrustedPublishedCatalogService {
    private final CatalogBootstrapPublicationReader publications;
    private final StoredCandidateDecisionService reviews;
    private final ObjectProvider<CatalogProposalPublicationReader> successors;
    public TrustedPublishedCatalogService(CatalogBootstrapPublicationReader publications, StoredCandidateDecisionService reviews,
            ObjectProvider<CatalogProposalPublicationReader> successors) {
        this.publications = publications; this.reviews = reviews; this.successors = successors;
    }
    public record Inputs(CatalogVerifiedPublication publication, CandidateDecisionImpactEvaluator.Snapshot decisionInputs) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inputs load(PublishedCatalogSnapshot.Reference reference) {
        java.util.Objects.requireNonNull(reference);
        // Dispatch without catching a joined transactional exception (which marks the shared transaction rollback-only).
        // If a root proof exists but is invalid, its verifier denies; never try a successor or synthetic fallback.
        if (!publications.proofStored(reference.snapshotId())) {
            var successor = successors.getObject().load(reference);
            return new Inputs(successor, successor.decisionInputs());
        }
        var publication = publications.load(reference);
        // Same consistent DB snapshot as proof replay; retain original ordered source JSON and observation dates.
        var inputs = reviews.load(publication.source());
        return new Inputs(publication, inputs.snapshot());
    }
}
