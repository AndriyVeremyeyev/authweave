package io.authweave.core.catalog.proposal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;

/** Reads an explicitly selected local file; never opens an HTTP listener or fetches evidence URLs. */
@Component
@Profile("local-catalog-write")
public final class LocalCatalogProposalCommand {
    static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final LocalCatalogProposalWriter writer;
    public LocalCatalogProposalCommand(ObjectMapper mapper, LocalCatalogProposalWriter writer) {
        this.mapper = mapper; this.writer = writer;
    }
    public LocalCatalogProposalWriter.SaveResult store(Map<String, String> environment) {
        var options = options(environment);
        if (!Files.isRegularFile(options.path())) throw new IllegalArgumentException("Select a readable regular JSON file.");
        CatalogChangePreviewRequest request;
        try (var input = Files.newInputStream(options.path())) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Proposal file exceeds the 32 MiB limit.");
            request = mapper.readValue(bytes, CatalogChangePreviewRequest.class);
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            // Do not echo raw JSON, source text, credentials or private paths from parser diagnostics.
            throw new IllegalArgumentException("Cannot read a valid proposal JSON file; check its path and the preview request contract.");
        }
        return writer.save(request, options.expectedVersion());
    }
    static Options options(Map<String, String> environment) {
        String file = environment.get("AUTHWEAVE_CATALOG_PROPOSAL_FILE");
        if (file == null || file.isBlank()) throw new IllegalArgumentException("Set AUTHWEAVE_CATALOG_PROPOSAL_FILE to an absolute local JSON path.");
        Path path;
        try { path = Path.of(file); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Use a valid absolute local JSON path."); }
        if (!path.isAbsolute()) throw new IllegalArgumentException("Use an absolute local JSON path.");
        String value = environment.get("AUTHWEAVE_CATALOG_EXPECTED_VERSION");
        Long version = null;
        if (value != null) {
            if (!value.matches("0|[1-9][0-9]{0,15}")) throw new IllegalArgumentException("Expected version must be a non-negative safe integer.");
            version = Long.parseLong(value);
            if (version > 9007199254740991L) throw new IllegalArgumentException("Expected version must be a non-negative safe integer.");
        }
        return new Options(path, version);
    }
    record Options(Path path, Long expectedVersion) { }
}
