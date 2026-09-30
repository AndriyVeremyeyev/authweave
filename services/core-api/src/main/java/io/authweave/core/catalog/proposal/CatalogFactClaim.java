package io.authweave.core.catalog.proposal;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.authweave.core.catalog.ProviderCatalog.Availability;
import io.authweave.core.catalog.ProviderCatalog.Support;
import io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;

/** Exact submitted values, separate from provenance and never a verified provider assertion. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CatalogFactClaim.Capability.class, name = "CAPABILITY"),
    @JsonSubTypes.Type(value = CatalogFactClaim.Compatibility.class, name = "COMPATIBILITY"),
    @JsonSubTypes.Type(value = CatalogFactClaim.Residency.class, name = "RESIDENCY"),
    @JsonSubTypes.Type(value = CatalogFactClaim.AuthenticationControl.class, name = "AUTHENTICATION_CONTROL")
})
public sealed interface CatalogFactClaim {
    record Capability(Availability availability) implements CatalogFactClaim { }
    record Compatibility(Support support) implements CatalogFactClaim { }
    record Residency(ResidencyCoverage coverage, List<String> storageCountries) implements CatalogFactClaim {
        public Residency { storageCountries = List.copyOf(storageCountries); }
    }
    record AuthenticationControl(Support availability, Support enforcement) implements CatalogFactClaim { }

    static CatalogFactClaim from(ProposedFact fact) {
        return switch (fact) {
            case CapabilityFact value -> new Capability(value.availability());
            case CompatibilityFact value -> new Compatibility(value.support());
            case ResidencyFact value -> new Residency(value.coverage(), value.storageCountries().stream().sorted().toList());
            case AuthenticationFact value -> new AuthenticationControl(value.availability(), value.enforcement());
            default -> throw new IllegalArgumentException("Unsupported proposed fact type");
        };
    }
}
