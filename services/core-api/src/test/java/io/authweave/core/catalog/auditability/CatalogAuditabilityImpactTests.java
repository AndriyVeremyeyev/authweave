package io.authweave.core.catalog.auditability;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewFixtures.*;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityImpactService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogAuditabilityImpactTests {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CatalogAuditabilityDraftValidator drafts = new CatalogAuditabilityDraftValidator(clock, new CatalogDraftValidator(clock));
    private final CatalogAuditabilityReviewService reviews = mock(CatalogAuditabilityReviewService.class);

    private CatalogAuditabilityImpactService service() throws Exception {
        return new CatalogAuditabilityImpactService(reviews, new CatalogAuditabilityRegressionCases(mapper, new CatalogScopedProfileCases(mapper)), clock);
    }
    private CatalogAuditabilityReviewRequest candidate(int retention) throws Exception {
        var initial = request(mapper, drafts); var option = initial.candidate().auditabilityDraft().options().getFirst();
        var facts = option.facts().stream().map(f -> with(with(f, "conditions", List.of()), "documentedMinimumRetentionDays",
                f.criterion() == Criterion.AUDIT_LOG_RETENTION ? retention : null)).toList();
        return bind(with(initial.candidate(), "auditabilityDraft", with(initial.candidate().auditabilityDraft(), "options",
                List.of(with(option, "facts", facts)))), drafts);
    }
    private CatalogAuditabilityReviewService.ReviewedCandidate stored(CatalogAuditabilityReviewRequest request) {
        var counts = new CatalogAuditabilityReviewValidator(drafts).validate(request);
        var receipt = new CatalogAuditabilityReview(request.reviewId(), request.expectedBaseContentSha256(), request.expectedAuditabilityContentSha256(),
                request.expectedTargetSetSha256(), CatalogDraftCanonicalizer.sha256(request), request.candidate().baseDraft().catalogVersion(),
                request.candidate().auditabilityDraft().evidenceVersion(), request.candidate().auditabilityDraft().options().size(), request.observations().size(), counts, NOW);
        var row = new CatalogAuditabilityReviewService.ReviewedCandidate(request, receipt);
        when(reviews.reviewed(receipt.reviewId(), receipt.reviewSha256())).thenReturn(row); return row;
    }
    private Impact compare(CatalogAuditabilityReviewRequest before, CatalogAuditabilityReviewRequest after) throws Exception {
        var b = stored(before); var a = stored(after);
        return service().preview(new Request(1, b.review().reviewId(), b.review().reviewSha256(), a.review().reviewId(), a.review().reviewSha256()));
    }

    @Test void exactStoredReferencesCompareAllFourProfilesWithoutConfusingClaimsWithAuthority() throws Exception {
        var report = compare(candidate(180), candidate(30));
        assertEquals(4, report.checkedCases()); assertEquals(24, report.checkedCriteria()); assertEquals(1, report.changedFacts()); assertEquals(3, report.changedChecks());
        var publicSector = report.scenarios().stream().filter(s -> s.scenarioId().equals("public-sector-scoped")).findFirst().orElseThrow();
        var retention = publicSector.checks().getLast();
        assertEquals(Reason.RETENTION_MEETS_MINIMUM, retention.before().reason()); assertEquals(Reason.RETENTION_BELOW_MINIMUM, retention.after().reason());
        assertEquals(90, publicSector.requirements().minimumRetentionDays()); assertTrue(retention.factChanged()); assertTrue(retention.conditionalResultChanged());
        assertEquals(Reason.FACT_MISSING, publicSector.checks().get(1).after().reason());
        assertTrue(report.storedReviewsVerified()); assertTrue(report.candidateChangesEvaluated());
        var json = mapper.valueToTree(report);
        for (String flag : List.of("coverageComplete", "baselineVerified", "sourceVerificationPerformed", "factTrustChanged", "configurationVerified",
                "complianceVerified", "storedReportVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady"))
            assertFalse(json.get(flag).asBoolean(), flag);
        for (String key : List.of("sourceUrl", "summary", "actorSubject", "baseDraft", "auditabilityDraft")) assertFalse(json.toString().contains("\"" + key + "\":"));
        assertEquals(report.analysisSha256(), json.get("analysisSha256").asText());
    }

    @Test void equivalentClaimsAndDifferentHumanVerdictsDoNotManufactureCapabilityChanges() throws Exception {
        var before = candidate(180);
        var after = with(with(before, "reviewId", UUID.randomUUID()), "observations", before.observations().stream()
                .map(o -> with(o, "verdict", Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM)).toList());
        var report = compare(before, after); assertEquals(0, report.changedFacts()); assertEquals(0, report.changedChecks());
        var sides = report.scenarios().stream().flatMap(s -> s.checks().stream()).filter(c -> c.after().factSha256() != null).toList();
        assertTrue(sides.stream().allMatch(c -> c.after().sourceVerdict() == Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM));
        assertTrue(sides.stream().allMatch(c -> c.before().conditionalOutcome() == c.after().conditionalOutcome()));
        assertNotEquals(report.beforeReview().reviewSha256(), report.afterReview().reviewSha256()); assertFalse(report.factTrustChanged());
        var identity = compare(before, before); assertEquals(0, identity.changedFacts()); assertEquals(0, identity.changedChecks());
    }

    @ParameterizedTest @ValueSource(strings = {"future", "stale", "boundary", "missing", "unknown", "unsupported", "unsupported-conditions", "conditions", "duration-unknown", "unselected"})
    void uncertaintyDatesScopeAndConditionsRemainExplicit(String variant) throws Exception {
        var before = candidate(180); var option = before.candidate().auditabilityDraft().options().getFirst(); var facts = new java.util.ArrayList<>(option.facts());
        var fact = facts.getLast();
        switch (variant) {
            case "future" -> fact = with(fact, "evidence", with(fact.evidence(), "observedAt", NOW.plusNanos(1)));
            case "stale" -> fact = with(fact, "evidence", with(fact.evidence(), "observedAt", NOW.minus(EvidencePolicy.MAX_AGE).minusNanos(1)));
            case "boundary" -> fact = with(fact, "evidence", with(fact.evidence(), "observedAt", NOW.minus(EvidencePolicy.MAX_AGE)));
            case "unknown", "unsupported" -> fact = with(with(fact, "documentedMinimumRetentionDays", null), "support",
                    variant.equals("unknown") ? io.authweave.core.catalog.ProviderCatalog.Support.UNKNOWN : io.authweave.core.catalog.ProviderCatalog.Support.UNSUPPORTED);
            case "conditions" -> fact = with(fact, "conditions", List.of("External configuration must be verified"));
            case "unsupported-conditions" -> fact = with(with(with(fact, "documentedMinimumRetentionDays", null), "support",
                    io.authweave.core.catalog.ProviderCatalog.Support.UNSUPPORTED), "conditions", List.of("The configuration scope must be verified"));
            case "duration-unknown" -> fact = with(fact, "documentedMinimumRetentionDays", null);
            default -> { }
        }
        if (variant.equals("missing")) facts.removeLast(); else facts.set(facts.size() - 1, fact);
        var after = bind(with(before.candidate(), "auditabilityDraft", with(before.candidate().auditabilityDraft(), "options", List.of(with(option, "facts", facts)))), drafts);
        var requirements = variant.equals("unselected") ? new AuditabilityRequirements(Set.of(Criterion.AUDIT_LOG_EXPORT), null)
                : new AuditabilityRequirements(Set.of(Criterion.AUDIT_LOG_RETENTION), 90);
        var result = side(after, option.scope().optionId(), Criterion.AUDIT_LOG_RETENTION, requirements, NOW);
        var expected = switch (variant) {
            case "future" -> Reason.CLAIM_FROM_FUTURE; case "stale" -> Reason.CLAIM_STALE; case "boundary" -> Reason.RETENTION_MEETS_MINIMUM;
            case "missing" -> Reason.FACT_MISSING; case "unknown" -> Reason.CLAIM_UNKNOWN; case "unsupported" -> Reason.CLAIM_UNAVAILABLE;
            case "conditions", "unsupported-conditions" -> Reason.CONDITIONS_UNVERIFIED; case "duration-unknown" -> Reason.RETENTION_DURATION_UNKNOWN; default -> Reason.CRITERION_NOT_SELECTED;
        };
        assertEquals(expected, result.reason());
        if (!variant.equals("missing")) assertEquals(fact.evidence().observedAt(), result.observedAt());
        assertFalse(compare(before, after).evaluationReady());
    }

    @Test void differentBaseDraftIsConflictAndMissingOrUnreadableReviewsCannotBecomeFreshSuccess() throws Exception {
        var before = candidate(180); var b = stored(before);
        var base = with(before.candidate().baseDraft(), "catalogVersion", "different-base");
        var supplement = with(with(before.candidate().auditabilityDraft(), "baseCatalogVersion", "different-base"), "baseContentSha256", CatalogDraftCanonicalizer.sha256(base));
        var a = stored(bind(new CatalogAuditabilityDraftValidator.Request(base, supplement), drafts));
        var request = new Request(1, b.review().reviewId(), b.review().reviewSha256(), a.review().reviewId(), a.review().reviewSha256());
        assertEquals(CatalogAuditabilityReviewException.Reason.CONFLICT, assertThrows(CatalogAuditabilityReviewException.class, () -> service().preview(request)).reason());
        for (var reason : List.of(CatalogAuditabilityReviewException.Reason.NOT_FOUND, CatalogAuditabilityReviewException.Reason.READ_UNAVAILABLE)) {
            doThrow(new CatalogAuditabilityReviewException(reason)).when(reviews).reviewed(a.review().reviewId(), a.review().reviewSha256());
            assertEquals(reason, assertThrows(CatalogAuditabilityReviewException.class, () -> service().preview(request)).reason());
        }
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic read failure")).when(reviews).reviewed(a.review().reviewId(), a.review().reviewSha256());
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service().preview(request));
    }

    @Test void referencesRejectWrongVersionsAndCallerCannotSubstituteAnUnboundDigest() {
        assertThrows(IllegalArgumentException.class, () -> new Request(2, UUID.randomUUID(), "0".repeat(64), UUID.randomUUID(), "1".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new Request(1, UUID.randomUUID(), "bad", UUID.randomUUID(), "1".repeat(64)));
        assertThrows(NullPointerException.class, () -> new Request(1, null, "0".repeat(64), UUID.randomUUID(), "1".repeat(64)));
    }
}
