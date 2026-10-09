package io.authweave.core.catalog.draft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.catalog.ProviderCatalog;
import static org.junit.jupiter.api.Assertions.*;

/** Actual build-tool output must remain a valid, untrusted Core draft with the existing review digest. */
class DecisionCatalogCandidateAssemblyTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static JsonNode assembly;

    @BeforeAll
    static void assembleActualRepositorySelection() throws Exception {
        var root = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();
        var output = Files.createTempFile("authweave-decision-candidate-", ".json");
        var errors = Files.createTempFile("authweave-decision-candidate-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder("node", "packages/contracts/scripts/prepare-decision-candidate.mjs")
                    .directory(root.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Candidate assembly timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
            assembly = MAPPER.readTree(output.toFile());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output); Files.deleteIfExists(errors);
        }
    }

    @Test
    void actualPayloadParsesAndValidatesWithoutTrustPromotionOrActiveCatalogLoading() {
        var draft = MAPPER.treeToValue(assembly.get("candidate"), ProviderCatalogDraft.class);
        var validation = new CatalogDraftValidator(Clock.fixed(NOW, ZoneOffset.UTC)).validate(draft);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, validation.status());
        assertEquals(8, validation.optionCount());
        assertEquals(assembly.get("reviewTasks").size(), validation.factCount());
        assertTrue(validation.issues().isEmpty());
        assertFalse(validation.sourceVerificationPerformed()); assertFalse(validation.approvalGranted());
        assertFalse(validation.writesPerformed()); assertFalse(validation.evaluationReady());
        validation.facts().forEach(fact -> {
            assertEquals(CatalogDraftValidation.ReviewStatus.UNREVIEWED, fact.evidenceStatus());
            assertEquals(CatalogDraftValidation.Freshness.CURRENT, fact.freshness());
        });
        assertThrows(RuntimeException.class, () -> MAPPER.treeToValue(assembly.get("candidate"), ProviderCatalog.class));
    }

    @Test
    void nodeBootstrapDigestReplaysThroughTypedJavaCanonicalizationWithoutChangingItsVersion() {
        var draft = MAPPER.treeToValue(assembly.get("candidate"), ProviderCatalogDraft.class);
        assertEquals(CatalogDraftCanonicalizer.VERSION, assembly.at("/bindings/bootstrapCanonicalization").asText());
        assertEquals(CatalogDraftCanonicalizer.sha256(draft), assembly.at("/bindings/bootstrapCandidateSha256").asText());
        assertNotEquals(assembly.at("/bindings/decisionCatalogSha256").asText(), assembly.at("/bindings/bootstrapCandidateSha256").asText());
    }

    @Test
    void everyExistingCoreFactHasExactlyOnePendingTaskAndNotAnInventedHumanConfirmation() {
        var draft = MAPPER.treeToValue(assembly.get("candidate"), ProviderCatalogDraft.class);
        var expected = new HashSet<String>();
        draft.options().forEach(option -> CatalogDraftFacts.entries(option).keySet().forEach(path -> expected.add(option.id() + "/" + path)));
        var actual = new HashSet<String>();
        assembly.get("reviewTasks").forEach(task -> {
            assertTrue(task.get("verdict").isNull());
            assertTrue(actual.add(task.get("optionId").asText() + "/" + task.get("factPath").asText()));
        });
        assertEquals(expected, actual);
        assertFalse(assembly.get("approvalGranted").asBoolean());
        assertFalse(assembly.has("reviewId")); assertFalse(assembly.has("confirmation"));
    }

    @Test
    void assemblyDoesNotRenewObservationDatesAndCurrentEvidenceEventuallyBecomesStale() {
        var draft = MAPPER.treeToValue(assembly.get("candidate"), ProviderCatalogDraft.class);
        var validator = new CatalogDraftValidator(Clock.fixed(NOW.plusSeconds(100L * 86400), ZoneOffset.UTC));
        var validation = validator.validate(draft);
        validation.facts().forEach(fact -> assertEquals(CatalogDraftValidation.Freshness.STALE, fact.freshness()));
        assertEquals(assembly.at("/bindings/bootstrapCandidateSha256").asText(), validation.contentSha256());
        assertFalse(validation.evaluationReady());
    }
}
