package io.authweave.core.catalog.proposal;

import java.util.HashMap;
import java.util.EnumMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;

@Service
@Transactional(readOnly = true)
public class CatalogFactReviewSummaryService {
    public static final String POLICY_VERSION = "catalog-fact-review-summary-1";
    private final CatalogProposalRepository proposals;
    private final CatalogFactReviewRepository reviews;
    private final CatalogDraftValidator validator;
    private final ObjectMapper mapper;
    public CatalogFactReviewSummaryService(CatalogProposalRepository proposals, CatalogFactReviewRepository reviews,
            CatalogDraftValidator validator, ObjectMapper mapper) {
        this.proposals = proposals; this.reviews = reviews; this.validator = validator; this.mapper = mapper;
    }

    public CatalogFactReviewSummaryPage summary(UUID proposalId, long version, int offset) {
        if (version < 0 || version > 9007199254740991L || offset < 0 || offset > 6800) {
            throw new IllegalArgumentException("Invalid summary bounds");
        }
        var snapshot = proposals.revision(proposalId, version);
        try {
            if (snapshot.requestSchemaVersion() != 1 || !"PROPOSED".equals(snapshot.state())) {
                throw new IllegalArgumentException("Unsupported snapshot");
            }
            var request = mapper.treeToValue(snapshot.request(), CatalogChangePreviewRequest.class);
            if (request == null || !proposalId.equals(request.proposalId())
                    || !snapshot.proposalSha256().equals(CatalogDraftCanonicalizer.sha256(request))) {
                throw new IllegalArgumentException("Stored request mismatch");
            }
            var validation = validator.validate(request.candidate());
            if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT || validation.factCount() > 6800) {
                throw new IllegalArgumentException("Unreviewable candidate");
            }
            var latest = new HashMap<FactKey, CatalogFactReview>();
            for (var review : reviews.latest(proposalId, version, snapshot.proposalSha256())) {
                if (latest.put(new FactKey(review.optionId(), review.factPath()), review) != null) {
                    throw new IllegalArgumentException("Duplicate fact observation");
                }
            }
            var totals = new EnumMap<Verdict, Integer>(Verdict.class);
            for (var verdict : Verdict.values()) totals.put(verdict, 0);
            int observed = 0;
            for (var fact : validation.facts()) {
                var review = latest.get(new FactKey(fact.optionId(), fact.path()));
                if (review != null) { observed++; totals.merge(review.verdict(), 1, Integer::sum); }
            }
            if (observed != latest.size()) throw new IllegalArgumentException("Observation outside candidate");
            long through = latest.values().stream().mapToLong(CatalogFactReview::reviewNumber).max().orElse(0);
            int start = Math.min(offset, validation.factCount()), end = Math.min(start + 20, validation.factCount());
            var items = validation.facts().subList(start, end).stream().map(fact -> new CatalogFactReviewSummaryPage.Item(
                    fact.optionId(), fact.path(), latest.get(new FactKey(fact.optionId(), fact.path())))).toList();
            var counts = new CatalogFactReviewSummaryPage.Counts(validation.factCount() - observed,
                    totals.get(Verdict.SOURCE_SUPPORTS_CLAIM), totals.get(Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM),
                    totals.get(Verdict.INSUFFICIENT_EVIDENCE));
            return new CatalogFactReviewSummaryPage(proposalId, version, snapshot.proposalSha256(), POLICY_VERSION,
                    through, validation.factCount(), counts, offset, items, end < validation.factCount() ? end : null,
                    false, false, false, false);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            throw new CatalogProposalException(CatalogProposalException.Reason.REPLAY_UNAVAILABLE);
        }
    }
    private record FactKey(String optionId, String factPath) { }
}
