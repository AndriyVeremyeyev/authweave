package io.authweave.core.catalog.impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import static io.authweave.core.assessment.domain.profile.ApplicationTopology.*;
import static io.authweave.core.assessment.domain.profile.AudienceRequirements.*;
import static io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED;
import static io.authweave.core.catalog.draft.CatalogChangePreview.FactKind.*;

/** Every address allowed by draft v1. Fixed required-rule probes, not customer requirements or full profiles. */
public final class CatalogFactPathRegressionCases {
    public static final String VERSION = "catalog-fact-path-regression-1";
    public static final int FACT_PATH_COUNT = 68;
    public static final List<CatalogImpactCases.Probe> PROBES = probes();
    public static final String SHA256 = CatalogDraftCanonicalizer.sha256(PROBES);
    private CatalogFactPathRegressionCases() { }

    private static List<CatalogImpactCases.Probe> probes() {
        var result = new ArrayList<CatalogImpactCases.Probe>();
        for (var value : ProviderCatalog.Capability.values()) add(result, CAPABILITY, "facts." + value);
        for (var value : ApplicationType.values()) if (value != ApplicationType.UNKNOWN && value != ApplicationType.OTHER)
            add(result, COMPATIBILITY, "compatibility.applications." + value);
        for (var value : ClientType.values()) add(result, COMPATIBILITY, "compatibility.clients." + value);
        for (var value : UserPopulation.values()) add(result, COMPATIBILITY, "compatibility.populations." + value);
        for (var value : TenancyModel.values()) if (value != TenancyModel.UNKNOWN) add(result, COMPATIBILITY, "compatibility.tenancy." + value);
        for (var value : MembershipModel.values()) if (value != MembershipModel.UNKNOWN) add(result, COMPATIBILITY, "compatibility.membership." + value);
        for (var value : DataCategory.values()) add(result, RESIDENCY, "residency." + value);
        for (var client : ClientType.values()) if (client != ClientType.MACHINE_TO_MACHINE)
            for (var population : UserPopulation.values()) for (var control : ProviderCatalog.AuthenticationControl.values())
                add(result, AUTHENTICATION_CONTROL, "authenticationControls." + client + "." + population + "." + control);
        // Do not silently expand coverage when the draft vocabulary changes: review/version the suite first.
        if (result.size() != FACT_PATH_COUNT || result.stream().map(CatalogImpactCases.Probe::factPath).distinct().count() != FACT_PATH_COUNT)
            throw new IllegalStateException("Review and version the fact-path regression suite for draft vocabulary changes");
        return result.stream().sorted(Comparator.comparing(CatalogImpactCases.Probe::id)).toList();
    }
    private static void add(List<CatalogImpactCases.Probe> probes, io.authweave.core.catalog.draft.CatalogChangePreview.FactKind kind, String path) {
        probes.add(new CatalogImpactCases.Probe("required-" + path.toLowerCase(Locale.ROOT).replace('_', '-'),
                "Synthetic required-rule probe for " + path, kind, path, REQUIRED, kind == RESIDENCY ? List.of("DE") : List.of()));
    }
}
