package io.authweave.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A local BFF credential boundary for all internal Core API routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class InternalServiceCredentialFilter extends OncePerRequestFilter {

    private final String token;

    InternalServiceCredentialFilter(@Value("${AUTHWEAVE_CORE_SERVICE_TOKEN:}") String token) {
        this.token = token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
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
        chain.doFilter(request, response);
    }
}
