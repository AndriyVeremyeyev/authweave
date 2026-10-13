package io.authweave.core.catalog.impact;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import io.authweave.core.catalog.proposal.CatalogFactReview;
import io.authweave.core.generated.audit.tables.records.CatalogFactReviewEventsRecord;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class StoredProposalDecisionReviewOriginTests {
    @Test void legacyManualAuditDigestRetainsExactlyItsPreMigrationShape() {
        var event = new CatalogFactReviewEventsRecord(); event.setReviewOrigin("MANUAL_SOURCE_REVIEW");
        var old = new LinkedHashMap<>(event.intoMap()); old.remove("review_origin");
        var payload = StoredProposalDecisionService.factReviewAuditPayload(event);
        assertEquals(old, payload); assertFalse(payload.containsKey("review_origin"));
        var mapper = JsonMapper.builder().build();
        assertEquals(DecisionCanonicalizer.sha256(mapper.valueToTree(old)), DecisionCanonicalizer.sha256(mapper.valueToTree(payload)));
        assertEquals("MANUAL_SOURCE_REVIEW", event.getReviewOrigin());
        assertEquals("HUMAN_SOURCE_REVIEW_OBSERVATION", CatalogFactReview.kind(event.getReviewOrigin()));
    }
    @Test void ownerOriginIsDigestBoundAndDoesNotMasqueradeAsPersonalReading() {
        var event = new CatalogFactReviewEventsRecord(); event.setReviewOrigin("OWNER_APPROVES_ASSISTANT_SOURCE_ASSESSMENT");
        var payload = StoredProposalDecisionService.factReviewAuditPayload(event);
        assertEquals(event.intoMap(), payload); assertEquals(event.getReviewOrigin(), payload.get("review_origin"));
        assertEquals("OWNER_APPROVED_ASSISTANT_SOURCE_ASSESSMENT", CatalogFactReview.kind(event.getReviewOrigin()));
        event.setReviewOrigin("MANUAL_SOURCE_REVIEW");
        var mapper = JsonMapper.builder().build();
        assertNotEquals(DecisionCanonicalizer.sha256(mapper.valueToTree(payload)),
                DecisionCanonicalizer.sha256(mapper.valueToTree(StoredProposalDecisionService.factReviewAuditPayload(event))));
    }
    @Test void unknownOrMissingOriginFailsClosed() {
        var event = new CatalogFactReviewEventsRecord(); assertThrows(RuntimeException.class, () -> StoredProposalDecisionService.factReviewAuditPayload(event));
        event.setReviewOrigin("VERIFIED"); assertThrows(IllegalArgumentException.class, () -> StoredProposalDecisionService.factReviewAuditPayload(event));
    }
}
