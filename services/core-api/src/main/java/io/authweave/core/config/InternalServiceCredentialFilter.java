package io.authweave.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.authweave.core.assessment.application.PersonalWorkspaceService;

/** A local BFF credential and personal-workspace boundary for Core API routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class InternalServiceCredentialFilter extends OncePerRequestFilter {

    private final String token;
    private final PersonalWorkspaceService workspaces;
    private final String curatorProjectId;
    private final String curatorOrganizationId;
    private static final Pattern WORKSPACE_PREFIX = Pattern.compile(
            "^/api/v[1-9][0-9]*/workspaces(?:/|$)");
    private static final Pattern WORKSPACE_PATH = Pattern.compile(
            "^/api/v[1-9][0-9]*/workspaces/([^/]+)(?:/.*)?$");
    private static final Pattern CURATOR_INTERNAL_PREFIX = Pattern.compile(
            "^/internal/v[1-9][0-9]*/catalog-curator(?:/|$)");
    private static final Pattern CURATOR_DECISION_PREFIX = Pattern.compile(
            "^/api/v[1-9][0-9]*/catalog-change-proposals/[^/]+/decisions(?:/|$)");
    private static final long CURATOR_REAUTH_SECONDS = 15 * 60;
    private static final long CLOCK_SKEW_SECONDS = 30;

    InternalServiceCredentialFilter(@Value("${AUTHWEAVE_CORE_SERVICE_TOKEN:}") String token,
            PersonalWorkspaceService workspaces,
            @Value("${AUTHWEAVE_OIDC_PROJECT_ID:}") String curatorProjectId,
            @Value("${AUTHWEAVE_OIDC_ORG_ID:}") String curatorOrganizationId) {
        this.token = token;
        this.workspaces = workspaces;
        this.curatorProjectId = curatorProjectId;
        this.curatorOrganizationId = curatorOrganizationId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !path.startsWith("/internal/") && !WORKSPACE_PREFIX.matcher(path).find()
                && !CURATOR_DECISION_PREFIX.matcher(path).find();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        int credentialStatus = credentialStatus(request);
        if (credentialStatus != HttpServletResponse.SC_OK) {
            response.sendError(credentialStatus);
            return;
        }
        String path = path(request);
        if (WORKSPACE_PREFIX.matcher(path).find()) {
            var match = WORKSPACE_PATH.matcher(path);
            UUID workspaceId;
            try {
                if (!match.matches()) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
                workspaceId = UUID.fromString(match.group(1));
            } catch (IllegalArgumentException invalidId) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            String issuer = singleHeader(request, "X-AuthWeave-Oidc-Issuer", 2048);
            String subject = singleHeader(request, "X-AuthWeave-Oidc-Subject", 256);
            if (issuer == null || subject == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            if (!workspaces.owns(issuer, subject, workspaceId)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        if (CURATOR_INTERNAL_PREFIX.matcher(path).find()
                || CURATOR_DECISION_PREFIX.matcher(path).find()) {
            int curatorStatus = curatorStatus(request);
            if (curatorStatus != HttpServletResponse.SC_NO_CONTENT) {
                response.sendError(curatorStatus);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    // Decision controllers must also call this check: URL encodings may differ between routing and filters.
    public int curatorStatus(HttpServletRequest request) {
        int credential = credentialStatus(request);
        if (credential != HttpServletResponse.SC_OK) return credential;
        if (!validScope(curatorProjectId) || !validScope(curatorOrganizationId)) {
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        }
        if (singleHeader(request, "X-AuthWeave-Oidc-Issuer", 2048) == null
                || singleHeader(request, "X-AuthWeave-Oidc-Subject", 256) == null) {
            return HttpServletResponse.SC_UNAUTHORIZED;
        }
        String role = singleHeader(request, "X-AuthWeave-Curator-Role", 64);
        String project = singleHeader(request, "X-AuthWeave-Curator-Project-Id", 40);
        String organization = singleHeader(request, "X-AuthWeave-Curator-Org-Id", 40);
        String authenticatedAt = singleHeader(request, "X-AuthWeave-Authenticated-At", 64);
        return "catalog_curator".equals(role) && curatorProjectId.equals(project)
                && curatorOrganizationId.equals(organization) && fresh(authenticatedAt)
                ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_FORBIDDEN;
    }

    private int credentialStatus(HttpServletRequest request) {
        if (token.length() < 32) return HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        var headers = Collections.list(request.getHeaders("Authorization"));
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return headers.size() == 1 && MessageDigest.isEqual(expected,
                headers.getFirst().getBytes(StandardCharsets.UTF_8))
                ? HttpServletResponse.SC_OK : HttpServletResponse.SC_UNAUTHORIZED;
    }

    private static boolean validScope(String value) {
        return value != null && value.matches("[0-9]{1,40}");
    }

    private static boolean fresh(String value) {
        if (value == null) return false;
        try {
            Duration age = Duration.between(Instant.parse(value), Instant.now());
            return age.compareTo(Duration.ofSeconds(-CLOCK_SKEW_SECONDS)) >= 0
                    && age.compareTo(Duration.ofSeconds(CURATOR_REAUTH_SECONDS)) <= 0;
        } catch (RuntimeException invalidTime) {
            return false;
        }
    }

    private static String singleHeader(HttpServletRequest request, String name, int maxLength) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst().isBlank()
                || values.getFirst().length() > maxLength) {
            return null;
        }
        return values.getFirst();
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
