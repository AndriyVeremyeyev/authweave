package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateAuditabilityInputTests.*;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure fictional claim eligibility, independent of source truth, full-rule coverage or publication permission. */
class PublishedProposalDecisionCoverageTests {
    private static final Instant AT = Instant.parse("2026-10-09T17:00:00Z");
    @Test void recordedBaseAndSupplementClaimsAreCountedWithoutInventingAbsentRequiredFacts() {
        var base = base(); var audit = input(base, supplement());
        var noAudit = PublishedProposalDecisionCoverageService.claims(new CandidateDecisionImpactEvaluator.Snapshot(base, supporting(base), null), AT);
        var complete = PublishedProposalDecisionCoverageService.claims(new CandidateDecisionImpactEvaluator.Snapshot(base, supporting(base), audit), AT);
        assertEquals(supporting(base).facts().size(), noAudit.recorded());
        assertEquals(noAudit.recorded() + audit.assertions().size(), complete.recorded());
        assertTrue(noAudit.allRecordedClaimsSupportedAndCurrent()); assertTrue(complete.allRecordedClaimsSupportedAndCurrent());
        var unreviewed = PublishedProposalDecisionCoverageService.claims(new CandidateDecisionImpactEvaluator.Snapshot(base,
                new SourceAssertions(DecisionCanonicalizer.sha256(base), List.of()), null), AT);
        assertEquals(noAudit.recorded(), unreviewed.unreviewed()); assertEquals(noAudit.recorded(), unreviewed.current());
        assertFalse(unreviewed.allRecordedClaimsSupportedAndCurrent());
    }
    @ParameterizedTest @ValueSource(strings = {"boundary", "stale", "now", "future"})
    void originalBaseAndSupplementDatesHaveInclusiveNinetyDayAndExactNanosecondBoundaries(String boundary) {
        var observed = switch (boundary) {
            case "boundary" -> AT.minusSeconds(90L * 86400); case "stale" -> AT.minusSeconds(90L * 86400).minusNanos(1);
            case "now" -> AT; default -> AT.plusNanos(1);
        };
        var base = base(); var supplement = supplement();
        ((ObjectNode) base.at("/options/0/facts/SCIM/evidence")).put("observedAt", observed.toString());
        ((ObjectNode) supplement.at("/options/0/facts/0/evidence")).put("observedAt", observed.toString());
        var snapshot = new CandidateDecisionImpactEvaluator.Snapshot(base, supporting(base), input(base, supplement));
        var claims = PublishedProposalDecisionCoverageService.claims(snapshot, AT);
        assertEquals(boundary.equals("stale") ? 2 : 0, claims.stale()); assertEquals(boundary.equals("future") ? 2 : 0, claims.future());
        assertEquals(claims.recorded() - claims.stale() - claims.future(), claims.current());
        assertEquals(!boundary.equals("stale") && !boundary.equals("future"), claims.allRecordedClaimsSupportedAndCurrent());
        assertEquals(observed.toString(), snapshot.catalog().at("/options/0/facts/SCIM/evidence/observedAt").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE"})
    void negativeBaseAndSupplementReviewsCannotBeErasedByCurrentDates(String name) {
        var base = base(); var assertions = supporting(base); var audit = input(base, supplement()); var verdict = Assertion.valueOf(name);
        var selected = assertions.facts().stream().map(a -> a.factPath().equals("facts.SCIM")
                ? new FactAssertion(a.optionId(), a.factPath(), a.claimSha256(), verdict) : a).toList();
        var selectedAudit = audit.assertions().stream().map(a -> new CandidateAuditabilityInput.FactAssertion(a.optionId(), a.criterion(), a.claimSha256(), verdict)).toList();
        var claims = PublishedProposalDecisionCoverageService.claims(new CandidateDecisionImpactEvaluator.Snapshot(base,
                new SourceAssertions(assertions.candidateSha256(), selected), new CandidateAuditabilityInput(audit.candidateSha256(), audit.supplement(), selectedAudit)), AT);
        assertEquals(1 + selectedAudit.size(), verdict == Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM ? claims.contradicted() : claims.insufficient());
        assertEquals(claims.recorded(), claims.current()); assertFalse(claims.allRecordedClaimsSupportedAndCurrent());
    }
    @Test void recordedClaimTotalsBoundsAndBothDirectionsOfEligibilityAreRequired() {
        for (var counts : List.of(new int[]{0,0,0,0,0,0,0,0}, new int[]{7401,7401,0,0,0,7401,0,0},
                new int[]{1,2,0,0,0,1,0,0}, new int[]{1,1,0,0,0,0,0,0}, new int[]{1,1,-1,0,1,1,0,0},
                new int[]{1,Integer.MAX_VALUE,Integer.MAX_VALUE,0,0,1,0,0})) {
            assertThrows(IllegalArgumentException.class, () -> new PublishedProposalDecisionCoverageService.CandidateClaims(
                    counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],counts[6],counts[7],false));
        }
        assertThrows(IllegalArgumentException.class, () -> new PublishedProposalDecisionCoverageService.CandidateClaims(1,1,0,0,0,1,0,0,false));
        assertThrows(IllegalArgumentException.class, () -> new PublishedProposalDecisionCoverageService.CandidateClaims(1,0,0,0,1,1,0,0,true));
    }
}
