package io.authweave.core.catalog.publication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.proposal.CatalogFactReview;
import io.authweave.core.catalog.proposal.CatalogFactReviewRepository;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.evaluation.EvidencePolicy;
import io.authweave.core.catalog.impact.CatalogImpactService;
import io.authweave.core.catalog.impact.CatalogFactPathRegressionService;
import static io.authweave.core.catalog.publication.CatalogPublicationPreflight.*;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogPublicationPreflightTests {
    private static final Instant AT = Instant.parse("2026-09-30T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
    private final CatalogDraftValidator validator = new CatalogDraftValidator(clock);
    private final CatalogPublicationPreflightRepository repository = mock(CatalogPublicationPreflightRepository.class);
    private final CatalogFactReviewRepository reviews = mock(CatalogFactReviewRepository.class);
    private final CatalogPublicationRepository publications = mock(CatalogPublicationRepository.class);
    private final CatalogPublicationLookup lookup = mock(CatalogPublicationLookup.class);
    private final CatalogBootstrapReviewService bootstrapReviews = mock(CatalogBootstrapReviewService.class);
    private final CatalogPublicationImpactVerifier impacts = mock(CatalogPublicationImpactVerifier.class);
    private final CatalogPublicationFactPathVerifier storedRegressions = mock(CatalogPublicationFactPathVerifier.class);
    private final CatalogPublicationPreflight preflight = new CatalogPublicationPreflight(repository, reviews, publications,
            lookup, validator, new CatalogChangePreviewService(validator, clock), mapper, clock, bootstrapReviews, impacts,
            new CatalogFactPathRegressionService(new CatalogImpactService(new CatalogChangePreviewService(validator, clock))), storedRegressions);
    private CatalogChangePreviewRequest request;
    private CatalogPublicationPreflightRepository.Proposal row;
    private PublishedCatalogSnapshot.Reference baseline;
    private CatalogPublicationLookup.BaselineComparison comparison;
    private List<CatalogFactReview> supporting;

    @BeforeEach
    void stored() {
        var fixtures = new CatalogPublicationLookupFixtures(mapper);
        var root = fixtures.root(AT.minusSeconds(60)); var child = fixtures.child(root);
        request = child.request(); baseline = reference(root.snapshot());
        store(request);
        comparison = new CatalogPublicationLookup.BaselineComparison(new CatalogPublicationLookup.Result(
                CatalogPublicationLookup.Status.VALIDATED_STORED_LINEAGE, CatalogPublicationLookup.Reason.NONE,
                root.snapshot(), List.of(baseline)), true, true);
        when(lookup.compareBaseline(eq(baseline), any())).thenReturn(comparison);
        when(impacts.verify(any(), anyLong(), any(), any())).thenReturn(new CatalogPublicationImpactVerifier.Check(
                CatalogPublicationImpactVerifier.Status.VERIFIED_PARTIAL_ANALYSIS, UUID.randomUUID(), 1, "a".repeat(64), AT, 3, 0, 7));
        when(repository.registryEmpty()).thenReturn(true);
        when(storedRegressions.verify(any(), anyLong(), any(), any())).thenReturn(new CatalogPublicationFactPathVerifier.Check(
                CatalogPublicationFactPathVerifier.Status.VERIFIED_FACT_PATH_ANALYSIS, UUID.randomUUID(), 1, "b".repeat(64), AT, 1, 1, 0, 1, 0));
    }

    @Test
    void allSupportingFactsAndMatchingAdminAssertionsStillCannotAuthorizeAnyPublication() {
        var result = run();
        assertEquals(9, result.facts().total()); assertTrue(result.facts().allFactsHaveSupportingObservation());
        assertEquals(9, result.reviewThroughNumber()); assertTrue(result.baselineIntegrityValidated()); assertTrue(result.baselineContentMatches());
        assertEquals(List.of(Blocker.BASELINE_AUTHORITY_UNAVAILABLE, Blocker.IMPACT_COVERAGE_INCOMPLETE,
                Blocker.CURATOR_AUTHORIZATION_NOT_PERFORMED, Blocker.PUBLICATION_WORKFLOW_UNAVAILABLE), result.blockers());
        assertBlocked(result);
        assertEquals(result, run()); assertThrows(UnsupportedOperationException.class, () -> result.blockers().clear());
        var json = mapper.valueToTree(result);
        assertEquals(POLICY_VERSION, json.get("policyVersion").asText()); assertEquals("BLOCKED", json.get("status").asText());
        assertFalse(json.get("approvalGranted").asBoolean()); assertFalse(json.get("baselineVerified").asBoolean());
        assertFalse(mapper.writeValueAsString(result).contains("Fictional registry"));
        assertTrue(result.factPaths().changedFactPathsCovered()); assertEquals(68, result.factPaths().declaredFactPaths());
        assertEquals(1, result.factPaths().checkedCases()); assertEquals(AT, result.factPaths().evaluatedAt());
        assertFalse(result.factPaths().storedReportVerified()); assertFalse(result.factPaths().coverageComplete());
    }

    @Test
    void missingContradictingAndInsufficientObservationsAreSeparateAndCoverTheWholeCandidate() {
        var latest = new ArrayList<>(supporting); latest.removeLast();
        latest.set(0, with(latest.get(0), "verdict", Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM));
        latest.set(1, with(latest.get(1), "verdict", Verdict.INSUFFICIENT_EVIDENCE));
        observe(latest);
        var result = run();
        assertEquals(new FactCounts(9, 1, 6, 1, 1, 0, 0), result.facts());
        assertTrue(result.blockers().containsAll(List.of(Blocker.FACT_OBSERVATIONS_MISSING, Blocker.SOURCE_CONTRADICTION,
                Blocker.INSUFFICIENT_SOURCE_EVIDENCE))); assertFalse(result.facts().allFactsHaveSupportingObservation());
        assertBlocked(result);
    }

    @Test
    void noObservationsAreNotVacuouslySupporting() {
        observe(List.of()); var result = run();
        assertEquals(9, result.facts().unobserved()); assertEquals(0, result.reviewThroughNumber());
        assertTrue(result.blockers().contains(Blocker.FACT_OBSERVATIONS_MISSING)); assertBlocked(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"proposalId", "proposalVersion", "proposalSha256", "reviewId", "reviewNumber", "number-duplicate",
            "id-duplicate", "target-duplicate", "optionId", "factPath", "verdict", "recordedAt", "before-proposal", "future-time",
            "kind", "sourceVerificationPerformed", "approvalGranted", "catalogWritesPerformed", "factTrustChanged", "too-many"})
    void corruptOrForgedLedgerProducesNoPartialSupportingSuccess(String field) {
        var latest = new ArrayList<>(supporting); var first = latest.getFirst();
        var changed = switch (field) {
            case "proposalId", "reviewId" -> with(first, field, field.equals("reviewId") ? null : UUID.randomUUID());
            case "proposalVersion", "reviewNumber" -> with(first, field, field.equals("reviewNumber") ? 0L : 1L);
            case "proposalSha256" -> with(first, field, "0".repeat(64));
            case "optionId", "factPath", "kind" -> with(first, field, "undeclared");
            case "verdict", "recordedAt" -> with(first, field, null);
            case "before-proposal" -> with(first, "recordedAt", row.recordedAt().minusNanos(1));
            case "future-time" -> with(first, "recordedAt", AT.plusSeconds(30).plusNanos(1));
            case "number-duplicate" -> with(first, "reviewNumber", latest.get(1).reviewNumber());
            case "id-duplicate" -> with(first, "reviewId", latest.get(1).reviewId());
            case "target-duplicate" -> with(with(first, "optionId", latest.get(1).optionId()), "factPath", latest.get(1).factPath());
            case "too-many" -> first;
            default -> with(first, field, true);
        };
        latest.set(0, changed);
        if (field.equals("too-many")) while (latest.size() < 6801) latest.add(first);
        observe(latest); var result = run();
        assertTrue(result.blockers().contains(Blocker.FACT_REVIEW_LEDGER_INVALID)); assertEquals(9, result.facts().unobserved());
        assertEquals(0, result.facts().supporting()); assertEquals(0, result.reviewThroughNumber()); assertBlocked(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "version", "schemaVersion", "state", "request", "request-null", "json-null", "hash", "unknown-field", "recordedAt"})
    void malformedRevisionCannotBeReinterpretedAsReviewable(String field) {
        var invalid = switch (field) {
            case "id" -> with(row, field, UUID.randomUUID());
            case "version" -> with(row, field, 1L);
            case "schemaVersion" -> with(row, field, 2);
            case "state" -> with(row, field, "APPROVED");
            case "request" -> with(row, field, "{ broken");
            case "request-null" -> with(row, "request", null);
            case "json-null" -> with(row, "request", "null");
            case "hash" -> with(row, "request", mapper.writeValueAsString(with(request, "rationale", "Tampered rationale")));
            case "unknown-field" -> {
                var json = (ObjectNode) mapper.valueToTree(request); json.put("approvalGranted", true);
                yield with(row, "request", mapper.writeValueAsString(json));
            }
            case "recordedAt" -> with(row, field, AT.plusSeconds(31));
            default -> throw new AssertionError(field);
        };
        when(repository.proposal(any(), anyLong())).thenReturn(invalid);
        var result = run(); assertTrue(result.blockers().contains(Blocker.PROPOSAL_FORMAT_INVALID));
        assertEquals(0, result.facts().total()); verifyNoInteractions(reviews, lookup); assertBlocked(result);
    }

    @Test
    void exactDigestGuardAndMissingRevisionNeverReadReviewsOrFallback() {
        var wrong = preflight.proposal(request.proposalId(), 0, "0".repeat(64), baseline);
        assertTrue(wrong.blockers().contains(Blocker.PROPOSAL_DIGEST_MISMATCH)); verifyNoInteractions(reviews, lookup);
        when(repository.proposal(any(), anyLong())).thenReturn(null);
        var missing = run(); assertTrue(missing.blockers().contains(Blocker.PROPOSAL_NOT_FOUND)); assertBlocked(missing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"currentVersion", "rejected", "published"})
    void historicalRevisionsRejectionsAndExistingPublicationAreNotWritable(String field) {
        when(repository.proposal(any(), anyLong())).thenReturn(with(row, field, field.equals("currentVersion") ? 1L : true));
        var result = run();
        assertTrue(result.blockers().contains(switch (field) {
            case "currentVersion" -> Blocker.PROPOSAL_NOT_CURRENT;
            case "rejected" -> Blocker.PROPOSAL_REJECTED;
            default -> Blocker.PROPOSAL_ALREADY_PUBLISHED;
        })); assertBlocked(result);
    }

    @Test
    void bodyAtTheLimitIsAcceptedButOverBudgetIsWithheld() {
        when(repository.proposal(any(), anyLong())).thenReturn(with(row, "requestBytes", CatalogPublicationRepository.MAX_JSON_BYTES));
        assertFalse(run().blockers().contains(Blocker.PROPOSAL_READ_BUDGET_EXCEEDED));
        when(repository.proposal(any(), anyLong())).thenReturn(with(row, "requestBytes", CatalogPublicationRepository.MAX_JSON_BYTES + 1));
        assertTrue(run().blockers().contains(Blocker.PROPOSAL_READ_BUDGET_EXCEEDED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "stale", "future"})
    void supportingObservationsNeverRefreshSourceDatesIncludingExactAgeBoundary(String state) {
        var json = (ObjectNode) mapper.valueToTree(request);
        var observed = switch (state) {
            case "current" -> AT.minus(EvidencePolicy.MAX_AGE);
            case "stale" -> AT.minus(EvidencePolicy.MAX_AGE).minusNanos(1);
            default -> AT.plusNanos(1);
        };
        ((ObjectNode) json.at("/candidate/options/0/facts/SCIM/evidence")).put("observedAt", observed.toString());
        store(mapper.treeToValue(json, CatalogChangePreviewRequest.class)); var result = run();
        assertTrue(result.facts().allFactsHaveSupportingObservation());
        assertEquals(state.equals("stale") ? 1 : 0, result.facts().stale());
        assertEquals(state.equals("future") ? 1 : 0, result.facts().future()); assertBlocked(result);
        assertEquals(observed.toString(), mapper.valueToTree(request).at("/candidate/options/0/facts/SCIM/evidence/observedAt").asText());
    }

    @Test
    void noRealChangesOrInvalidCandidateRemainBlocked() {
        store(with(request, "candidate", request.base()));
        var noOp = run(); assertTrue(noOp.blockers().contains(Blocker.CHANGE_NOT_REVIEWABLE));
        assertTrue(noOp.blockers().contains(Blocker.FACT_PATH_REGRESSION_INCOMPLETE)); assertFalse(noOp.factPaths().changedFactPathsCovered());
        var json = (ObjectNode) mapper.valueToTree(request);
        ((tools.jackson.databind.node.ArrayNode) json.at("/candidate/options")).add(json.at("/candidate/options/0").deepCopy());
        store(mapper.treeToValue(json, CatalogChangePreviewRequest.class)); observe(List.of());
        var invalid = run(); assertTrue(invalid.blockers().contains(Blocker.CANDIDATE_INVALID));
        assertTrue(invalid.blockers().contains(Blocker.FACT_PATH_REGRESSION_BLOCKED)); assertEquals(0, invalid.factPaths().checkedCases());
    }

    @Test
    void reusedLabelsAndMissingImpactReceiptDoNotDisappearBehindSupportingFacts() {
        when(repository.labelUsed(anyString())).thenReturn(true);
        when(impacts.verify(any(), anyLong(), any(), any())).thenReturn(CatalogPublicationImpactVerifier.Check.unavailable(CatalogPublicationImpactVerifier.Status.MISSING));
        var result = run(); assertTrue(result.blockers().containsAll(List.of(Blocker.CATALOG_LABEL_ALREADY_USED,
                Blocker.IMPACT_RECEIPT_MISSING, Blocker.IMPACT_COVERAGE_INCOMPLETE))); assertBlocked(result);
    }

    @ParameterizedTest @ValueSource(strings = {"READ_BUDGET_EXCEEDED", "INVALID_RECEIPT", "INCOMPATIBLE_RULES", "REPLAY_MISMATCH", "VERIFIED_BLOCKED_ANALYSIS"})
    void incompleteInvalidOrIncompatibleImpactNeverBecomesCoverageOrAuthorization(String state) {
        var status = CatalogPublicationImpactVerifier.Status.valueOf(state);
        var impact = status == CatalogPublicationImpactVerifier.Status.VERIFIED_BLOCKED_ANALYSIS
                ? new CatalogPublicationImpactVerifier.Check(status, UUID.randomUUID(), 1, "a".repeat(64), AT, 0, 0, 7)
                : CatalogPublicationImpactVerifier.Check.unavailable(status);
        when(impacts.verify(any(), anyLong(), any(), any())).thenReturn(impact);
        var result = run();
        var blocker = switch (status) {
            case READ_BUDGET_EXCEEDED -> Blocker.IMPACT_REPORT_READ_BUDGET_EXCEEDED;
            case INVALID_RECEIPT -> Blocker.IMPACT_RECEIPT_INVALID;
            case INCOMPATIBLE_RULES -> Blocker.IMPACT_RULES_INCOMPATIBLE;
            case REPLAY_MISMATCH -> Blocker.IMPACT_REPLAY_MISMATCH;
            default -> Blocker.IMPACT_ANALYSIS_BLOCKED;
        };
        assertTrue(result.blockers().contains(blocker)); assertEquals(impact, result.impact()); assertBlocked(result);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "READ_BUDGET_EXCEEDED", "INVALID_RECEIPT", "INCOMPATIBLE_RULES", "REPLAY_MISMATCH", "VERIFIED_BLOCKED_ANALYSIS", "VERIFIED_INCOMPLETE_ANALYSIS"})
    void freshRegressionCannotReplaceMissingInvalidBlockedOrIncompleteStoredReceipts(String state) {
        var status = CatalogPublicationFactPathVerifier.Status.valueOf(state);
        var receipt = status == CatalogPublicationFactPathVerifier.Status.VERIFIED_BLOCKED_ANALYSIS
                || status == CatalogPublicationFactPathVerifier.Status.VERIFIED_INCOMPLETE_ANALYSIS
                ? new CatalogPublicationFactPathVerifier.Check(status, UUID.randomUUID(), 1, "b".repeat(64), AT, 0, 0, 0, 0, 0)
                : CatalogPublicationFactPathVerifier.Check.unavailable(status);
        when(storedRegressions.verify(any(), anyLong(), any(), any())).thenReturn(receipt);
        var result = run(); assertTrue(result.factPaths().changedFactPathsCovered());
        var blocker = switch (status) {
            case MISSING -> Blocker.FACT_PATH_RECEIPT_MISSING;
            case READ_BUDGET_EXCEEDED -> Blocker.FACT_PATH_REPORT_READ_BUDGET_EXCEEDED;
            case INVALID_RECEIPT -> Blocker.FACT_PATH_RECEIPT_INVALID;
            case INCOMPATIBLE_RULES -> Blocker.FACT_PATH_RULES_INCOMPATIBLE;
            case REPLAY_MISMATCH -> Blocker.FACT_PATH_REPLAY_MISMATCH;
            case VERIFIED_BLOCKED_ANALYSIS -> Blocker.STORED_FACT_PATH_ANALYSIS_BLOCKED;
            default -> Blocker.STORED_FACT_PATH_ANALYSIS_INCOMPLETE;
        };
        assertTrue(result.blockers().contains(blocker)); assertEquals(receipt, result.storedFactPaths()); assertBlocked(result);
    }

    @Test void storedRegressionOutagesPropagateWithoutReadingBootstrapReceiptsOrReusingFreshCounts() {
        when(storedRegressions.verify(any(), anyLong(), any(), any())).thenThrow(new DataAccessException("Unavailable"));
        assertThrows(DataAccessException.class, this::run); clearInvocations(storedRegressions);
        var result = preflight.bootstrap(request.candidate());
        assertEquals(CatalogPublicationFactPathVerifier.Status.NOT_CHECKED, result.storedFactPaths().status());
        verifyNoInteractions(storedRegressions); assertBlocked(result);
    }

    @Test void impactStorageFailuresPropagateAndBootstrapNeverReadsProposalReports() {
        when(impacts.verify(any(), anyLong(), any(), any())).thenThrow(new DataAccessException("Unavailable"));
        assertThrows(DataAccessException.class, this::run);
        clearInvocations(impacts);
        var result = preflight.bootstrap(request.candidate());
        assertTrue(result.blockers().contains(Blocker.BOOTSTRAP_IMPACT_WORKFLOW_UNAVAILABLE));
        assertEquals(CatalogPublicationImpactVerifier.Status.NOT_CHECKED, result.impact().status());
        assertEquals(CatalogFactPathRegressionService.Status.NOT_CHECKED, result.factPaths().status());
        verifyNoInteractions(impacts); assertBlocked(result);
    }

    @Test
    void baselineMustBeExplicitExactMatchingAndTipEvenThoughAuthorityRemainsUnavailable() {
        assertTrue(preflight.proposal(request.proposalId(), 0, row.sha256(), null).blockers().contains(Blocker.BASELINE_REFERENCE_MISSING));
        when(lookup.compareBaseline(eq(baseline), any())).thenReturn(new CatalogPublicationLookup.BaselineComparison(
                new CatalogPublicationLookup.Result(CatalogPublicationLookup.Status.UNAVAILABLE, CatalogPublicationLookup.Reason.NOT_FOUND, null, List.of()), false, false));
        var unavailable = run(); assertTrue(unavailable.blockers().contains(Blocker.BASELINE_INTEGRITY_UNAVAILABLE));
        assertFalse(unavailable.baselineContentMatches());
        when(lookup.compareBaseline(eq(baseline), any())).thenReturn(new CatalogPublicationLookup.BaselineComparison(comparison.lookup(), true, false));
        when(publications.successors(any())).thenReturn(1);
        var mismatched = run(); assertTrue(mismatched.blockers().containsAll(List.of(Blocker.BASELINE_CONTENT_MISMATCH, Blocker.BASELINE_NOT_TIP)));
        assertBlocked(mismatched);
    }

    @Test
    void emptyRegistryAndFreshBootstrapCannotReplaceADedicatedAuthenticatedReviewWorkflow() {
        var result = preflight.bootstrap(request.candidate());
        assertEquals(Mode.CURATED_BOOTSTRAP, result.mode()); assertEquals(9, result.facts().unobserved());
        assertNull(result.proposalId()); assertNull(result.proposalVersion()); assertNull(result.proposalSha256());
        assertTrue(result.blockers().contains(Blocker.BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE));
        assertFalse(result.blockers().contains(Blocker.BOOTSTRAP_REGISTRY_NOT_EMPTY)); assertBlocked(result);
        when(repository.registryEmpty()).thenReturn(false);
        assertTrue(preflight.bootstrap(request.candidate()).blockers().contains(Blocker.BOOTSTRAP_REGISTRY_NOT_EMPTY));
        verifyNoInteractions(reviews, lookup, publications);
    }

    @Test
    void exactStoredBootstrapReviewRemovesMissingObservationsButNeverGrantsApprovalOrPublication() {
        var candidate = request.candidate(); var id = UUID.randomUUID();
        var observations = validator.validate(candidate).facts().stream().map(f -> new CatalogBootstrapReviewRequest.Observation(
                f.optionId(), f.path(), Verdict.SOURCE_SUPPORTS_CLAIM)).toList();
        var reviewRequest = new CatalogBootstrapReviewRequest(1, id, CatalogDraftCanonicalizer.sha256(candidate), candidate,
                observations, CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
        var digest = CatalogDraftCanonicalizer.sha256(reviewRequest);
        var receipt = new CatalogBootstrapReview(id, reviewRequest.expectedCandidateSha256(), digest, candidate.catalogVersion(),
                9, new CatalogBootstrapReview.Counts(9, 0, 0), AT);
        when(bootstrapReviews.reviewed(id, digest)).thenReturn(new CatalogBootstrapReviewService.ReviewedCandidate(reviewRequest, receipt));
        var result = preflight.bootstrap(id, digest);
        assertTrue(result.facts().allFactsHaveSupportingObservation());
        assertFalse(result.blockers().contains(Blocker.BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE));
        assertFalse(result.blockers().contains(Blocker.FACT_OBSERVATIONS_MISSING)); assertBlocked(result);
        assertEquals(CatalogFactPathRegressionService.Status.NOT_CHECKED, result.factPaths().status());
        when(repository.registryEmpty()).thenReturn(false);
        assertTrue(preflight.bootstrap(id, digest).blockers().contains(Blocker.BOOTSTRAP_REGISTRY_NOT_EMPTY));
    }

    @Test
    void missingOrMismatchingBootstrapReviewCannotFallBackToUnreviewedDraftAndStorageOutagesPropagate() {
        var id = UUID.randomUUID(); var digest = "a".repeat(64);
        when(bootstrapReviews.reviewed(id, digest)).thenThrow(new CatalogBootstrapReviewException(CatalogBootstrapReviewException.Reason.NOT_FOUND));
        var result = preflight.bootstrap(id, digest);
        assertTrue(result.blockers().contains(Blocker.BOOTSTRAP_REVIEW_UNAVAILABLE)); assertEquals(0, result.facts().total()); assertBlocked(result);
        doThrow(new DataAccessException("Unavailable")).when(bootstrapReviews).reviewed(id, digest);
        assertThrows(DataAccessException.class, () -> preflight.bootstrap(id, digest));
    }

    @Test
    void noFactsAreInvalidRatherThanVacuousBootstrapSuccess() {
        var json = (ObjectNode) mapper.valueToTree(request.candidate());
        ((ObjectNode) json.at("/options/0")).set("facts", mapper.createObjectNode());
        ((ObjectNode) json.at("/options/0")).set("compatibility", mapper.valueToTree(new ProviderCatalogDraft.Compatibility(
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of())));
        ((ObjectNode) json.at("/options/0")).set("residency", mapper.createObjectNode());
        ((ObjectNode) json.at("/options/0")).set("authenticationControls", mapper.createObjectNode());
        var result = preflight.bootstrap(mapper.treeToValue(json, ProviderCatalogDraft.class));
        assertEquals(0, result.facts().total()); assertFalse(result.facts().allFactsHaveSupportingObservation());
        assertTrue(result.blockers().contains(Blocker.CANDIDATE_INVALID)); assertBlocked(result);
    }

    @Test
    void invalidBoundsCannotReachStorageAndStorageOutageCannotMasqueradeAsPolicySuccess() {
        assertThrows(IllegalArgumentException.class, () -> preflight.proposal(request.proposalId(), -1, row.sha256(), baseline));
        assertThrows(IllegalArgumentException.class, () -> preflight.proposal(request.proposalId(), 9007199254740992L, row.sha256(), baseline));
        assertThrows(IllegalArgumentException.class, () -> preflight.proposal(request.proposalId(), 0, "no-hash", baseline));
        verify(repository, never()).proposal(any(), anyLong());
        when(repository.proposal(any(), anyLong())).thenThrow(new DataAccessException("Unavailable"));
        assertThrows(DataAccessException.class, this::run);
    }

    private void store(CatalogChangePreviewRequest value) {
        request = value; String json = mapper.writeValueAsString(value); String digest = CatalogDraftCanonicalizer.sha256(value);
        row = new CatalogPublicationPreflightRepository.Proposal(value.proposalId(), 0, 0L, "PROPOSED", 1, digest,
                AT.minusSeconds(50), json, bytes(json), false, false);
        when(repository.proposal(any(), anyLong())).thenReturn(row);
        var facts = validator.validate(value.candidate()).facts(); var items = new ArrayList<CatalogFactReview>();
        for (var fact : facts) items.add(new CatalogFactReview(UUID.randomUUID(), value.proposalId(), 0, digest,
                items.size() + 1, fact.optionId(), fact.path(), Verdict.SOURCE_SUPPORTS_CLAIM, AT.minusSeconds(10),
                "HUMAN_SOURCE_REVIEW_OBSERVATION", false, false, false, false));
        supporting = List.copyOf(items); observe(supporting);
    }
    private void observe(List<CatalogFactReview> values) { when(reviews.latest(any(), anyLong(), anyString())).thenReturn(values); }
    private Result run() { return preflight.proposal(request.proposalId(), 0, row.sha256(), baseline); }
    private static void assertBlocked(Result result) {
        assertEquals("BLOCKED", result.status()); assertFalse(result.coverageComplete()); assertFalse(result.baselineVerified());
        assertFalse(result.sourceVerificationPerformed()); assertFalse(result.approvalGranted()); assertFalse(result.writesPerformed());
        assertFalse(result.publicationReady()); assertFalse(result.evaluationReady());
    }
}
