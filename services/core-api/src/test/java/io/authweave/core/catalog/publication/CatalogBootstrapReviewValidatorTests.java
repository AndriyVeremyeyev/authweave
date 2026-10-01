package io.authweave.core.catalog.publication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import static io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.*;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogBootstrapReviewValidatorTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogDraftValidator drafts = new CatalogDraftValidator(Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
    private final CatalogBootstrapReviewValidator validator = new CatalogBootstrapReviewValidator(drafts);

    @Test
    void allFourFamiliesRequireOneVerdictAndCountsNeverClaimSourceTruth() {
        var request = request();
        assertEquals(new CatalogBootstrapReview.Counts(9, 0, 0), validator.validate(request));
        var items = new ArrayList<>(request.observations());
        items.set(0, with(items.get(0), "verdict", SOURCE_DOES_NOT_SUPPORT_CLAIM));
        items.set(1, with(items.get(1), "verdict", INSUFFICIENT_EVIDENCE));
        assertEquals(new CatalogBootstrapReview.Counts(7, 1, 1), validator.validate(with(request, "observations", items)));
        Collections.reverse(items);
        var unordered = with(request, "observations", items);
        assertEquals(new CatalogBootstrapReview.Counts(7, 1, 1), validator.validate(unordered));
        assertEquals(CatalogDraftCanonicalizer.sha256(with(request, "observations", List.copyOf(items.reversed()))), CatalogDraftCanonicalizer.sha256(unordered));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "missing", "duplicate", "foreign-option", "foreign-fact", "invalid-candidate"})
    void incompleteForeignDuplicateAndSemanticallyInvalidCandidatesCannotBecomeReviews(String change) {
        var request = request(); var items = new ArrayList<>(request.observations());
        switch (change) {
            case "hash" -> request = with(request, "expectedCandidateSha256", "0".repeat(64));
            case "missing" -> { items.removeLast(); request = with(request, "observations", items); }
            case "duplicate" -> { items.add(items.getFirst()); request = with(request, "observations", items); }
            case "foreign-option", "foreign-fact" -> {
                items.set(0, with(items.getFirst(), change.equals("foreign-option") ? "optionId" : "factPath",
                        change.equals("foreign-option") ? "unknown-option" : "facts.UNKNOWN"));
                request = with(request, "observations", items);
            }
            case "invalid-candidate" -> {
                var json = (ObjectNode) mapper.valueToTree(request.candidate());
                ((tools.jackson.databind.node.ArrayNode) json.get("options")).add(json.at("/options/0").deepCopy());
                var draft = mapper.treeToValue(json, ProviderCatalogDraft.class);
                request = with(with(request, "candidate", draft), "expectedCandidateSha256", CatalogDraftCanonicalizer.sha256(draft));
            }
            default -> throw new AssertionError(change);
        }
        var invalid = request;
        assertEquals(CatalogBootstrapReviewException.Reason.INVALID_REQUEST,
                assertThrows(CatalogBootstrapReviewException.class, () -> validator.validate(invalid)).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z"})
    void reviewingOldOrFutureEvidenceDoesNotRewriteTheSourceTimestamp(String at) {
        var request = request(); var json = (ObjectNode) mapper.valueToTree(request.candidate());
        ((ObjectNode) json.at("/options/0/facts/SCIM/evidence")).put("observedAt", at);
        var draft = mapper.treeToValue(json, ProviderCatalogDraft.class);
        var changed = with(with(request, "candidate", draft), "expectedCandidateSha256", CatalogDraftCanonicalizer.sha256(draft));
        assertEquals(9, validator.validate(changed).supporting());
        assertEquals(Instant.parse(at), changed.candidate().options().getFirst().facts().get(io.authweave.core.catalog.ProviderCatalog.Capability.SCIM).evidence().observedAt());
    }

    @Test
    void constructorsBoundObservationTargetsAndRequireDistinctBootstrapConfirmation() {
        var request = request();
        assertThrows(AssertionError.class, () -> with(request, "schemaVersion", 2));
        assertThrows(AssertionError.class, () -> with(request, "confirmation", null));
        assertThrows(AssertionError.class, () -> with(request, "observations", List.of()));
        assertThrows(AssertionError.class, () -> with(request, "observations", Collections.nCopies(6801, request.observations().getFirst())));
        assertThrows(IllegalArgumentException.class, () -> new CatalogBootstrapReviewRequest.Observation("option", "not.a.fact", SOURCE_SUPPORTS_CLAIM));
    }

    private CatalogBootstrapReviewRequest request() {
        var draft = new CatalogPublicationLookupFixtures(mapper).root(Instant.parse("2026-09-30T12:00:00Z")).snapshot().catalog().asDraft();
        var items = drafts.validate(draft).facts().stream().map(f -> new CatalogBootstrapReviewRequest.Observation(f.optionId(), f.path(), SOURCE_SUPPORTS_CLAIM)).toList();
        return new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(draft), draft, items,
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
    }
}
