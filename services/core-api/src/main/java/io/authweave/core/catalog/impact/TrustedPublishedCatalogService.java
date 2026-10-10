package io.authweave.core.catalog.impact;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.catalog.publication.CatalogBootstrapPublicationReader;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;

/** Core-only input adapter. Accepts an exact reference, not caller-provided manifests/assertions.
 * Historical publication provenance is verified; current evidence freshness remains a kernel check.
 * Does not cast into ProviderCatalog.SYNTHETIC, activate an assessment or pin/save a result. */
@Service
public class TrustedPublishedCatalogService {
    private final CatalogBootstrapPublicationReader publications;
    private final StoredCandidateDecisionService reviews;
    public TrustedPublishedCatalogService(CatalogBootstrapPublicationReader publications, StoredCandidateDecisionService reviews) {
        this.publications = publications; this.reviews = reviews;
    }
    public record Inputs(CatalogBootstrapPublicationReader.Loaded publication, CandidateDecisionImpactEvaluator.Snapshot decisionInputs) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inputs load(PublishedCatalogSnapshot.Reference reference) {
        var publication = publications.load(reference);
        // Same consistent DB snapshot as proof replay; retain original ordered source JSON and observation dates.
        var inputs = reviews.load(publication.source());
        return new Inputs(publication, inputs.snapshot());
    }
}
