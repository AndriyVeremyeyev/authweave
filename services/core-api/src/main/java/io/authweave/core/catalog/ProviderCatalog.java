package io.authweave.core.catalog;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory;

/** The first catalog is deliberately synthetic; real evidence requires a publication workflow. */
public record ProviderCatalog(int schemaVersion, String catalogVersion, Kind kind, List<Option> options) {

    public ProviderCatalog {
        if (schemaVersion != 4) throw new IllegalArgumentException("Unsupported catalog schema version");
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
    public enum ResidencyCoverage { COMPLETE, PARTIAL, UNKNOWN }
    public enum AuthenticationControl { PHISHING_RESISTANCE, NON_EXPORTABLE_KEYS, STEP_UP_AUTHENTICATION }

    public interface Evidence {
        EvidenceStatus evidenceStatus();
        URI sourceUrl();
        Instant observedAt();
    }

    public record Option(String id, String displayName, String plan, String region,
            Map<Capability, Fact> facts, Compatibility compatibility, Map<DataCategory, ResidencyFact> residency,
            Map<ClientType, Map<UserPopulation, Map<AuthenticationControl, AuthenticationControlFact>>> authenticationControls) {
        public Option {
            identifier(id);
            label(displayName);
            label(plan);
            label(region);
            facts = Map.copyOf(facts);
            Objects.requireNonNull(compatibility);
            residency = Map.copyOf(residency);
            var clients = new java.util.EnumMap<ClientType, Map<UserPopulation, Map<AuthenticationControl, AuthenticationControlFact>>>(ClientType.class);
            authenticationControls.forEach((client, populations) -> {
                if (client == ClientType.MACHINE_TO_MACHINE) {
                    throw new IllegalArgumentException("Human authentication controls cannot describe machine clients");
                }
                var scopes = new java.util.EnumMap<UserPopulation, Map<AuthenticationControl, AuthenticationControlFact>>(UserPopulation.class);
                populations.forEach((population, controls) -> scopes.put(population, Map.copyOf(controls)));
                clients.put(client, Map.copyOf(scopes));
            });
            authenticationControls = Map.copyOf(clients);
        }

        public Option(String id, String displayName, String plan, String region,
                Map<Capability, Fact> facts, Compatibility compatibility, Map<DataCategory, ResidencyFact> residency) {
            this(id, displayName, plan, region, facts, compatibility, residency, Map.of());
        }

        public Option(String id, String displayName, String plan, String region,
                Map<Capability, Fact> facts, Compatibility compatibility) {
            this(id, displayName, plan, region, facts, compatibility, Map.of());
        }
    }

    /** Enforcement means the scoped human flow can require the control, not merely offer it.
     * This is not proof of deployed configuration, enrollment/recovery security or an AAL. */
    public record AuthenticationControlFact(Support availability, Support enforcement,
            EvidenceStatus evidenceStatus, URI sourceUrl, Instant observedAt) implements Evidence {
        public AuthenticationControlFact {
            Objects.requireNonNull(availability);
            Objects.requireNonNull(enforcement);
            if (enforcement == Support.SUPPORTED && availability != Support.SUPPORTED) {
                throw new IllegalArgumentException("Enforcement support requires availability support");
            }
            validateEvidence(evidenceStatus, sourceUrl, observedAt);
        }
    }

    /** Confirmed storage destinations for this option, not a menu of configurable locations.
     * COMPLETE covers all destinations for the category, including replicas.
     * Recovery copies belong to BACKUPS, separately from primary profile/credential/log storage.
     * Empty countries mean UNKNOWN, never proof that the category is not stored. */
    public record ResidencyFact(ResidencyCoverage coverage, List<String> storageCountries,
            EvidenceStatus evidenceStatus, URI sourceUrl, Instant observedAt) implements Evidence {
        public ResidencyFact {
            Objects.requireNonNull(coverage);
            storageCountries = List.copyOf(storageCountries);
            var codes = Set.of(Locale.getISOCountries());
            if (storageCountries.size() > 249 || new HashSet<>(storageCountries).size() != storageCountries.size()
                    || !codes.containsAll(storageCountries)
                    || (coverage == ResidencyCoverage.UNKNOWN) != storageCountries.isEmpty()) {
                throw new IllegalArgumentException("Residency evidence requires unique country codes and consistent coverage");
            }
            storageCountries = storageCountries.stream().sorted().toList();
            validateEvidence(evidenceStatus, sourceUrl, observedAt);
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
