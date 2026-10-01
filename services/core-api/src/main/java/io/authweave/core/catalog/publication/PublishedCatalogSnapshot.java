package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;

/** Reserved publication wire format. Parsing this self-declared manifest grants no trust or publication authority. */
public record PublishedCatalogSnapshot(int schemaVersion, Kind kind, UUID snapshotId,
        String canonicalizationVersion, Content catalog, String contentSha256, String snapshotSha256,
        @JsonProperty(required = true) Reference previousSnapshot, Publication publication, List<FactStatus> factEvidenceStatuses) {
    public PublishedCatalogSnapshot {
        if (schemaVersion != 1 || !CatalogDraftCanonicalizer.VERSION.equals(canonicalizationVersion)) {
            throw new IllegalArgumentException("Unsupported published snapshot format");
        }
        Objects.requireNonNull(kind); Objects.requireNonNull(snapshotId); Objects.requireNonNull(catalog);
        Objects.requireNonNull(publication); digest(contentSha256); digest(snapshotSha256);
        factEvidenceStatuses = List.copyOf(factEvidenceStatuses);
        if (factEvidenceStatuses.isEmpty() || factEvidenceStatuses.size() > 6800) {
            throw new IllegalArgumentException("Invalid evidence-status count");
        }
    }
    public enum Kind { PUBLISHED_PROVIDER_CATALOG_SNAPSHOT }
    public enum DeclaredEvidenceStatus { REVIEWED }
    /** Data shares draft fact shapes; declarations stay separate and cannot silently convert draft evidence. */
    public record Content(int schemaVersion, String catalogVersion, List<ProviderCatalogDraft.Option> options) {
        public Content {
            options = List.copyOf(options);
            new ProviderCatalogDraft(schemaVersion, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT, catalogVersion, options);
        }
        public ProviderCatalogDraft asDraft() {
            return new ProviderCatalogDraft(schemaVersion, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT, catalogVersion, options);
        }
    }
    /** Pin by immutable identity and manifest digest, never by a mutable label alone. */
    public record Reference(UUID snapshotId, String catalogVersion, String snapshotSha256) {
        public Reference {
            Objects.requireNonNull(snapshotId); identifier(catalogVersion); digest(snapshotSha256);
        }
    }
    /** A claimed decision ID is not an authenticated Core decision, signature or evidence of source verification. */
    public record Publication(UUID decisionId, Instant publishedAt) {
        public Publication { Objects.requireNonNull(decisionId); Objects.requireNonNull(publishedAt); }
    }
    public record FactStatus(String optionId, String factPath, DeclaredEvidenceStatus evidenceStatus) {
        public FactStatus {
            identifier(optionId); Objects.requireNonNull(evidenceStatus);
            if (factPath == null || factPath.length() > 200 || !factPath.matches(
                    "(facts\\.[A-Z0-9_]+|compatibility\\.(applications|clients|populations|tenancy|membership)\\.[A-Z0-9_]+|residency\\.[A-Z0-9_]+|authenticationControls\\.[A-Z0-9_]+\\.[A-Z0-9_]+\\.[A-Z0-9_]+)")) {
                throw new IllegalArgumentException("Invalid fact target");
            }
        }
    }
    /** The manifest digest omits only itself; identity, content digest, parent, decision and statuses are bound. */
    public String computedSnapshotSha256() {
        return CatalogDraftCanonicalizer.sha256(new DigestPayload(schemaVersion, kind, snapshotId,
                canonicalizationVersion, catalog, contentSha256, previousSnapshot, publication, factEvidenceStatuses));
    }
    public String computedContentSha256() { return CatalogDraftCanonicalizer.sha256(catalog); }
    private record DigestPayload(int schemaVersion, Kind kind, UUID snapshotId, String canonicalizationVersion,
            Content catalog, String contentSha256, Reference previousSnapshot, Publication publication,
            List<FactStatus> factEvidenceStatuses) { }
    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256 digest");
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid catalog identifier");
    }
}
