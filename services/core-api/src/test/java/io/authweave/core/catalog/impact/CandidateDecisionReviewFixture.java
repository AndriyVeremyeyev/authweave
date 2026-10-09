package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import io.authweave.core.catalog.auditability.*;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.publication.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateAuditabilityInputTests.*;

/** Shared isolated-test DB setup. Fictional human assertions, never local IdP/application data. */
final class CandidateDecisionReviewFixture {
    private final ObjectMapper mapper;
    private final CatalogBootstrapReviewService bases;
    private final CatalogAuditabilityReviewService audits;
    private final CatalogAuditabilityDraftValidator drafts;
    CandidateDecisionReviewFixture(ObjectMapper mapper, CatalogBootstrapReviewService bases, CatalogAuditabilityReviewService audits,
            CatalogAuditabilityDraftValidator drafts) { this.mapper = mapper; this.bases = bases; this.audits = audits; this.drafts = drafts; }
    record Fixture(CatalogBootstrapReview base, CatalogAuditabilityReview audit, StoredCandidateDecisionService.Reference reference) { }
    Fixture stored() { return stored(b -> { }, a -> { }, Verdict.SOURCE_SUPPORTS_CLAIM); }
    Fixture stored(Consumer<ObjectNode> baseChange, Consumer<ObjectNode> auditChange, Verdict verdict) {
        var raw = (ObjectNode) base(); freshSourceDates(raw); baseChange.accept(raw);
        var base = mapper.treeToValue(raw, ProviderCatalogDraft.class);
        var observations = base.options().stream().flatMap(o -> CatalogDraftFacts.entries(o).keySet().stream().sorted()
                .map(path -> new CatalogBootstrapReviewRequest.Observation(o.id(), path, Verdict.SOURCE_SUPPORTS_CLAIM))).toList();
        var request = new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(base), base, observations,
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
        var receipt = bases.record(request, actor()).review();
        var savedBase = mapper.readTree(bases.loadForDecision(receipt.reviewId(), receipt.reviewSha256()).candidateJson());
        var supplement = (ObjectNode) supplement(); freshSourceDates(supplement);
        supplement.put("baseContentSha256", request.expectedCandidateSha256()); supplement.put("baseCatalogVersion", base.catalogVersion()); auditChange.accept(supplement);
        var candidate = new CatalogAuditabilityDraftValidator.Request(base, mapper.treeToValue(supplement, AuditabilityCatalogDraft.class));
        var validation = drafts.validate(candidate);
        var manual = new CatalogAuditabilityReviewRequest(1, UUID.randomUUID(), validation.baseValidation().contentSha256(),
                validation.contentSha256(), validation.reviewTargetSetSha256(), candidate, validation.targets().stream()
                    .map(t -> new CatalogAuditabilityReviewRequest.Observation(t.scope().optionId(), t.fact().criterion(), t.targetSha256(), verdict)).toList(),
                CatalogAuditabilityReviewRequest.Confirmation.MANUAL_AUDITABILITY_SOURCE_REVIEW);
        var audit = audits.record(manual, actor()).review();
        var savedAudit = mapper.readTree(audits.loadForDecision(audit.reviewId(), audit.reviewSha256()).candidateJson()).get("auditabilityDraft");
        var reference = new StoredCandidateDecisionService.Reference(receipt.reviewId(), receipt.reviewSha256(), DecisionCanonicalizer.sha256(savedBase),
                new StoredCandidateDecisionService.AuditReference(audit.reviewId(), audit.reviewSha256(), DecisionCanonicalizer.sha256(savedAudit)));
        return new Fixture(receipt, audit, reference);
    }
    private static void freshSourceDates(JsonNode document) {
        // Before fixture record, never a review-time evidence refresh. Keeps positive DB tests from expiring.
        var at = Instant.now().minusSeconds(86400).toString();
        for (var option : document.path("options")) for (var fact : option.path("facts")) ((ObjectNode) fact.get("evidence")).put("observedAt", at);
    }
    private static CuratorActor actor() { return new CuratorActor("https://identity.example.invalid", "fictional-reviewer", "123", "456", Instant.now()); }
}
