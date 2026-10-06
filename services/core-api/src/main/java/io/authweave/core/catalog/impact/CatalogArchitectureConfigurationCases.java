package io.authweave.core.catalog.impact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator;
import io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.ClientScope;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Outcome;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Status;
import static io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.SettingId.*;
import static io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.SettingValue.*;
import static io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.Reason;
import static io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.SettingId;
import static io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.SettingValue;

/** Explicit synthetic designs and context overlays. Never user defaults or observed registration. */
@Component
public final class CatalogArchitectureConfigurationCases {
    public static final String VERSION = "catalog-architecture-configuration-scenarios-1";
    public static final String BASE_SHA256 = "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1";
    public static final int COUNT = 252;
    public static final int CHECK_COUNT = 2004;
    public enum ContextVariant { BASE_PROFILE, UNKNOWN_CLIENTS, EXCLUDED_PATTERN_CLIENT }
    public enum DesignVariant { EMPTY, EXPLICIT_UNKNOWN, REFERENCE_DESIGN, MISMATCH_WITH_GAP, NATIVE_LOOPBACK }
    // Independent reference inputs: do not generate these from evaluator compatibleValues.
    public static final Map<PatternId, Map<SettingId, SettingValue>> REFERENCES = references();
    public record Definition(String scenarioId, ContextVariant contextVariant, DesignVariant designVariant,
            PatternId patternId, String sourceProfileSha256, ApplicationIdentityProfile profile, Map<SettingId, SettingValue> settings) {
        public Definition { settings = Map.copyOf(settings); }
        public String key() { return scenarioId + "/" + contextVariant + "/" + patternId + "/" + designVariant; }
    }
    public record Expected(ClientScope scope, Status status, List<ArchitectureConfigurationEvaluator.Check> checks) { }
    private final List<Definition> definitions;
    private final String sha256;
    public CatalogArchitectureConfigurationCases(CatalogScopedProfileCases base, ObjectMapper mapper) {
        if (!BASE_SHA256.equals(base.sha256()) || !base.sha256().equals(CatalogDraftCanonicalizer.sha256(base.definitions())))
            throw new IllegalStateException("Review frozen architecture configuration scenario binding");
        var rows = new ArrayList<Definition>();
        for (var source : base.definitions().stream().sorted(java.util.Comparator.comparing(CatalogScenarioCases.Definition::id)).toList()) {
            if (source.profileSchemaVersion() != 5) throw new IllegalStateException("Use the frozen v5 base, not a replacement profile");
            var profile = mapper.treeToValue(source.profile(), ApplicationIdentityProfile.class);
            for (var context : ContextVariant.values()) for (var pattern : PatternId.values()) {
                var clients = context == ContextVariant.BASE_PROFILE ? profile.application().clients() : context == ContextVariant.UNKNOWN_CLIENTS ? Set.<ClientType>of()
                    : profile.application().clients().stream().filter(c -> c != client(pattern)).collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (context == ContextVariant.EXCLUDED_PATTERN_CLIENT && clients.isEmpty()) throw new IllegalStateException("Excluded is not unknown scope");
                var overlay = new ApplicationIdentityProfile(new ApplicationTopology(profile.application().type(), clients),
                        profile.audience(), profile.protocols(), profile.provisioning(), profile.security(), profile.operations());
                for (var design : DesignVariant.values()) {
                    if (design == DesignVariant.NATIVE_LOOPBACK && pattern != PatternId.NATIVE_CODE_PKCE) continue;
                    var values = new EnumMap<SettingId, SettingValue>(SettingId.class);
                    if (design != DesignVariant.EMPTY) values.putAll(REFERENCES.get(pattern));
                    if (design == DesignVariant.EXPLICIT_UNKNOWN) values.replaceAll((id, value) -> UNKNOWN);
                    if (design == DesignVariant.MISMATCH_WITH_GAP) { values.put(OAUTH_FLOW, UNKNOWN); values.put(CLIENT_AUTHENTICATION, DISTRIBUTED_SHARED_SECRET); }
                    if (design == DesignVariant.NATIVE_LOOPBACK) values.put(REDIRECT_MATCHING, NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION);
                    rows.add(new Definition(source.id(), context, design, pattern, CatalogDraftCanonicalizer.sha256(source.profile()), overlay, values));
                }
            }
        }
        definitions = List.copyOf(rows);
        if (definitions.size() != COUNT || definitions.stream().map(Definition::key).distinct().count() != COUNT
                || definitions.stream().mapToInt(d -> REFERENCES.get(d.patternId()).size()).sum() != CHECK_COUNT)
            throw new IllegalStateException("Review complete architecture configuration matrix");
        sha256 = CatalogDraftCanonicalizer.sha256(List.of(VERSION, CatalogScopedProfileCases.VERSION, BASE_SHA256, REFERENCES, definitions));
    }
    public List<Definition> definitions() { return definitions; }
    public String sha256() { return sha256; }
    public static ClientType client(PatternId id) { return id == PatternId.NATIVE_CODE_PKCE ? ClientType.NATIVE_MOBILE : id == PatternId.M2M_CLIENT_CREDENTIALS ? ClientType.MACHINE_TO_MACHINE : ClientType.BROWSER; }
    /** Independent expected results from the explicit fixture inputs, not the production evaluator. */
    public static Expected expected(Definition d) {
        var clients = d.profile().application().clients();
        var scope = clients.isEmpty() ? ClientScope.UNKNOWN : clients.contains(client(d.patternId())) ? ClientScope.SELECTED : ClientScope.NOT_SELECTED;
        var checks = REFERENCES.get(d.patternId()).entrySet().stream().map(entry -> {
            var value = d.settings().getOrDefault(entry.getKey(), UNKNOWN);
            boolean compatible = value == entry.getValue() || d.patternId() == PatternId.NATIVE_CODE_PKCE && entry.getKey() == REDIRECT_MATCHING && value == NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION;
            var reason = scope == ClientScope.UNKNOWN ? Reason.CLIENT_SCOPE_UNKNOWN : scope == ClientScope.NOT_SELECTED ? Reason.PATTERN_NOT_APPLICABLE
                : value == UNKNOWN ? Reason.SETTING_UNKNOWN : compatible ? Reason.EXPECTED_SETTING_DECLARED : Reason.INCOMPATIBLE_SETTING_DECLARED;
            var outcome = scope == ClientScope.NOT_SELECTED ? Outcome.NOT_APPLICABLE : scope == ClientScope.UNKNOWN || value == UNKNOWN ? Outcome.UNKNOWN
                : compatible ? Outcome.CONDITIONALLY_SATISFIED : Outcome.CONDITIONALLY_NOT_SATISFIED;
            return new ArchitectureConfigurationEvaluator.Check(entry.getKey(), outcome, reason);
        }).toList();
        var status = scope == ClientScope.NOT_SELECTED ? Status.NOT_APPLICABLE : checks.stream().anyMatch(c -> c.outcome() == Outcome.CONDITIONALLY_NOT_SATISFIED)
                ? Status.CONDITIONALLY_DOES_NOT_MATCH : checks.stream().anyMatch(c -> c.outcome() == Outcome.UNKNOWN) ? Status.NEEDS_INFORMATION : Status.CONDITIONALLY_MATCHES;
        return new Expected(scope, status, checks);
    }
    private static Map<PatternId, Map<SettingId, SettingValue>> references() {
        var designs = new EnumMap<PatternId, Map<SettingId, SettingValue>>(PatternId.class);
        for (var pattern : PatternId.values()) {
            boolean server = pattern == PatternId.BFF_SESSION || pattern == PatternId.SERVER_SIDE_SESSION, workload = pattern == PatternId.M2M_CLIENT_CREDENTIALS;
            var m = new LinkedHashMap<SettingId, SettingValue>();
            m.put(OAUTH_FLOW, workload ? CLIENT_CREDENTIALS : AUTHORIZATION_CODE); m.put(OAUTH_CLIENT_TYPE, server || workload ? CONFIDENTIAL : PUBLIC);
            m.put(CLIENT_AUTHENTICATION, workload ? WORKLOAD_HELD_CREDENTIAL : server ? SERVER_HELD_CREDENTIAL : NONE);
            m.put(TOKEN_LOCATION, workload ? WORKLOAD : server ? APPLICATION_SERVER : pattern == PatternId.SPA_CODE_PKCE ? BROWSER : NATIVE_APP);
            if (!workload) { m.put(PKCE_METHOD, S256); m.put(REDIRECT_MATCHING, EXACT_REGISTERED); }
            if (server) { m.put(SESSION_COOKIE_SECURE, ENABLED); m.put(SESSION_COOKIE_HTTP_ONLY, ENABLED); m.put(SESSION_CSRF_DEFENSE, DEFENSE_PLANNED); }
            if (server || pattern == PatternId.SPA_CODE_PKCE) m.put(RESOURCE_ACCESS, pattern == PatternId.BFF_SESSION ? BFF_PROXY : server ? SESSION_BACKEND : DIRECT_BROWSER);
            if (pattern == PatternId.SPA_CODE_PKCE) m.put(BROWSER_TOKEN_ENDPOINT_ACCESS, REQUIRED_ORIGINS_PLANNED);
            if (pattern == PatternId.NATIVE_CODE_PKCE) m.put(NATIVE_USER_AGENT, EXTERNAL_BROWSER);
            if (workload) m.put(WORKLOAD_AUTHORIZATION, WORKLOAD_OWN_OR_PREARRANGED);
            designs.put(pattern, Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableMap(designs);
    }
}
