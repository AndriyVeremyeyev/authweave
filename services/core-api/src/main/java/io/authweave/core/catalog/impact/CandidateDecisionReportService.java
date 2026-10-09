package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReview;
import io.authweave.core.catalog.publication.CatalogBootstrapReview;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.authweave.core.catalog.impact.CandidateDecisionReportException.Reason.*;

/** Internal historical replay; not an assessment result, controller or catalog publication input. */
@Service
public class CandidateDecisionReportService {
    public static final String VERSION = "decision-stored-impact-report-1";
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final CandidateDecisionReportRepository repository;
    private final StoredCandidateDecisionService loader;
    public CandidateDecisionReportService(CandidateDecisionReportRepository repository, StoredCandidateDecisionService loader) {
        this.repository = repository; this.loader = loader;
    }
    public record Request(int profileSchemaVersion, JsonNode profile, JsonNode weights,
            StoredCandidateDecisionService.Reference before, StoredCandidateDecisionService.Reference after) {
        public Request {
            if (profileSchemaVersion != 6) throw new IllegalArgumentException("Use profile schema v6");
            profile = Objects.requireNonNull(profile).deepCopy(); weights = Objects.requireNonNull(weights).deepCopy();
            Objects.requireNonNull(before); Objects.requireNonNull(after);
        }
        @Override public JsonNode profile() { return profile.deepCopy(); }
        @Override public JsonNode weights() { return weights.deepCopy(); }
    }
    public record Reviews(CatalogBootstrapReview base, CatalogAuditabilityReview auditability) { }
    public record Body(String scope, int reportSchemaVersion, String reportVersion, String canonicalization,
            String inputSha256, Instant evaluatedAt, Request request, Reviews beforeReviews, Reviews afterReviews,
            CandidateDecisionImpactEvaluator.Result impact, boolean storedReviewsVerified, boolean currentCuratorAuthorityVerified,
            boolean sourceVerificationPerformed, boolean configurationVerified, boolean complianceVerified,
            boolean approvalGranted, boolean publicationReady, boolean writesPerformed) { }
    public record Receipt(UUID reportId, String inputSha256, String reportSha256, Instant recordedAt, JsonNode report,
            boolean historicalReplayVerified) {
        public Receipt { report = report.deepCopy(); }
        @Override public JsonNode report() { return report.deepCopy(); }
    }
    public static String inputSha256(Request request) { return DecisionCanonicalizer.sha256(MAPPER.valueToTree(request)); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Receipt get(UUID id, String expectedReportSha256) {
        Objects.requireNonNull(id); digest(expectedReportSha256);
        var row = repository.find(id);
        if (row == null) throw new CandidateDecisionReportException(NOT_FOUND);
        if (!expectedReportSha256.equals(row.reportSha256())) throw new CandidateDecisionReportException(CONFLICT);
        return verify(row);
    }
    Body compute(Request request, Instant at) {
        var before = loader.load(request.before()); var after = loader.load(request.after());
        if (before.baseReview().recordedAt().isAfter(at.plusSeconds(30)) || after.baseReview().recordedAt().isAfter(at.plusSeconds(30))
                || before.auditabilityReview() != null && before.auditabilityReview().recordedAt().isAfter(at.plusSeconds(30))
                || after.auditabilityReview() != null && after.auditabilityReview().recordedAt().isAfter(at.plusSeconds(30)))
            throw new CandidateDecisionReportException(READ_UNAVAILABLE);
        var impact = CandidateDecisionImpactEvaluator.evaluate(request.profile(), request.profileSchemaVersion(), request.weights(),
                before.snapshot(), after.snapshot(), at);
        return new Body("STORED_REVIEW_CANDIDATE_DECISION_IMPACT", 1, VERSION, DecisionCanonicalizer.VERSION, inputSha256(request), at,
                request, new Reviews(before.baseReview(), before.auditabilityReview()), new Reviews(after.baseReview(), after.auditabilityReview()),
                impact, true, false, false, false, false, false, false, false);
    }
    Receipt verify(CandidateDecisionReportRepository.Row row) {
        if (row.body() == null || row.bytes() < 1 || row.bytes() > CandidateDecisionReportRepository.MAX_BYTES)
            throw new CandidateDecisionReportException(READ_UNAVAILABLE);
        var body = MAPPER.readTree(row.body());
        if (!VERSION.equals(body.path("reportVersion").asText()) || !DecisionCanonicalizer.VERSION.equals(body.path("canonicalization").asText())
                || !CandidateDecisionImpactEvaluator.VERSION.equals(body.at("/impact/impactVersion").asText())
                || !CandidateDecisionEvaluator.VERSION.equals(body.at("/impact/before/kernelVersion").asText())
                || !CandidateDecisionEvaluator.VERSION.equals(body.at("/impact/after/kernelVersion").asText()))
            throw new CandidateDecisionReportException(UNSUPPORTED_VERSION);
        try {
            var event = row.event(); var at = Instant.parse(body.path("evaluatedAt").asText());
            var request = MAPPER.treeToValue(body.get("request"), Request.class);
            if (!row.inputSha256().equals(inputSha256(request)) || !row.reportSha256().equals(DecisionCanonicalizer.sha256(body))
                    || !row.beforeReviewId().equals(request.before().reviewId()) || !row.afterReviewId().equals(request.after().reviewId())
                    || !Objects.equals(row.beforeAuditReviewId(), request.before().auditability() == null ? null : request.before().auditability().reviewId())
                    || !Objects.equals(row.afterAuditReviewId(), request.after().auditability() == null ? null : request.after().auditability().reviewId())
                    || at.isAfter(row.recordedAt().plusSeconds(30)) || at.isBefore(row.recordedAt().minusSeconds(300))
                    || event == null || event.id() == null || !row.id().equals(event.reportId())
                    || !row.inputSha256().equals(event.inputSha256()) || !row.reportSha256().equals(event.reportSha256())
                    || !"candidate-decision.recorded".equals(event.action()) || !"SERVICE".equals(event.actorType())
                    || !"core-api-local-catalog".equals(event.actorId()) || !"SUCCEEDED".equals(event.outcome()) || event.correlationId() == null
                    || event.occurredAt() == null || row.recordedAt().isBefore(event.occurredAt().minusSeconds(30))
                    || row.recordedAt().isAfter(event.occurredAt().plusSeconds(30))) throw new IllegalArgumentException("Unbound receipt");
            var replay = MAPPER.valueToTree(compute(request, at));
            if (!body.equals(replay)) throw new IllegalArgumentException("Historical decision replay differs");
            return new Receipt(row.id(), row.inputSha256(), row.reportSha256(), row.recordedAt(), body, true);
        } catch (CandidateDecisionReportException failure) { throw failure; }
        catch (org.springframework.dao.DataAccessException unavailable) { throw unavailable; }
        catch (RuntimeException invalid) { throw new CandidateDecisionReportException(READ_UNAVAILABLE); }
    }
    static void digest(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Use an exact SHA-256"); }
}
