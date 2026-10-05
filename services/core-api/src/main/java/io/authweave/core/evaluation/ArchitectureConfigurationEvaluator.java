package io.authweave.core.evaluation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import static io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.ClientScope;
import static io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Outcome;
import static io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Status;

/** A bounded proposed design, never an observation of an IdP or deployed application. */
public final class ArchitectureConfigurationEvaluator {
    public static final String POLICY_VERSION = "architecture-configuration-design-1";
    public enum SettingId {
        OAUTH_FLOW, OAUTH_CLIENT_TYPE, CLIENT_AUTHENTICATION, TOKEN_LOCATION, PKCE_METHOD, REDIRECT_MATCHING,
        SESSION_COOKIE_SECURE, SESSION_COOKIE_HTTP_ONLY, SESSION_CSRF_DEFENSE, RESOURCE_ACCESS,
        BROWSER_TOKEN_ENDPOINT_ACCESS, NATIVE_USER_AGENT, WORKLOAD_AUTHORIZATION
    }
    public enum SettingValue {
        UNKNOWN, AUTHORIZATION_CODE, CLIENT_CREDENTIALS, IMPLICIT, CONFIDENTIAL, PUBLIC,
        SERVER_HELD_CREDENTIAL, WORKLOAD_HELD_CREDENTIAL, NONE, DISTRIBUTED_SHARED_SECRET,
        APPLICATION_SERVER, BROWSER, NATIVE_APP, WORKLOAD, S256, PLAIN,
        EXACT_REGISTERED, NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION, WILDCARD,
        ENABLED, DISABLED, DEFENSE_PLANNED, ABSENT, BFF_PROXY, SESSION_BACKEND, DIRECT_BROWSER,
        REQUIRED_ORIGINS_PLANNED, BLOCKED, EXTERNAL_BROWSER, EMBEDDED_WEBVIEW,
        WORKLOAD_OWN_OR_PREARRANGED, USER_DELEGATION
    }
    public enum Reason { EXPECTED_SETTING_DECLARED, INCOMPATIBLE_SETTING_DECLARED, SETTING_UNKNOWN, CLIENT_SCOPE_UNKNOWN, PATTERN_NOT_APPLICABLE }
    public record Definition(SettingId settingId, String description, List<SettingValue> allowedValues,
            List<SettingValue> compatibleValues, List<URI> references) {
        public Definition {
            allowedValues = List.copyOf(allowedValues); compatibleValues = List.copyOf(compatibleValues); references = List.copyOf(references);
            if (!allowedValues.contains(SettingValue.UNKNOWN) || compatibleValues.isEmpty()
                    || compatibleValues.contains(SettingValue.UNKNOWN) || !allowedValues.containsAll(compatibleValues))
                throw new IllegalArgumentException("Invalid architecture setting definition");
        }
    }
    public record Check(SettingId settingId, Outcome outcome, Reason reasonCode) {
        public Check {
            Objects.requireNonNull(settingId); Objects.requireNonNull(reasonCode);
            if (outcome != ArchitectureConfigurationEvaluator.outcome(reasonCode)) throw new IllegalArgumentException("Inconsistent architecture setting outcome");
        }
    }
    public record Analysis(PatternId patternId, ClientScope clientScope, Map<SettingId, SettingValue> settings, List<Check> checks) {
        public Analysis {
            Objects.requireNonNull(patternId); Objects.requireNonNull(clientScope);
            settings = Map.copyOf(settings); checks = List.copyOf(checks);
            validate(patternId, settings);
            if (!checks.equals(ArchitectureConfigurationEvaluator.checks(patternId, clientScope, settings)))
                throw new IllegalArgumentException("Incomplete or unbound architecture configuration analysis");
        }
        @JsonProperty public Status status() {
            if (clientScope == ClientScope.NOT_SELECTED) return Status.NOT_APPLICABLE;
            if (checks.stream().anyMatch(c -> c.outcome() == Outcome.CONDITIONALLY_NOT_SATISFIED)) return Status.CONDITIONALLY_DOES_NOT_MATCH;
            if (checks.stream().anyMatch(c -> c.outcome() == Outcome.UNKNOWN)) return Status.NEEDS_INFORMATION;
            return Status.CONDITIONALLY_MATCHES;
        }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String analysisBasis() { return "UNVERIFIED_PROPOSED_CONFIGURATION"; }
        @JsonProperty public boolean configurationObserved() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean providerCompatibilityVerified() { return false; }
        @JsonProperty public boolean runtimeFlowVerified() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
    }
    private static final URI SECURITY = URI.create("https://www.rfc-editor.org/rfc/rfc9700.html#section-2.1");
    private static final URI BROWSER_APPS = URI.create("https://www.rfc-editor.org/rfc/rfc10017.html#section-6.1");
    private static final URI NATIVE = URI.create("https://www.rfc-editor.org/rfc/rfc8252.html#section-8");
    private static final URI WORKLOAD = URI.create("https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4");
    public static final List<String> DEFERRED_BOUNDARIES = List.of(
            "Observed client registration and actual issuer/redirect values",
            "Runtime protocol validation, token storage and session defenses",
            "API authorization, scopes, audience and grant permissions",
            "Provider interoperability, browser CORS and native redirect ownership",
            "Provisioning, offboarding, assurance, compliance and operations",
            "Independent assessment of additional resource-access paths");
    private ArchitectureConfigurationEvaluator() { }

    public static Analysis evaluate(PatternId pattern, ClientScope scope, Map<SettingId, SettingValue> settings) {
        Objects.requireNonNull(pattern); Objects.requireNonNull(scope);
        var supplied = Map.copyOf(settings);
        validate(pattern, supplied);
        return new Analysis(pattern, scope, supplied, checks(pattern, scope, supplied));
    }
    private static void validate(PatternId pattern, Map<SettingId, SettingValue> settings) {
        var definitions = definitions(pattern);
        for (var entry : settings.entrySet()) {
            var definition = definitions.stream().filter(d -> d.settingId() == entry.getKey()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Setting belongs to another pattern"));
            if (!definition.allowedValues().contains(entry.getValue())) throw new IllegalArgumentException("Value belongs to another setting");
        }
    }
    private static List<Check> checks(PatternId pattern, ClientScope scope, Map<SettingId, SettingValue> settings) {
        return definitions(pattern).stream().map(d -> {
            var value = settings.getOrDefault(d.settingId(), SettingValue.UNKNOWN);
            var reason = scope == ClientScope.NOT_SELECTED ? Reason.PATTERN_NOT_APPLICABLE
                    : scope == ClientScope.UNKNOWN ? Reason.CLIENT_SCOPE_UNKNOWN
                    : value == SettingValue.UNKNOWN ? Reason.SETTING_UNKNOWN
                    : d.compatibleValues().contains(value) ? Reason.EXPECTED_SETTING_DECLARED : Reason.INCOMPATIBLE_SETTING_DECLARED;
            return new Check(d.settingId(), outcome(reason), reason);
        }).toList();
    }
    private static Outcome outcome(Reason reason) {
        return switch (reason) {
            case EXPECTED_SETTING_DECLARED -> Outcome.CONDITIONALLY_SATISFIED;
            case INCOMPATIBLE_SETTING_DECLARED -> Outcome.CONDITIONALLY_NOT_SATISFIED;
            case SETTING_UNKNOWN, CLIENT_SCOPE_UNKNOWN -> Outcome.UNKNOWN;
            case PATTERN_NOT_APPLICABLE -> Outcome.NOT_APPLICABLE;
        };
    }
    public static List<Definition> definitions(PatternId pattern) {
        Objects.requireNonNull(pattern);
        boolean workload = pattern == PatternId.M2M_CLIENT_CREDENTIALS;
        boolean server = pattern == PatternId.BFF_SESSION || pattern == PatternId.SERVER_SIDE_SESSION;
        var reference = workload ? WORKLOAD : pattern == PatternId.NATIVE_CODE_PKCE ? NATIVE : SECURITY;
        var result = new ArrayList<Definition>();
        result.add(rule(SettingId.OAUTH_FLOW, "Grant of the primary reference flow; not proof of runtime protocol validation.", reference,
                workload ? SettingValue.CLIENT_CREDENTIALS : SettingValue.AUTHORIZATION_CODE,
                SettingValue.AUTHORIZATION_CODE, SettingValue.CLIENT_CREDENTIALS, SettingValue.IMPLICIT));
        result.add(rule(SettingId.OAUTH_CLIENT_TYPE, "Whether this client can protect its authentication credentials.", reference,
                server || workload ? SettingValue.CONFIDENTIAL : SettingValue.PUBLIC, SettingValue.CONFIDENTIAL, SettingValue.PUBLIC));
        result.add(rule(SettingId.CLIENT_AUTHENTICATION, "Credential custody category only; never submit a secret. A distributed shared secret does not make a public client confidential.", reference,
                workload ? SettingValue.WORKLOAD_HELD_CREDENTIAL : server ? SettingValue.SERVER_HELD_CREDENTIAL : SettingValue.NONE,
                SettingValue.SERVER_HELD_CREDENTIAL, SettingValue.WORKLOAD_HELD_CREDENTIAL, SettingValue.NONE, SettingValue.DISTRIBUTED_SHARED_SECRET));
        result.add(rule(SettingId.TOKEN_LOCATION, "Custody of OAuth tokens in the primary reference design; storage security remains unverified.", reference,
                workload ? SettingValue.WORKLOAD : server ? SettingValue.APPLICATION_SERVER : pattern == PatternId.SPA_CODE_PKCE ? SettingValue.BROWSER : SettingValue.NATIVE_APP,
                SettingValue.APPLICATION_SERVER, SettingValue.BROWSER, SettingValue.NATIVE_APP, SettingValue.WORKLOAD));
        if (!workload) {
            result.add(rule(SettingId.PKCE_METHOD, "AuthWeave conservatively requires S256 in all human reference designs. This project policy is not a universal normative MUST for every confidential OIDC client.", SECURITY,
                    SettingValue.S256, SettingValue.S256, SettingValue.PLAIN, SettingValue.NONE));
            var redirect = rule(SettingId.REDIRECT_MATCHING, "Exact registered redirect matching; the port exception is limited to native loopback IP-literal redirects, not wildcard hosts or paths. Actual URI registration is deferred.", SECURITY,
                    SettingValue.EXACT_REGISTERED, SettingValue.EXACT_REGISTERED, SettingValue.NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION, SettingValue.WILDCARD);
            if (pattern == PatternId.NATIVE_CODE_PKCE) redirect = new Definition(redirect.settingId(), redirect.description(), redirect.allowedValues(),
                    List.of(SettingValue.EXACT_REGISTERED, SettingValue.NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION), List.of(SECURITY, NATIVE));
            result.add(redirect);
        }
        if (server) {
            result.add(rule(SettingId.SESSION_COOKIE_SECURE, "Secure cookie planned for the application session; no cookie was inspected.", BROWSER_APPS,
                    SettingValue.ENABLED, SettingValue.ENABLED, SettingValue.DISABLED));
            result.add(rule(SettingId.SESSION_COOKIE_HTTP_ONLY, "HttpOnly cookie planned for the application session; no cookie was inspected.", BROWSER_APPS,
                    SettingValue.ENABLED, SettingValue.ENABLED, SettingValue.DISABLED));
            result.add(rule(SettingId.SESSION_CSRF_DEFENSE, "A session CSRF defense is planned; effectiveness, SameSite policy and other defenses remain deferred.", BROWSER_APPS,
                    SettingValue.DEFENSE_PLANNED, SettingValue.DEFENSE_PLANNED, SettingValue.ABSENT));
        }
        if (server || pattern == PatternId.SPA_CODE_PKCE) result.add(rule(SettingId.RESOURCE_ACCESS,
                "Primary resource path of this reference pattern only; additional direct browser APIs require independent assessment.", BROWSER_APPS,
                pattern == PatternId.BFF_SESSION ? SettingValue.BFF_PROXY : server ? SettingValue.SESSION_BACKEND : SettingValue.DIRECT_BROWSER,
                SettingValue.BFF_PROXY, SettingValue.SESSION_BACKEND, SettingValue.DIRECT_BROWSER));
        if (pattern == PatternId.SPA_CODE_PKCE) result.add(rule(SettingId.BROWSER_TOKEN_ENDPOINT_ACCESS,
                "Required browser origins are planned at the token endpoint; actual CORS and interoperability are unverified.", URI.create("https://www.rfc-editor.org/rfc/rfc10017.html#section-6.3"),
                SettingValue.REQUIRED_ORIGINS_PLANNED, SettingValue.REQUIRED_ORIGINS_PLANNED, SettingValue.BLOCKED));
        if (pattern == PatternId.NATIVE_CODE_PKCE) result.add(rule(SettingId.NATIVE_USER_AGENT,
                "Authorization uses an external browser rather than an embedded webview.", NATIVE,
                SettingValue.EXTERNAL_BROWSER, SettingValue.EXTERNAL_BROWSER, SettingValue.EMBEDDED_WEBVIEW));
        if (workload) result.add(rule(SettingId.WORKLOAD_AUTHORIZATION,
                "Client credentials cover the workload's own or prearranged resources, not user-delegated authorization.", WORKLOAD,
                SettingValue.WORKLOAD_OWN_OR_PREARRANGED, SettingValue.WORKLOAD_OWN_OR_PREARRANGED, SettingValue.USER_DELEGATION));
        return List.copyOf(result);
    }
    private static Definition rule(SettingId id, String description, URI reference, SettingValue compatible, SettingValue... allowed) {
        var values = new ArrayList<>(List.of(allowed)); values.add(SettingValue.UNKNOWN);
        return new Definition(id, description, values, List.of(compatible), List.of(reference));
    }
}
