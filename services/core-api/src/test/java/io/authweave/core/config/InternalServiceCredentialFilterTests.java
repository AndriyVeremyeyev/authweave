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
        new InternalServiceCredentialFilter("").doFilter(request, response, new MockFilterChain());
        assertEquals(503, response.getStatus());
    }

    @Test
    void refusesDuplicateAuthorizationHeaders() throws Exception {
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter(TOKEN).doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/internal/v1/personal-workspaces");
        request.setServletPath("/internal/v1/personal-workspaces");
        return request;
    }
}
