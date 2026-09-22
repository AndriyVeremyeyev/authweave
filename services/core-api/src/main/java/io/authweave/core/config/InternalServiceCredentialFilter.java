package io.authweave.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
final class InternalServiceCredentialFilter extends OncePerRequestFilter {

    private final String token;
    private final PersonalWorkspaceService workspaces;
    private static final Pattern WORKSPACE_PREFIX = Pattern.compile(
            "^/api/v[1-9][0-9]*/workspaces(?:/|$)");
    private static final Pattern WORKSPACE_PATH = Pattern.compile(
            "^/api/v[1-9][0-9]*/workspaces/([^/]+)(?:/.*)?$");

    InternalServiceCredentialFilter(@Value("${AUTHWEAVE_CORE_SERVICE_TOKEN:}") String token,
            PersonalWorkspaceService workspaces) {
        this.token = token;
        this.workspaces = workspaces;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !path.startsWith("/internal/") && !WORKSPACE_PREFIX.matcher(path).find();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (token.length() < 32) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        var headers = Collections.list(request.getHeaders("Authorization"));
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        if (headers.size() != 1 || !MessageDigest.isEqual(expected,
                headers.getFirst().getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
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
        chain.doFilter(request, response);
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
