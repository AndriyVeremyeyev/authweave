package io.authweave.core.catalog.draft;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.*;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.*;
import io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory;
import io.authweave.core.catalog.ProviderCatalog.*;

/** Proposed data only. This type cannot be loaded as the active, reviewed catalog. */
public record ProviderCatalogDraft(int schemaVersion, Kind kind, String catalogVersion, List<Option> options) {
    public ProviderCatalogDraft {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported draft schema version");
        Objects.requireNonNull(kind);
        identifier(catalogVersion);
        options = List.copyOf(options);
        if (options.isEmpty() || options.size() > 100) throw new IllegalArgumentException("Invalid option count");
    }

    public enum Kind { PROVIDER_CATALOG_DRAFT }
    public enum Deployment { MANAGED, SELF_HOSTED }

    public record Option(String id, String providerId, String product, String plan, Deployment deployment,
            String region, String configuration, Map<Capability, CapabilityFact> facts, Compatibility compatibility,
            Map<DataCategory, ResidencyFact> residency,
            Map<ClientType, Map<UserPopulation, Map<AuthenticationControl, AuthenticationFact>>> authenticationControls) {
        public Option {
            identifier(id); identifier(providerId);
            text(product, 120); text(plan, 120); text(region, 120); text(configuration, 120);
            Objects.requireNonNull(deployment);
            facts = Map.copyOf(facts);
            Objects.requireNonNull(compatibility);
            residency = Map.copyOf(residency);
            var clients = new java.util.EnumMap<ClientType, Map<UserPopulation, Map<AuthenticationControl, AuthenticationFact>>>(ClientType.class);
            authenticationControls.forEach((client, populations) -> {
                if (client == ClientType.MACHINE_TO_MACHINE) throw new IllegalArgumentException("Human controls cannot describe machine clients");
                var scopes = new java.util.EnumMap<UserPopulation, Map<AuthenticationControl, AuthenticationFact>>(UserPopulation.class);
                populations.forEach((population, controls) -> scopes.put(population, Map.copyOf(controls)));
                clients.put(client, Map.copyOf(scopes));
            });
            authenticationControls = Map.copyOf(clients);
        }
    }

    public record Compatibility(Map<ApplicationType, CompatibilityFact> applications,
            Map<ClientType, CompatibilityFact> clients, Map<UserPopulation, CompatibilityFact> populations,
            Map<TenancyModel, CompatibilityFact> tenancy, Map<MembershipModel, CompatibilityFact> membership) {
        public Compatibility {
            applications = Map.copyOf(applications); clients = Map.copyOf(clients);
            populations = Map.copyOf(populations); tenancy = Map.copyOf(tenancy); membership = Map.copyOf(membership);
            if (applications.containsKey(ApplicationType.UNKNOWN) || applications.containsKey(ApplicationType.OTHER)
                    || tenancy.containsKey(TenancyModel.UNKNOWN) || membership.containsKey(MembershipModel.UNKNOWN)) {
                throw new IllegalArgumentException("Unclassified context is not a catalog category");
            }
        }
    }

    public interface ProposedFact {
        List<String> conditions();
        Evidence evidence();
    }

    public record CapabilityFact(Availability availability, List<String> conditions, Evidence evidence) implements ProposedFact {
        public CapabilityFact {
            Objects.requireNonNull(availability); conditions = ProviderCatalogDraft.conditions(conditions); Objects.requireNonNull(evidence);
        }
    }

    public record CompatibilityFact(Support support, List<String> conditions, Evidence evidence) implements ProposedFact {
        public CompatibilityFact {
            Objects.requireNonNull(support); conditions = ProviderCatalogDraft.conditions(conditions); Objects.requireNonNull(evidence);
        }
    }

    public record ResidencyFact(ResidencyCoverage coverage, List<String> storageCountries,
            List<String> conditions, Evidence evidence) implements ProposedFact {
        public ResidencyFact {
            Objects.requireNonNull(coverage); conditions = ProviderCatalogDraft.conditions(conditions); Objects.requireNonNull(evidence);
            storageCountries = List.copyOf(storageCountries);
            if (storageCountries.size() > 249 || new HashSet<>(storageCountries).size() != storageCountries.size()
                    || storageCountries.stream().anyMatch(code -> !code.matches("[A-Z]{2}"))) {
                throw new IllegalArgumentException("Use distinct uppercase two-letter country codes");
            }
        }
    }

    public record AuthenticationFact(Support availability, Support enforcement, List<String> conditions,
            Evidence evidence) implements ProposedFact {
        public AuthenticationFact {
            Objects.requireNonNull(availability); Objects.requireNonNull(enforcement);
            conditions = ProviderCatalogDraft.conditions(conditions); Objects.requireNonNull(evidence);
        }
    }

    /** A bounded owner-supplied paraphrase, not a verified source capture or an instruction. */
    public record Evidence(URI sourceUrl, Instant observedAt, String summary) {
        public Evidence {
            Objects.requireNonNull(sourceUrl); Objects.requireNonNull(observedAt); text(summary, 1000);
            if (!"https".equals(sourceUrl.getScheme()) || sourceUrl.getHost() == null
                    || sourceUrl.getUserInfo() != null || sourceUrl.toString().length() > 2048
                    || !sourceUrl.toString().matches("https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:[/?#][^\\s]*)?")) {
                throw new IllegalArgumentException("Use an HTTPS source URL without embedded credentials");
            }
        }
    }

    private static List<String> conditions(List<String> values) {
        var copy = List.copyOf(values);
        if (copy.size() > 10 || new HashSet<>(copy).size() != copy.size()) throw new IllegalArgumentException("Invalid conditions");
        copy.forEach(value -> text(value, 500));
        return copy;
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid identifier");
    }

    private static void text(String value, int maximum) {
        // Match JSON Schema code-point lengths and its explicit whitespace-only policy.
        if (value == null || value.codePointCount(0, value.length()) > maximum
                || !value.matches("(?s).*[^\\x00-\\x20\\x{85}\\p{Z}\\x{FEFF}].*")) {
            throw new IllegalArgumentException("Text must be nonblank and bounded");
        }
    }
}
