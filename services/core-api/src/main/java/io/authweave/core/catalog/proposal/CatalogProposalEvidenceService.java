package io.authweave.core.catalog.proposal;

import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreview;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.evaluation.EvidencePolicy;

/** Reads an exact revision and reuses Core date policy; sources remain caller-supplied text. */
@Service
public final class CatalogProposalEvidenceService {
    public static final String POLICY_VERSION = "catalog-proposal-evidence-review-1";
    private static final int PAGE_SIZE = 20;
    private final CatalogProposalRepository repository;
    private final CatalogDraftValidator validator;
    private final ObjectMapper mapper;
    public CatalogProposalEvidenceService(CatalogProposalRepository repository,
            CatalogDraftValidator validator, ObjectMapper mapper) {
        this.repository = repository; this.validator = validator; this.mapper = mapper;
    }

    public CatalogProposalEvidencePage review(UUID proposalId, long version, int offset) {
        if (offset < 0 || offset > 6800) throw new IllegalArgumentException("Invalid evidence page offset");
        var snapshot = repository.revision(proposalId, version);
        try {
            if (snapshot.requestSchemaVersion() != 1 || !"PROPOSED".equals(snapshot.state())) {
                throw new IllegalArgumentException("Unsupported stored format");
            }
            var request = mapper.treeToValue(snapshot.request(), CatalogChangePreviewRequest.class);
            if (request == null || !proposalId.equals(request.proposalId()) ||
                    !snapshot.proposalSha256().equals(CatalogDraftCanonicalizer.sha256(request))) {
                throw new IllegalArgumentException("Stored request mismatch");
            }
            var validation = validator.validate(request.candidate());
            if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT) {
                throw new IllegalArgumentException("Candidate cannot be reviewed by current policy");
            }
            var scopes = request.candidate().options().stream().collect(Collectors.toMap(
                    option -> option.id(), Function.identity()));
            int start = Math.min(offset, validation.factCount());
            int end = Math.min(start + PAGE_SIZE, validation.factCount());
            var items = validation.facts().subList(start, end).stream().map(fact -> {
                var option = scopes.get(fact.optionId());
                var scope = new CatalogChangePreview.OptionScope(option.providerId(), option.product(),
                        option.plan(), option.deployment(), option.region(), option.configuration());
                return new CatalogProposalEvidencePage.Item(fact.optionId(), fact.path(), scope,
                        fact.evidenceStatus(), fact.freshness(), fact.conditions(), fact.evidence());
            }).toList();
            int current = 0, stale = 0, future = 0;
            for (var fact : validation.facts()) {
                switch (fact.freshness()) { case CURRENT -> current++; case STALE -> stale++; case FUTURE -> future++; }
            }
            return new CatalogProposalEvidencePage(proposalId, version, snapshot.proposalSha256(),
                    request.candidate().catalogVersion(), validation.evaluatedAt(), POLICY_VERSION,
                    EvidencePolicy.MAX_AGE.toDays(), false, false, false, false, validation.factCount(),
                    new CatalogChangePreview.FreshnessCounts(current, stale, future), offset, items,
                    end < validation.factCount() ? end : null);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            throw new CatalogProposalException(CatalogProposalException.Reason.REPLAY_UNAVAILABLE);
        }
    }
}
