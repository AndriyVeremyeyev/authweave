package io.authweave.core.catalog.proposal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import static io.authweave.core.generated.jooq.tables.CatalogFactReviews.CATALOG_FACT_REVIEWS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;

@Repository
@Transactional(readOnly = true)
public class CatalogFactReviewRepository {
    private static final int PAGE_SIZE = 20;
    private final DSLContext dsl;
    public CatalogFactReviewRepository(DSLContext dsl) { this.dsl = dsl; }

    public CatalogFactReviewPage page(UUID proposalId, long version, long afterReviewNumber) {
        if (version < 0 || version > 9007199254740991L || afterReviewNumber < 0 || afterReviewNumber > 9007199254740991L) {
            throw new IllegalArgumentException("Invalid review history bounds");
        }
        var revision = CATALOG_PROPOSAL_REVISIONS;
        var digest = dsl.select(revision.PROPOSAL_SHA256).from(revision)
                .where(revision.PROPOSAL_ID.eq(proposalId)).and(revision.VERSION.eq(version)).fetchOne();
        if (digest == null) throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        var r = CATALOG_FACT_REVIEWS;
        // Read the historical ledger without replay, current date policy, head locks or actor audit projection.
        var rows = dsl.selectFrom(r).where(r.PROPOSAL_ID.eq(proposalId)).and(r.PROPOSAL_VERSION.eq(version))
                .and(r.PROPOSAL_SHA256.eq(digest.value1())).and(r.REVIEW_NUMBER.gt(afterReviewNumber))
                .orderBy(r.REVIEW_NUMBER.asc()).limit(PAGE_SIZE + 1).fetch(CatalogFactReview::from);
        boolean more = rows.size() > PAGE_SIZE;
        var items = more ? rows.subList(0, PAGE_SIZE) : rows;
        return new CatalogFactReviewPage(proposalId, version, digest.value1(), afterReviewNumber, items,
                more ? items.getLast().reviewNumber() : null);
    }
}
