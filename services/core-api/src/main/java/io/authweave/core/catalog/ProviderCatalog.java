package io.authweave.core.catalog;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The first catalog is deliberately synthetic; real evidence requires a publication workflow. */
public record ProviderCatalog(int schemaVersion, String catalogVersion, Kind kind, List<Option> options) {

    public ProviderCatalog {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported catalog schema version");
        identifier(catalogVersion);
        Objects.requireNonNull(kind);
        options = List.copyOf(options);
        if (options.isEmpty() || options.size() > 100) throw new IllegalArgumentException("Invalid option count");
        var ids = new HashSet<String>();
        for (var option : options) {
            if (!ids.add(option.id())) throw new IllegalArgumentException("Duplicate catalog option ID");
        }
    }

    public enum Kind { SYNTHETIC }
    public enum Capability { OIDC, SAML, OAUTH2_APIS, SOCIAL_LOGIN, ENTERPRISE_SSO, SCIM, JIT, GROUP_SYNC, MFA }
    /** OPTIONAL means it can be enabled or disabled for this specific plan and region. */
    public enum Availability { OPTIONAL, MANDATORY, UNAVAILABLE, UNKNOWN }
    public enum EvidenceStatus { REVIEWED, UNREVIEWED }

    public record Option(String id, String displayName, String plan, String region, Map<Capability, Fact> facts) {
        public Option {
            identifier(id);
            label(displayName);
            label(plan);
            label(region);
            facts = Map.copyOf(facts);
        }
    }

    public record Fact(Availability availability, EvidenceStatus evidenceStatus, URI sourceUrl, Instant observedAt) {
        public Fact {
            Objects.requireNonNull(availability);
            Objects.requireNonNull(evidenceStatus);
            Objects.requireNonNull(sourceUrl);
            Objects.requireNonNull(observedAt);
            // Reserved fictional provenance. Never fetched, and never presented as actual vendor evidence.
            if (!"https".equals(sourceUrl.getScheme()) || sourceUrl.getHost() == null
                    || !sourceUrl.getHost().endsWith(".invalid") || sourceUrl.getUserInfo() != null
                    || sourceUrl.toString().length() > 2048) {
                throw new IllegalArgumentException("Synthetic facts require an HTTPS .invalid source URL without credentials");
            }
        }
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{0,99}")) {
            throw new IllegalArgumentException("Invalid catalog identifier");
        }
    }

    private static void label(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new IllegalArgumentException("Invalid catalog label");
        }
    }
}
