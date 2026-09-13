package io.authweave.core.catalog.impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.CatalogChangePreview.FactKind;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import static io.authweave.core.assessment.domain.profile.ApplicationTopology.*;
import static io.authweave.core.assessment.domain.profile.AudienceRequirements.*;
import static io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory.*;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.*;
import static io.authweave.core.catalog.draft.CatalogChangePreview.FactKind.*;

/** Versioned, source-controlled rule probes, not complete application profiles or all golden scenarios. */
public final class CatalogImpactCases {
    public static final String VERSION = "catalog-impact-probes-1";
    public static final List<Probe> PROBES = probes();
    public static final String SHA256 = CatalogDraftCanonicalizer.sha256(PROBES);
    private CatalogImpactCases() { }

    public record Probe(String id, String description, FactKind factKind, String factPath,
            RequirementCriticality criticality, List<String> allowedCountries) {
        public Probe { allowedCountries = List.copyOf(allowedCountries); }
    }
    private static List<Probe> probes() {
        var result = new ArrayList<Probe>();
        for (var capability : ProviderCatalog.Capability.values()) result.add(capability("required", capability, REQUIRED));
        for (var criticality : List.of(PREFERRED, NOT_REQUIRED, FORBIDDEN, UNKNOWN)) {
            result.add(capability(slug(criticality.name()), ProviderCatalog.Capability.SCIM, criticality));
        }
        result.add(capability("public-sector-forbidden", ProviderCatalog.Capability.SOCIAL_LOGIN, FORBIDDEN));
        result.add(context("b2b-application", "applications", ApplicationType.B2B_SAAS));
        result.add(context("browser-client", "clients", ClientType.BROWSER));
        result.add(context("partner-population", "populations", UserPopulation.PARTNERS));
        result.add(context("organization-tenancy", "tenancy", TenancyModel.MULTI_TENANT_ORGANIZATIONS));
        result.add(context("multi-organization-membership", "membership", MembershipModel.MULTIPLE_ORGANIZATIONS_PER_USER));
        result.add(new Probe("profile-storage-de-nl", "Required profile storage limited to DE and NL", RESIDENCY,
                "residency." + USER_PROFILES, REQUIRED, List.of("DE", "NL")));
        result.add(new Probe("backup-storage-de", "Required backup storage limited to DE", RESIDENCY,
                "residency." + BACKUPS, REQUIRED, List.of("DE")));
        for (var control : ProviderCatalog.AuthenticationControl.values()) {
            result.add(new Probe("partner-browser-" + slug(control.name()), "Required partner browser " + control.name(), AUTHENTICATION_CONTROL,
                    "authenticationControls." + ClientType.BROWSER + "." + UserPopulation.PARTNERS + "." + control, REQUIRED, List.of()));
        }
        return result.stream().sorted(Comparator.comparing(Probe::id)).toList();
    }
    private static Probe capability(String prefix, ProviderCatalog.Capability capability, RequirementCriticality criticality) {
        return new Probe(prefix + "-" + slug(capability.name()), criticality + " " + capability + " capability rule",
                CAPABILITY, "facts." + capability, criticality, List.of());
    }
    private static Probe context(String id, String dimension, Enum<?> category) {
        return new Probe(id, "Required compatibility with " + category.name(), COMPATIBILITY,
                "compatibility." + dimension + "." + category.name(), REQUIRED, List.of());
    }
    private static String slug(String value) { return value.toLowerCase(Locale.ROOT).replace('_', '-'); }
}
