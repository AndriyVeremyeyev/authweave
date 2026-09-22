package io.authweave.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalServiceCredentialFilterTests {

    private static final String TOKEN = "synthetic-internal-token-000000000000000000000";

    @Test
    void refusesProvisioningWhenNoServiceCredentialIsConfigured() throws Exception {
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter("", null)
                .doFilter(request, response, new MockFilterChain());
        assertEquals(503, response.getStatus());
    }

    @Test
    void refusesDuplicateAuthorizationHeaders() throws Exception {
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter(TOKEN, null)
                .doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void refusesWorkspaceRoutesWhenNoServiceCredentialIsConfigured() throws Exception {
        var request = new MockHttpServletRequest("GET",
                "/api/v6/workspaces/60000000-0000-4000-8000-000000000001/assessments");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter("", null)
                .doFilter(request, response, new MockFilterChain());
        assertEquals(503, response.getStatus());
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/internal/v1/personal-workspaces");
        request.setServletPath("/internal/v1/personal-workspaces");
        return request;
    }
}
