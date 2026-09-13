package io.authweave.core.catalog;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;

/** The first catalog is deliberately synthetic; real evidence requires a publication workflow. */
public record ProviderCatalog(int schemaVersion, String catalogVersion, Kind kind, List<Option> options) {

    public ProviderCatalog {
        if (schemaVersion != 2) throw new IllegalArgumentException("Unsupported catalog schema version");
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
    public enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

    public interface Evidence {
        EvidenceStatus evidenceStatus();
        URI sourceUrl();
        Instant observedAt();
    }

    public record Option(String id, String displayName, String plan, String region,
            Map<Capability, Fact> facts, Compatibility compatibility) {
        public Option {
            identifier(id);
            label(displayName);
            label(plan);
            label(region);
            facts = Map.copyOf(facts);
            Objects.requireNonNull(compatibility);
        }
    }

    public record Compatibility(
            Map<ApplicationType, CompatibilityFact> applications,
            Map<ClientType, CompatibilityFact> clients,
            Map<UserPopulation, CompatibilityFact> populations,
            Map<TenancyModel, CompatibilityFact> tenancy,
            Map<MembershipModel, CompatibilityFact> membership) {
        public Compatibility {
            applications = Map.copyOf(applications);
            clients = Map.copyOf(clients);
            populations = Map.copyOf(populations);
            tenancy = Map.copyOf(tenancy);
            membership = Map.copyOf(membership);
            if (applications.containsKey(ApplicationType.UNKNOWN) || applications.containsKey(ApplicationType.OTHER)
                    || tenancy.containsKey(TenancyModel.UNKNOWN) || membership.containsKey(MembershipModel.UNKNOWN)) {
                throw new IllegalArgumentException("Unknown or unclassified context cannot be a supported catalog category");
            }
        }

        public static Compatibility empty() {
            return new Compatibility(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    public record CompatibilityFact(Support support, EvidenceStatus evidenceStatus, URI sourceUrl, Instant observedAt)
            implements Evidence {
        public CompatibilityFact {
            Objects.requireNonNull(support);
            validateEvidence(evidenceStatus, sourceUrl, observedAt);
        }
    }

    public record Fact(Availability availability, EvidenceStatus evidenceStatus, URI sourceUrl, Instant observedAt)
            implements Evidence {
        public Fact {
            Objects.requireNonNull(availability);
            validateEvidence(evidenceStatus, sourceUrl, observedAt);
        }
    }

    private static void validateEvidence(EvidenceStatus status, URI sourceUrl, Instant observedAt) {
        Objects.requireNonNull(status);
        Objects.requireNonNull(sourceUrl);
        Objects.requireNonNull(observedAt);
        if (!"https".equals(sourceUrl.getScheme()) || sourceUrl.getHost() == null
                || !sourceUrl.getHost().endsWith(".invalid") || sourceUrl.getUserInfo() != null
                || sourceUrl.toString().length() > 2048) {
            throw new IllegalArgumentException("Synthetic facts require an HTTPS .invalid source URL without credentials");
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
