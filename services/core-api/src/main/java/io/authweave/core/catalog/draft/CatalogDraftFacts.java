package io.authweave.core.catalog.draft;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;

/** Stable typed fact addresses shared by validation and comparison; omitted entries remain unknown. */
final class CatalogDraftFacts {
    private CatalogDraftFacts() { }

    static NavigableMap<String, ProposedFact> entries(Option option) {
        var entries = new TreeMap<String, ProposedFact>();
        option.facts().forEach((key, value) -> entries.put("facts." + key, value));
        var context = option.compatibility();
        context.applications().forEach((key, value) -> entries.put("compatibility.applications." + key, value));
        context.clients().forEach((key, value) -> entries.put("compatibility.clients." + key, value));
        context.populations().forEach((key, value) -> entries.put("compatibility.populations." + key, value));
        context.tenancy().forEach((key, value) -> entries.put("compatibility.tenancy." + key, value));
        context.membership().forEach((key, value) -> entries.put("compatibility.membership." + key, value));
        option.residency().forEach((key, value) -> entries.put("residency." + key, value));
        option.authenticationControls().forEach((client, populations) -> populations.forEach((population, controls) ->
                controls.forEach((control, value) -> entries.put("authenticationControls." + client + "." + population + "." + control, value))));
        return Collections.unmodifiableNavigableMap(entries);
    }

    static ProposedFact normalized(ProposedFact fact) {
        if (fact == null) return null;
        var conditions = fact.conditions().stream().sorted().toList();
        return switch (fact) {
            case CapabilityFact value -> new CapabilityFact(value.availability(), conditions, value.evidence());
            case CompatibilityFact value -> new CompatibilityFact(value.support(), conditions, value.evidence());
            case ResidencyFact value -> new ResidencyFact(value.coverage(), value.storageCountries().stream().sorted().toList(), conditions, value.evidence());
            case AuthenticationFact value -> new AuthenticationFact(value.availability(), value.enforcement(), conditions, value.evidence());
            default -> throw new IllegalArgumentException("Unsupported proposed fact type");
        };
    }
}
