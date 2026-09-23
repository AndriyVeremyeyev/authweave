package io.authweave.core.config;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalServiceCredentialFilterTests {

    private static final String TOKEN = "synthetic-internal-token-000000000000000000000";
    private static final String PROJECT = "123456789012345678";
    private static final String ORGANIZATION = "987654321098765432";

    @Test
    void refusesProvisioningWhenNoServiceCredentialIsConfigured() throws Exception {
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter("", null, "", "")
                .doFilter(request, response, new MockFilterChain());
        assertEquals(503, response.getStatus());
    }

    @Test
    void refusesDuplicateAuthorizationHeaders() throws Exception {
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter(TOKEN, null, "", "")
                .doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void refusesWorkspaceRoutesWhenNoServiceCredentialIsConfigured() throws Exception {
        var request = new MockHttpServletRequest("GET",
                "/api/v6/workspaces/60000000-0000-4000-8000-000000000001/assessments");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter("", null, "", "")
                .doFilter(request, response, new MockFilterChain());
        assertEquals(503, response.getStatus());
    }

    @Test
    void curatorProbeRequiresConfiguredScopeAndVerifiedIdentity() throws Exception {
        var request = curatorRequest("/internal/v1/catalog-curator/authorization", Instant.now());
        assertEquals(503, status(request, PROJECT, ""));
        assertEquals(200, status(request, PROJECT, ORGANIZATION));

        request.removeHeader("Authorization");
        assertEquals(401, status(request, PROJECT, ORGANIZATION));
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.removeHeader("X-AuthWeave-Oidc-Subject");
        assertEquals(401, status(request, PROJECT, ORGANIZATION));
    }

    @Test
    void curatorBoundaryRejectsMissingWrongDuplicateAndStaleAssertions() throws Exception {
        String path = "/api/v2/catalog-change-proposals/33333333-3333-4333-8333-333333333333/decisions";
        var request = curatorRequest(path, Instant.now());
        assertEquals(200, status(request, PROJECT, ORGANIZATION));

        request.removeHeader("X-AuthWeave-Curator-Role");
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
        request.addHeader("X-AuthWeave-Curator-Role", "admin");
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
        request.addHeader("X-AuthWeave-Curator-Role", "catalog_curator");
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
        request.removeHeader("X-AuthWeave-Curator-Role");
        request.addHeader("X-AuthWeave-Curator-Role", "catalog_curator");

        request.removeHeader("X-AuthWeave-Curator-Project-Id");
        request.addHeader("X-AuthWeave-Curator-Project-Id", "111111111111111111");
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
        request.removeHeader("X-AuthWeave-Curator-Project-Id");
        request.addHeader("X-AuthWeave-Curator-Project-Id", PROJECT);
        request.removeHeader("X-AuthWeave-Curator-Org-Id");
        request.addHeader("X-AuthWeave-Curator-Org-Id", "111111111111111111");
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
        request.removeHeader("X-AuthWeave-Curator-Org-Id");
        request.addHeader("X-AuthWeave-Curator-Org-Id", ORGANIZATION);

        request.removeHeader("X-AuthWeave-Authenticated-At");
        request.addHeader("X-AuthWeave-Authenticated-At", Instant.now().minusSeconds(901).toString());
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
        request.removeHeader("X-AuthWeave-Authenticated-At");
        request.addHeader("X-AuthWeave-Authenticated-At", Instant.now().plusSeconds(31).toString());
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
        request.removeHeader("X-AuthWeave-Authenticated-At");
        request.addHeader("X-AuthWeave-Authenticated-At", "not-an-instant");
        assertEquals(403, status(request, PROJECT, ORGANIZATION));
    }

    private static int status(MockHttpServletRequest request, String project, String organization)
            throws Exception {
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter(TOKEN, null, project, organization)
                .doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static MockHttpServletRequest curatorRequest(String path, Instant authenticatedAt) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("X-AuthWeave-Oidc-Issuer", "http://localhost:8081");
        request.addHeader("X-AuthWeave-Oidc-Subject", "synthetic-curator");
        request.addHeader("X-AuthWeave-Curator-Role", "catalog_curator");
        request.addHeader("X-AuthWeave-Curator-Project-Id", PROJECT);
        request.addHeader("X-AuthWeave-Curator-Org-Id", ORGANIZATION);
        request.addHeader("X-AuthWeave-Authenticated-At", authenticatedAt.toString());
        return request;
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/internal/v1/personal-workspaces");
        request.setServletPath("/internal/v1/personal-workspaces");
        return request;
    }
}
