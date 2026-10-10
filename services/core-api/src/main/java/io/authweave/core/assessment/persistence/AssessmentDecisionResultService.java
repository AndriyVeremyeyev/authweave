package io.authweave.core.assessment.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.assessment.application.PersonalWorkspaceService;
import io.authweave.core.assessment.result.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.publication.*;
import static io.authweave.core.assessment.result.AssessmentDecisionResultException.Reason.*;

/** Stored advice, not a final architecture choice, assessment state transition or automatic catalog activation. */
@Service
public class AssessmentDecisionResultService {
    public static final String VERSION = "assessment-decision-result-1";
    public static final String PROJECTION_VERSION = "stored-profile-to-v6-unknown-projection-1";
    private final AssessmentDecisionResultRepository repository;
    private final AssessmentProfileJsonCodec profiles;
    private final PersonalWorkspaceService workspaces;
    private final TrustedPublishedCatalogService catalogs;
    private final ObjectMapper mapper;
    private final Policy policy;
    public AssessmentDecisionResultService(AssessmentDecisionResultRepository repository, AssessmentProfileJsonCodec profiles,
            PersonalWorkspaceService workspaces, TrustedPublishedCatalogService catalogs, ObjectMapper mapper, DecisionPublicationCoveragePolicy rules) {
        this.repository = repository; this.profiles = profiles; this.workspaces = workspaces; this.catalogs = catalogs; this.mapper = mapper;
        var components = new java.util.TreeMap<>(DecisionPublicationCoveragePolicy.COMPONENT_VERSIONS);
        components.put("bootstrapPublicationLoading", CatalogBootstrapPublicationReader.VERSION);
        components.put("proposalPublicationLoading", CatalogProposalPublicationReader.VERSION);
        policy = new Policy(6, CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, rules.decisionPolicySha256(),
                PROJECTION_VERSION, Map.copyOf(components));
    }
    public record Actor(String issuer, String subject) {
        public Actor {
            if (issuer == null || issuer.isBlank() || issuer.length() > 2048 || subject == null || subject.isBlank() || subject.length() > 256)
                throw new IllegalArgumentException("Use a singular authenticated owner");
        }
    }
    public record Policy(int evaluationProfileSchemaVersion, String profileSchemaSha256, String decisionPolicySha256,
            String profileProjectionVersion, Map<String, String> componentVersions) {
        public Policy { componentVersions = Map.copyOf(componentVersions); }
    }
    public record Catalog(PublishedCatalogSnapshot.Reference reference, String proofSha256, String publicationPolicyVersion,
            String coverageManifestSha256, String loaderVersion, String decisionInputsSha256,
            List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps) {
        public Catalog { verificationGaps = List.copyOf(verificationGaps); }
    }
    public record Body(String scope, int schemaVersion, String resultVersion, String canonicalization,
            UUID workspaceId, UUID assessmentId, long version, String requestSha256, Instant evaluatedAt,
            AssessmentDecisionResultRequest request, int profileSchemaVersion, String profileSha256, JsonNode profile,
            JsonNode evaluationProfile, String evaluationProfileSha256, Policy policy, String policySha256, Catalog catalog,
            CandidateDecisionEvaluator.Result decision, boolean historicalPublicationWorkflowVerified,
            boolean externalSourceVerificationPerformed, boolean configurationVerified, boolean complianceVerified, boolean decisionApproved) { }
    public record Receipt(AssessmentDecisionResultRequest.Reference reference, Instant recordedAt, JsonNode result, boolean historicalReplayVerified) {
        public Receipt { result = result.deepCopy(); }
        @Override public JsonNode result() { return result.deepCopy(); }
    }
    public record Saved(Receipt receipt, boolean created) { }
    private record BoundRequest(UUID workspaceId, UUID assessmentId, AssessmentDecisionResultRequest request) { }

    @Transactional
    public Saved save(UUID workspace, UUID assessment, AssessmentDecisionResultRequest request, Actor actor) {
        owner(workspace, actor); Objects.requireNonNull(assessment); Objects.requireNonNull(request);
        // Global key first, then profile row: retries see the committed winner without an absent-row race.
        repository.lockKey(request.resultId());
        if (repository.keyUsedElsewhere(request.resultId(), workspace, assessment)) fail(CONFLICT);
        var existing = repository.find(workspace, assessment, request.resultId()); var requestSha = hash(new BoundRequest(workspace, assessment, request));
        if (existing != null) {
            if (!existing.workspaceId().equals(workspace) || !existing.assessmentId().equals(assessment)
                    || !existing.requestSha256().equals(requestSha)) fail(CONFLICT);
            return new Saved(verify(existing, actor), false);
        }
        var head = repository.lockProfile(workspace, assessment);
        if (head.version() != request.expectedAssessmentVersion() || "ARCHIVED".equals(head.status())) fail(CONFLICT);
        var recorded = repository.revision(workspace, assessment, head.version());
        if (!recorded.equals(head)) fail(READ_UNAVAILABLE);
        var latest = repository.latest(workspace, assessment);
        if (!Objects.equals(latest, request.previousResult())) fail(CONFLICT);
        if (latest != null) verify(repository.find(workspace, assessment, latest.resultId()), actor);
        long version = latest == null ? 1 : Math.addExact(latest.version(), 1);
        if (version > AssessmentDecisionResultRequest.MAX_VERSION) fail(CONFLICT);
        var at = repository.now(); var body = compute(workspace, assessment, version, request, recorded, at);
        var json = mapper.writeValueAsString(body); repository.size(json);
        repository.insert(workspace, assessment, version, request, requestSha, hash(body), json, actor);
        return new Saved(verify(repository.find(workspace, assessment, request.resultId()), actor), true);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Receipt get(UUID workspace, UUID assessment, AssessmentDecisionResultRequest.Reference reference, Actor actor) {
        owner(workspace, actor); Objects.requireNonNull(assessment); Objects.requireNonNull(reference);
        var row = repository.find(workspace, assessment, reference.resultId());
        if (row == null || !row.workspaceId().equals(workspace) || !row.assessmentId().equals(assessment)) fail(NOT_FOUND);
        if (row.version() != reference.version() || !row.resultSha256().equals(reference.resultSha256())) fail(CONFLICT);
        return verify(row, actor);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssessmentDecisionResultViews.Page history(UUID workspace, UUID assessment,
            AssessmentDecisionResultRequest.Reference before, Actor actor) {
        owner(workspace, actor); Objects.requireNonNull(assessment);
        if (!repository.assessmentExists(workspace, assessment)) fail(NOT_FOUND);
        var rows = repository.index(workspace, assessment, before);
        boolean more = rows.size() > AssessmentDecisionResultViews.PAGE_SIZE;
        var items = more ? rows.subList(0, AssessmentDecisionResultViews.PAGE_SIZE) : rows;
        return new AssessmentDecisionResultViews.Page("OWNED_ASSESSMENT_RESULT_INDEX", workspace, assessment, items,
                more ? items.getLast().reference() : null, false);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssessmentDecisionResultViews.Summary summary(UUID workspace, UUID assessment,
            AssessmentDecisionResultRequest.Reference reference, Actor actor) {
        // The outer transaction supplies the same read-only snapshot to get(), replay and this bounded projection.
        return projectSummary(get(workspace, assessment, reference, actor));
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssessmentDecisionResultViews.Advice advice(UUID workspace, UUID assessment,
            AssessmentDecisionResultRequest.Reference reference, Actor actor) {
        var receipt = get(workspace, assessment, reference, actor); var decision = receipt.result().get("decision");
        var limitations = mapper.createArrayNode();
        for (var entry : decision.get("limitations")) {
            var view = mapper.createObjectNode();
            for (var key : List.of("profilePath", "reasonCode", "blocksDeploymentRecommendation", "explanation")) view.set(key, entry.get(key));
            limitations.add(view); // User-supplied declaredValue is deliberately not part of this view.
        }
        var view = new AssessmentDecisionResultViews.Advice("VERIFIED_ASSESSMENT_DECISION_ADVICE", projectSummary(receipt),
                decision.get("candidates"), decision.get("rankGroups"), decision.get("architecture"), limitations, decision.get("followUps"));
        if (mapper.writeValueAsString(view).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) fail(READ_UNAVAILABLE);
        return view;
    }
    private AssessmentDecisionResultViews.Summary projectSummary(Receipt receipt) {
        var body = receipt.result(); var request = body.get("request");
        var item = new AssessmentDecisionResultViews.Item(receipt.reference(), request.path("expectedAssessmentVersion").asLong(),
                mapper.treeToValue(request.get("catalog"), PublishedCatalogSnapshot.Reference.class),
                request.get("previousResult").isNull() ? null : mapper.treeToValue(request.get("previousResult"), AssessmentDecisionResultRequest.Reference.class),
                receipt.recordedAt());
        var candidates = new java.util.ArrayList<AssessmentDecisionResultViews.Candidate>();
        for (var entry : body.path("decision").path("candidates")) {
            var hard = entry.get("hardChecks"); var score = entry.get("score");
            candidates.add(new AssessmentDecisionResultViews.Candidate(hard.path("optionId").asText(), hard.path("product").asText(),
                    hard.path("plan").asText(), hard.path("region").asText(), hard.path("deployment").asText(), hard.path("hardVerdict").asText(),
                    score.isNull() ? null : new AssessmentDecisionResultViews.Bounds(score.path("lowerBound").asInt(),
                        score.path("upperBound").asInt(), score.path("unknownWeight").asInt())));
        }
        var shortlist = new java.util.ArrayList<String>(); body.path("decision").path("shortlist").forEach(id -> shortlist.add(id.asText()));
        return new AssessmentDecisionResultViews.Summary("VERIFIED_ASSESSMENT_DECISION_SUMMARY",
                UUID.fromString(body.path("workspaceId").asText()), UUID.fromString(body.path("assessmentId").asText()), item,
                Instant.parse(body.path("evaluatedAt").asText()), body.path("profileSchemaVersion").asInt(), body.path("profileSha256").asText(),
                body.path("policySha256").asText(), request.get("weights"), body.path("decision").path("status").asText(), shortlist,
                candidates, body.path("catalog").path("verificationGaps").size(), true, false, false, false, false);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssessmentDecisionResultViews.Sensitivity sensitivity(UUID workspace, UUID assessment,
            AssessmentDecisionSensitivityRequest request, Actor actor) {
        var receipt = get(workspace, assessment, request.reference(), actor); var body = receipt.result();
        try {
            var catalog = mapper.treeToValue(body.at("/request/catalog"), PublishedCatalogSnapshot.Reference.class);
            var source = catalogs.load(catalog).decisionInputs();
            if (!hash(source).equals(body.at("/catalog/decisionInputsSha256").asText())) fail(READ_UNAVAILABLE);
            var comparison = CandidatePreferenceScorer.compareWeights(body.get("evaluationProfile"), 6, source.catalog(),
                    source.assertions(), source.auditability(), body.at("/request/weights"), request.weights(),
                    Instant.parse(body.get("evaluatedAt").asText()));
            var before = AssessmentDecisionResultViews.Scoring.from(comparison.before());
            var original = mapper.createObjectNode();
            for (var key : List.of("weights", "status", "shortlist", "candidates", "rankGroups"))
                original.set(key, key.equals("weights") ? body.at("/request/weights") : body.get("decision").get(key));
            if (!mapper.readTree(mapper.writeValueAsString(before)).equals(original)) fail(READ_UNAVAILABLE);
            var view = new AssessmentDecisionResultViews.Sensitivity("VERIFIED_ASSESSMENT_WEIGHT_SENSITIVITY", projectSummary(receipt),
                    before, AssessmentDecisionResultViews.Scoring.from(comparison.after()), false);
            if (mapper.writeValueAsString(view).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) fail(READ_UNAVAILABLE);
            return view;
        } catch (CatalogPublishedLoadingException unavailable) { throw new AssessmentDecisionResultException(READ_UNAVAILABLE); }
        catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) { throw new AssessmentDecisionResultException(INVALID_INPUT); }
    }
    private Body compute(UUID workspace, UUID assessment, long version, AssessmentDecisionResultRequest request,
            AssessmentDecisionResultRepository.Profile profile, Instant at) {
        try {
            var input = catalogs.load(request.catalog()); var publication = input.publication();
            if (!publication.historicalPublicationWorkflowVerified() || publication.externalSourceVerificationPerformed()
                    || publication.snapshot().publication().publishedAt().isAfter(at.plusSeconds(30))) fail(READ_UNAVAILABLE);
            var stored = profiles.snapshot(profile.json(), profile.schemaVersion());
            var projected = profiles.evaluationSnapshot(profile.json(), profile.schemaVersion());
            var source = input.decisionInputs();
            var decision = CandidateDecisionEvaluator.evaluate(projected, 6, source.catalog(), source.assertions(), source.auditability(), request.weights(), at);
            var catalog = new Catalog(publication.reference(), publication.proofSha256(), publication.publicationPolicyVersion(),
                    publication.coverageManifestSha256(), publication.loaderVersion(), hash(source), publication.verificationGaps());
            return new Body("PINNED_ASSESSMENT_DECISION_ADVICE", 1, VERSION, DecisionCanonicalizer.VERSION, workspace, assessment, version,
                    hash(new BoundRequest(workspace, assessment, request)), at, request, profile.schemaVersion(), hash(stored), stored,
                    projected, hash(projected), policy, hash(policy), catalog, decision, true, false, false, false, false);
        } catch (CatalogPublishedLoadingException unavailable) { throw new AssessmentDecisionResultException(READ_UNAVAILABLE); }
        catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) { throw new AssessmentDecisionResultException(INVALID_INPUT); }
    }
    Receipt verify(AssessmentDecisionResultRepository.Row row, Actor actor) {
        if (row == null || row.body() == null || row.bytes() < 1 || row.bytes() > AssessmentDecisionResultRepository.MAX_BYTES) fail(READ_UNAVAILABLE);
        try {
            var tree = mapper.readTree(row.body());
            if (!VERSION.equals(tree.path("resultVersion").asText()) || !mapper.valueToTree(policy).equals(tree.get("policy"))) fail(UNSUPPORTED_POLICY);
            // Parse only the request. Derived kernel/domain output is never reconstructed from caller/stored results;
            // a fresh typed calculation must equal the entire original JSON (including every nested field).
            var request = mapper.treeToValue(tree.get("request"), AssessmentDecisionResultRequest.class);
            var at = Instant.parse(tree.path("evaluatedAt").asText()); var event = row.event();
            if (!row.id().equals(request.resultId()) || !row.workspaceId().toString().equals(tree.path("workspaceId").asText())
                    || !row.assessmentId().toString().equals(tree.path("assessmentId").asText())
                    || row.version() != tree.path("version").asLong() || row.assessmentVersion() != request.expectedAssessmentVersion()
                    || !row.requestSha256().equals(tree.path("requestSha256").asText()) || !row.requestSha256().equals(hash(new BoundRequest(row.workspaceId(), row.assessmentId(), request)))
                    || !row.resultSha256().equals(hash(tree)) || !Objects.equals(row.previous(), request.previousResult())
                    || !row.snapshotId().equals(request.catalog().snapshotId()) || !row.catalogVersion().equals(request.catalog().catalogVersion())
                    || !row.snapshotSha256().equals(request.catalog().snapshotSha256())
                    || at.isBefore(row.recordedAt().minusSeconds(300)) || at.isAfter(row.recordedAt().plusSeconds(30))
                    || event == null || event.id() == null || !row.id().equals(event.resultId()) || !row.workspaceId().equals(event.workspaceId())
                    || !row.assessmentId().equals(event.assessmentId()) || row.version() != event.version()
                    || !row.requestSha256().equals(event.requestSha256()) || !row.resultSha256().equals(event.resultSha256())
                    || !actor.issuer().equals(event.issuer()) || !actor.subject().equals(event.subject())
                    || !AssessmentDecisionResultRepository.action(row.version()).equals(event.action()) || !"SUCCEEDED".equals(event.outcome())
                    || event.correlationId() == null || event.occurredAt() == null
                    || row.recordedAt().isBefore(event.occurredAt().minusSeconds(30)) || row.recordedAt().isAfter(event.occurredAt().plusSeconds(30))) fail(READ_UNAVAILABLE);
            if (row.previous() == null ? row.version() != 1 : row.previous().version() != row.version() - 1) fail(READ_UNAVAILABLE);
            if (row.previous() != null) {
                var previous = repository.find(row.workspaceId(), row.assessmentId(), row.previous().resultId());
                if (previous == null || previous.body() == null || !previous.workspaceId().equals(row.workspaceId())
                        || !previous.assessmentId().equals(row.assessmentId()) || previous.version() != row.previous().version()
                        || !previous.resultSha256().equals(row.previous().resultSha256()) || !previous.resultSha256().equals(hash(mapper.readTree(previous.body())))) fail(READ_UNAVAILABLE);
            }
            var profile = repository.revision(row.workspaceId(), row.assessmentId(), row.assessmentVersion());
            // Normalize Jackson's in-memory long/int nodes through the persisted wire representation.
            var replay = mapper.readTree(mapper.writeValueAsString(compute(row.workspaceId(), row.assessmentId(), row.version(), request, profile, at)));
            if (!tree.equals(replay)) fail(READ_UNAVAILABLE);
            return new Receipt(new AssessmentDecisionResultRequest.Reference(row.id(), row.version(), row.resultSha256()), row.recordedAt(), tree, true);
        } catch (AssessmentDecisionResultException denied) {
            if (denied.reason() == INVALID_INPUT) throw new AssessmentDecisionResultException(READ_UNAVAILABLE);
            throw denied;
        } catch (org.springframework.dao.DataAccessException unavailable) { throw unavailable; }
        catch (org.jooq.exception.DataAccessException unavailable) { throw unavailable; }
        catch (RuntimeException invalid) { throw new AssessmentDecisionResultException(READ_UNAVAILABLE); }
    }
    private void owner(UUID workspace, Actor actor) {
        Objects.requireNonNull(workspace); Objects.requireNonNull(actor);
        if (!workspaces.owns(actor.issuer(), actor.subject(), workspace)) fail(FORBIDDEN);
    }
    private String hash(Object value) { return DecisionCanonicalizer.sha256(mapper.valueToTree(value)); }
    private static void fail(AssessmentDecisionResultException.Reason reason) { throw new AssessmentDecisionResultException(reason); }
}
