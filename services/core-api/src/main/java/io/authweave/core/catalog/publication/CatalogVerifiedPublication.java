package io.authweave.core.catalog.publication;

import java.util.List;
import io.authweave.core.catalog.impact.CatalogProfilePlanningCoverageService;
import io.authweave.core.catalog.impact.StoredCandidateDecisionService;

/** Only historical DB verifiers construct these inputs. Neither provenance nor coverage grants authority. */
public sealed interface CatalogVerifiedPublication permits CatalogBootstrapPublicationReader.Loaded, CatalogProposalPublicationReader.Loaded {
    PublishedCatalogSnapshot snapshot();
    PublishedCatalogSnapshot.Reference reference();
    /** Bootstrap source only; a proposal publication has its own revision/review pins instead. */
    StoredCandidateDecisionService.Reference source();
    String proofSha256();
    String publicationPolicyVersion();
    String coverageManifestSha256();
    List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps();
    String loaderVersion();
    boolean historicalPublicationWorkflowVerified();
    boolean externalSourceVerificationPerformed();
    boolean assessmentResultPinned();
}
