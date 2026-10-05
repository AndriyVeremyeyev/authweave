package io.authweave.core.catalog.auditability;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.AuditabilityCatalog;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewFixtures.*;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityImpactCoverageService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogAuditabilityImpactCoverageTests {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CatalogAuditabilityDraftValidator drafts = new CatalogAuditabilityDraftValidator(clock, new CatalogDraftValidator(clock));
    private final CatalogAuditabilityReviewService reviews = mock(CatalogAuditabilityReviewService.class);
    private final CatalogScopedProfileCases base = base();
    private final CatalogAuditabilityRegressionCases cases = cases();
    private final CatalogProfileImpactCoverageService legacy = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, mapper));
    private final CatalogProfileImpactCoverageV6Service structural = spy(new CatalogProfileImpactCoverageV6Service(legacy, cases,
            new CatalogAuditabilityRegressionService(cases, resource("synthetic.v4.json", ProviderCatalog.class), resource("auditability-evidence.v1.json", AuditabilityCatalog.class))));
    private final CatalogAuditabilityImpactService impacts = spy(new CatalogAuditabilityImpactService(reviews, cases, clock));
    private final CatalogAuditabilityImpactCoverageService service = new CatalogAuditabilityImpactCoverageService(impacts, structural, cases);
    private CatalogScopedProfileCases base() { try { return new CatalogScopedProfileCases(mapper); } catch (Exception e) { throw new AssertionError(e); } }
    private CatalogAuditabilityRegressionCases cases() { try { return new CatalogAuditabilityRegressionCases(mapper, base); } catch (Exception e) { throw new AssertionError(e); } }
    private <T> T resource(String name, Class<T> type) {
        try (var stream = new ClassPathResource("catalog/" + name).getInputStream()) { return mapper.readValue(stream, type); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private CatalogAuditabilityReviewRequest candidate(int retention) throws Exception {
        var original = request(mapper, drafts); var option = original.candidate().auditabilityDraft().options().getFirst();
        var facts = option.facts().stream().map(f -> with(with(f, "conditions", List.of()), "documentedMinimumRetentionDays",
                f.criterion() == Criterion.AUDIT_LOG_RETENTION ? retention : null)).toList();
        return bind(with(original.candidate(), "auditabilityDraft", with(original.candidate().auditabilityDraft(), "options", List.of(with(option, "facts", facts)))), drafts);
    }
    private CatalogAuditabilityReview stored(CatalogAuditabilityReviewRequest request) {
        var receipt = new CatalogAuditabilityReview(request.reviewId(), request.expectedBaseContentSha256(), request.expectedAuditabilityContentSha256(),
                request.expectedTargetSetSha256(), CatalogDraftCanonicalizer.sha256(request), request.candidate().baseDraft().catalogVersion(),
                request.candidate().auditabilityDraft().evidenceVersion(), request.candidate().auditabilityDraft().options().size(), request.observations().size(),
                new CatalogAuditabilityReviewValidator(drafts).validate(request), NOW);
        when(reviews.reviewed(receipt.reviewId(), receipt.reviewSha256())).thenReturn(new CatalogAuditabilityReviewService.ReviewedCandidate(request, receipt));
        return receipt;
    }
    private CatalogAuditabilityImpactService.Request input(CatalogAuditabilityReviewRequest before, CatalogAuditabilityReviewRequest after) {
        var b = stored(before); var a = stored(after);
        return new CatalogAuditabilityImpactService.Request(1, b.reviewId(), b.reviewSha256(), a.reviewId(), a.reviewSha256());
    }
    private CatalogAuditabilityImpactService.Request input() throws Exception { return input(candidate(180), candidate(30)); }

    @Test void actualCandidateChecksBindEveryAuditInputWhileAllOtherDimensionsStayStructural() throws Exception {
        assertEquals("53cf742af80c1e86e6a19ade9986efec53dea8dc9b5b8b8a629520279b96e1de", MANIFEST_SHA256);
        var request = input(); var result = service.preview(request);
        assertEquals(12, result.checkedAuditabilityDimensions()); assertEquals(124, result.structuralOnlyDimensions().size());
        assertEquals(structural.inspectAt(NOW), result.profileCoverage()); assertEquals(impacts.preview(request), result.candidateImpact());
        assertEquals(CatalogDraftCanonicalizer.sha256(result.profileCoverage()), result.profileCoverageSha256());
        assertEquals(result.candidateImpact().analysisSha256(), result.candidateImpactSha256());
        var retention = result.dimensions().stream().filter(d -> d.scenarioId().equals("public-sector-scoped") && d.profilePath().endsWith("minimumRetentionDays")).findFirst().orElseThrow();
        assertEquals(new Outcomes(1, 0, 0), retention.before()); assertEquals(new Outcomes(0, 1, 0), retention.after());
        assertEquals(1, retention.changedChecks()); assertEquals(1, retention.changedFacts());
        var unselected = result.dimensions().stream().filter(d -> d.scenarioId().equals("partner-portal-scoped") && d.profilePath().endsWith("minimumRetentionDays")).findFirst().orElseThrow();
        assertEquals("SCOPE_GUARD_ONLY", unselected.state()); assertTrue(unselected.evidenceCriteria().isEmpty());
        assertEquals(new Outcomes(0, 0, 0), unselected.after());
        assertTrue(result.profileCoverage().verificationGaps().containsAll(CatalogProfileImpactCoverageV6Service.VERIFICATION_GAPS));
        assertFalse(result.profileCoverage().candidateAuditabilityChangesEvaluated(), "Do not reinterpret the old structural contract.");
        assertTrue(result.candidateAuditabilityChangesEvaluated()); assertTrue(result.storedReviewsVerified());
        var json = mapper.valueToTree(result);
        for (String flag : List.of("coverageComplete", "baselineVerified", "sourceVerificationPerformed", "factTrustChanged", "configurationVerified",
                "complianceVerified", "storedReportVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) assertFalse(json.get(flag).asBoolean(), flag);
        for (String key : List.of("actorSubject", "sourceUrl", "summary", "baseDraft", "auditabilityDraft")) assertFalse(json.toString().contains("\"" + key + "\":"));
        assertEquals(result.analysisSha256(), json.get("analysisSha256").asText());
        verify(impacts, times(2)).preview(request); verify(reviews, never()).record(any(), any());
    }

    @Test void identityComparisonHasCompleteCoverageRowsButDoesNotManufactureChangedChecks() throws Exception {
        var before = candidate(180); var result = service.preview(input(before, before));
        assertEquals(12, result.checkedAuditabilityDimensions()); assertTrue(result.dimensions().stream().allMatch(d -> d.changedFacts() == 0 && d.changedChecks() == 0));
        assertFalse(result.coverageComplete()); assertEquals(result, service.preview(input(before, before)));
    }

    @ParameterizedTest @ValueSource(ints = {2, 100})
    void everyCandidateScopeGetsItsOwnRowsWithoutMultiplyingUnverifiedStructuralInputs(int count) throws Exception {
        var before = expand(candidate(180), count); var after = expand(candidate(30), count);
        var result = service.preview(input(before, after));
        assertEquals(12 * count, result.checkedAuditabilityDimensions()); assertEquals(124, result.structuralOnlyDimensions().size());
        assertEquals(4 * count, result.candidateImpact().checkedCases()); assertEquals(count, result.candidateImpact().changedFacts());
        assertEquals(count, result.dimensions().stream().map(Dimension::optionScope).distinct().count());
        assertEquals(12 * count, result.dimensions().stream().map(d -> List.of(d.scenarioId(), d.optionScope(), d.profilePath())).distinct().count());
        assertFalse(result.coverageComplete());
    }
    private CatalogAuditabilityReviewRequest expand(CatalogAuditabilityReviewRequest original, int count) {
        var baseOptions = java.util.stream.IntStream.range(0, count).mapToObj(i -> with(with(original.candidate().baseDraft().options().getFirst(), "id", "example-scope-" + i), "configuration", "Synthetic configuration " + i)).toList();
        var baseDraft = with(original.candidate().baseDraft(), "options", baseOptions);
        var option = original.candidate().auditabilityDraft().options().getFirst();
        var options = java.util.stream.IntStream.range(0, count).mapToObj(i -> with(option, "scope", with(with(option.scope(), "optionId", "example-scope-" + i), "configuration", "Synthetic configuration " + i))).toList();
        var audit = with(with(original.candidate().auditabilityDraft(), "baseContentSha256", CatalogDraftCanonicalizer.sha256(baseDraft)), "options", options);
        return bind(new CatalogAuditabilityDraftValidator.Request(baseDraft, audit), drafts);
    }

    @ParameterizedTest @ValueSource(strings = {"future", "stale", "conditions", "unknown", "missing", "boundary"})
    void missingOrUnverifiedClaimRemainsIndeterminateInTheBoundDimension(String variant) throws Exception {
        var before = candidate(180); var option = before.candidate().auditabilityDraft().options().getFirst();
        var facts = new ArrayList<>(option.facts()); var fact = facts.getLast();
        if (variant.equals("future")) fact = with(fact, "evidence", with(fact.evidence(), "observedAt", NOW.plusNanos(1)));
        if (variant.equals("stale") || variant.equals("boundary")) fact = with(fact, "evidence", with(fact.evidence(), "observedAt",
                NOW.minus(EvidencePolicy.MAX_AGE).minusNanos(variant.equals("stale") ? 1 : 0)));
        if (variant.equals("conditions")) fact = with(fact, "conditions", List.of("Deployed configuration not verified"));
        if (variant.equals("unknown")) fact = with(with(fact, "documentedMinimumRetentionDays", null), "support", ProviderCatalog.Support.UNKNOWN);
        if (variant.equals("missing")) facts.removeLast(); else facts.set(facts.size() - 1, fact);
        var after = bind(with(before.candidate(), "auditabilityDraft", with(before.candidate().auditabilityDraft(), "options", List.of(with(option, "facts", facts)))), drafts);
        var result = service.preview(input(before, after));
        var row = result.dimensions().stream().filter(d -> d.scenarioId().equals("public-sector-scoped") && d.profilePath().endsWith("minimumRetentionDays")).findFirst().orElseThrow();
        assertEquals(variant.equals("boundary") ? new Outcomes(1, 0, 0) : new Outcomes(0, 0, 1), row.after());
        assertFalse(result.coverageComplete()); assertFalse(result.sourceVerificationPerformed());
    }

    @ParameterizedTest @ValueSource(strings = {"time", "missing", "duplicate", "reordered", "counts", "criterion", "profile", "scope", "changed"})
    void incorrectDimensionMatricesCannotHideGapsOrBorrowOtherScopes(String variant) throws Exception {
        var good = service.preview(input()); var rows = new ArrayList<>(good.dimensions()); var first = rows.getFirst();
        if (variant.equals("missing")) rows.removeLast(); if (variant.equals("duplicate")) rows.set(1, first);
        if (variant.equals("reordered")) java.util.Collections.swap(rows, 0, 1);
        if (List.of("counts", "criterion", "profile", "scope", "changed").contains(variant)) {
            var replacement = new Dimension(first.scenarioId(), variant.equals("profile") ? "0".repeat(64) : first.profileSha256(),
                    variant.equals("scope") ? with(first.optionScope(), "region", "Other region") : first.optionScope(), first.profilePath(),
                    variant.equals("criterion") ? List.of() : first.evidenceCriteria(),
                    variant.equals("criterion") ? new Outcomes(0, 0, 0) : variant.equals("counts") ? new Outcomes(0, 0, first.evidenceCriteria().size()) : first.before(),
                    variant.equals("criterion") ? new Outcomes(0, 0, 0) : first.after(),
                    variant.equals("criterion") ? 0 : variant.equals("changed") ? 0 : first.changedFacts(), variant.equals("criterion") ? 0 : first.changedChecks());
            rows.set(0, replacement);
        }
        assertThrows(IllegalArgumentException.class, () -> new Check(variant.equals("time") ? NOW.plusNanos(1) : NOW, good.profileCoverage(), good.candidateImpact(), rows));
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-id", "wrong-hash", "scenario-digest", "scenario-profile", "not-checked", "structural-time"})
    void foreignServiceBindingsFailClosedRatherThanBecomingACoverageAssertion(String variant) throws Exception {
        var request = input(); var impact = impacts.preview(request);
        if (variant.equals("wrong-id") || variant.equals("wrong-hash")) impact = with(impact, "beforeReview", with(impact.beforeReview(),
                variant.equals("wrong-id") ? "reviewId" : "reviewSha256", variant.equals("wrong-id") ? java.util.UUID.randomUUID() : "0".repeat(64)));
        if (variant.equals("scenario-digest")) impact = with(impact, "scenarioSetSha256", "0".repeat(64));
        if (variant.equals("scenario-profile")) impact = with(impact, "scenarios", impact.scenarios().stream().map(s -> with(s, "profileSha256", "0".repeat(64))).toList());
        doReturn(impact).when(impacts).preview(request);
        if (variant.equals("not-checked")) doReturn(CatalogProfileImpactCoverageV6Service.Check.notChecked()).when(structural).inspectAt(NOW);
        if (variant.equals("structural-time")) doReturn(structural.inspectAt(NOW.plusNanos(1))).when(structural).inspectAt(NOW);
        assertEquals(CatalogAuditabilityReviewException.Reason.READ_UNAVAILABLE,
                assertThrows(CatalogAuditabilityReviewException.class, () -> service.preview(request)).reason());
    }

    @Test void unavailableReviewOrDatabaseNeverFallsBackToSyntheticCoverage() throws Exception {
        var request = input();
        for (var reason : List.of(CatalogAuditabilityReviewException.Reason.NOT_FOUND, CatalogAuditabilityReviewException.Reason.READ_UNAVAILABLE)) {
            doThrow(new CatalogAuditabilityReviewException(reason)).when(impacts).preview(request);
            assertEquals(reason, assertThrows(CatalogAuditabilityReviewException.class, () -> service.preview(request)).reason());
        }
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic unavailable database")).when(impacts).preview(request);
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service.preview(request));
        verifyNoInteractions(structural);
    }

    @ParameterizedTest @ValueSource(strings = {"negative", "overflow", "too-many", "mismatched", "duplicate-criteria"})
    void rowConstructorsRejectUnboundedCountsAndInventedAppliedCriteria(String variant) throws Exception {
        var good = service.preview(input()).dimensions().getFirst();
        assertThrows(IllegalArgumentException.class, () -> {
            if (variant.equals("negative")) new Outcomes(-1, 0, 0);
            else if (variant.equals("overflow")) new Outcomes(Integer.MAX_VALUE, Integer.MAX_VALUE, 2);
            else if (variant.equals("too-many")) new Outcomes(4, 3, 0);
            else new Dimension(good.scenarioId(), good.profileSha256(), good.optionScope(), good.profilePath(),
                    variant.equals("duplicate-criteria") ? List.of(Criterion.AUDIT_LOG_RETENTION, Criterion.AUDIT_LOG_RETENTION) : good.evidenceCriteria(),
                    new Outcomes(0, 0, 0), good.after(), good.changedFacts(), good.changedChecks());
        });
    }
}
