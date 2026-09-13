package io.authweave.core.evaluation;

import java.net.URI;
import java.util.List;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;

import static io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.Outcome.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.Reason.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.Status.*;

public final class ArchitecturePatternEvaluator {
    public static final String POLICY_VERSION = "architecture-pattern-preflight-1";
    private static final String CLIENT_PATH = "application.clients";
    private static final String TOKEN_PATH = "security.browserTokenExposureMinimization";
    public static final List<String> CHECKED_PATHS = List.of(CLIENT_PATH, TOKEN_PATH);
    public static final List<String> DEFERRED_PATHS = List.of(
            "application.type", "audience", "protocols", "provisioning", "security.multiFactorAuthentication",
            "security.auditability", "security.dataResidency", "security.assurance", "security.complianceTargets", "operations");

    // These definitions and references are part of the versioned policy, not provider facts.
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(PatternId.BFF_SESSION, "Backend for Frontend with server-side session", BROWSER,
                    TokenHandling.SERVER_SIDE,
                    List.of("OAuth tokens stay on the backend; the browser uses a session cookie."),
                    List.of("API requests pass through the BFF; sessions and CSRF defenses need maintenance.",
                            "Malicious browser code can still make requests through the user's session."),
                    List.of("A backend can handle OAuth and proxy the required APIs.",
                            "Secure HttpOnly cookies, CSRF defenses and session lifecycle controls are implemented."),
                    List.of(URI.create("https://www.ietf.org/ietf-ftp/rfc/rfc10017.html#section-6.1"))),
            new Definition(PatternId.SERVER_SIDE_SESSION, "Server-side application session", BROWSER,
                    TokenHandling.SERVER_SIDE,
                    List.of("The application backend handles login and keeps token state away from browser code."),
                    List.of("Session storage, expiry and CSRF defenses need maintenance."),
                    List.of("The browser can access application resources through a session-owning backend.",
                            "Direct browser access to separate APIs is assessed independently."),
                    List.of(URI.create("https://www.ietf.org/ietf-ftp/rfc/rfc10017.html#section-1"))),
            new Definition(PatternId.SPA_CODE_PKCE, "Browser SPA with Authorization Code and PKCE", BROWSER,
                    TokenHandling.BROWSER,
                    List.of("The browser can call APIs directly without a BFF proxy."),
                    List.of("Browser code handles OAuth tokens; PKCE does not remove token exposure."),
                    List.of("The authorization server supports public clients and PKCE; endpoints permit the required browser requests.",
                            "Token storage, lifetime, scopes and defenses against malicious scripts meet the accepted threat model."),
                    List.of(URI.create("https://www.ietf.org/ietf-ftp/rfc/rfc10017.html#section-6.3"))),
            new Definition(PatternId.NATIVE_CODE_PKCE, "Native app with Authorization Code and PKCE", NATIVE_MOBILE,
                    TokenHandling.NATIVE_APP,
                    List.of("An external user-agent handles sign-in; PKCE protects the authorization code exchange."),
                    List.of("The native application owns token storage and redirect handling."),
                    List.of("Use an external user-agent and registered redirect URIs, with PKCE support at the authorization server.",
                            "Platform token storage and API authorization are designed; an embedded shared secret cannot authenticate a public app."),
                    List.of(URI.create("https://www.rfc-editor.org/rfc/rfc8252.html#section-4"),
                            URI.create("https://www.rfc-editor.org/rfc/rfc8252.html#section-8.1"))),
            new Definition(PatternId.M2M_CLIENT_CREDENTIALS, "Workload with client credentials", MACHINE_TO_MACHINE,
                    TokenHandling.WORKLOAD,
                    List.of("A workload can obtain API access without an interactive user sign-in."),
                    List.of("Workload credentials, token scope and lifecycle require operational controls."),
                    List.of("The workload can authenticate as a confidential client.",
                            "Access is for the workload's own resources or prearranged authorization; user delegation requires separate analysis.",
                            "The target API and authorization server support the required grant and permissions."),
                    List.of(URI.create("https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4"))));

    private ArchitecturePatternEvaluator() { }

    public static List<Pattern> evaluate(ApplicationIdentityProfile profile) {
        return DEFINITIONS.stream().map(definition -> {
            var clients = profile.application().clients();
            boolean unknownClients = clients.isEmpty();
            boolean selected = clients.contains(definition.clientType());
            var clientCheck = unknownClients
                    ? new Check(CLIENT_PATH, UNKNOWN, CLIENT_CONTEXT_UNKNOWN, "Select client types to determine pattern applicability.")
                    : selected
                        ? new Check(CLIENT_PATH, PASS, CLIENT_SELECTED, "This pattern addresses one of the selected client types.")
                        : new Check(CLIENT_PATH, NOT_APPLIED, CLIENT_NOT_SELECTED, "This pattern's client type is not selected.");
            Check tokenCheck;
            if (unknownClients) {
                tokenCheck = new Check(TOKEN_PATH, UNKNOWN, CLIENT_CONTEXT_UNKNOWN,
                        "Client context is needed before applying the browser token criterion.");
            } else if (!selected) {
                tokenCheck = new Check(TOKEN_PATH, NOT_APPLIED, PATTERN_NOT_APPLICABLE,
                        "The token criterion is not applied to an unselected client pattern.");
            } else if (definition.clientType() != BROWSER) {
                tokenCheck = new Check(TOKEN_PATH, NOT_APPLIED, BROWSER_CRITERION_NOT_APPLICABLE,
                        "This criterion concerns browser application code, not native-app or workload token storage.");
            } else {
                tokenCheck = browserCheck(profile.security().browserTokenExposureMinimization(), definition.tokenHandling());
            }
            var status = unknownClients || tokenCheck.outcome() == UNKNOWN ? NEEDS_INFORMATION
                    : selected ? MATCHES_CHECKED_REQUIREMENTS : NOT_APPLICABLE;
            return new Pattern(definition.id(), definition.displayName(), definition.clientType(), definition.tokenHandling(),
                    status, List.of(clientCheck, tokenCheck), definition.advantages(), definition.tradeoffs(),
                    definition.prerequisites(), definition.references());
        }).toList();
    }

    private static Check browserCheck(RequirementCriticality requirement, TokenHandling handling) {
        return switch (requirement) {
            case REQUIRED -> handling == TokenHandling.SERVER_SIDE
                    ? new Check(TOKEN_PATH, PASS, TOKENS_HELD_SERVER_SIDE,
                            "This pattern keeps OAuth tokens in the backend when its prerequisites are met; session misuse still needs defenses.")
                    : new Check(TOKEN_PATH, UNKNOWN, ACCEPTABLE_EXPOSURE_UNDEFINED,
                            "Define acceptable browser token exposure and mitigations; mandatory minimization alone does not prohibit all browser tokens.");
            case PREFERRED -> new Check(TOKEN_PATH, NOT_APPLIED, PREFERENCE_NOT_SCORED,
                    "Compare token handling and tradeoffs; this preference does not eliminate a pattern and is not scored.");
            case NOT_REQUIRED -> new Check(TOKEN_PATH, NOT_APPLIED, NO_REQUIREMENT,
                    "No browser token minimization constraint is imposed.");
            case UNKNOWN -> new Check(TOKEN_PATH, UNKNOWN, REQUIREMENT_UNKNOWN,
                    "Clarify the browser token requirement before relying on this partial match.");
            case FORBIDDEN -> new Check(TOKEN_PATH, UNKNOWN, MINIMIZATION_PROHIBITION_UNDEFINED,
                    "Clarify the intended prohibition: forbidding minimization is not a ban on browser tokens or a requirement to expose them.");
        };
    }

    private record Definition(PatternId id, String displayName, ClientType clientType, TokenHandling tokenHandling,
            List<String> advantages, List<String> tradeoffs, List<String> prerequisites, List<URI> references) { }
}
