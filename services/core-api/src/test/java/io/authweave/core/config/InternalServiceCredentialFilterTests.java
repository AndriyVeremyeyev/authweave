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
    void assurancePlanningRechecksEncodedPersonalRoutesAndRejectsInputWithoutCallingTheService() {
        var id = java.util.UUID.fromString("60000000-0000-4000-8000-000000000001");
        var assessment = java.util.UUID.fromString("80000000-0000-4000-8000-000000000001");
        var workspaces = org.mockito.Mockito.mock(io.authweave.core.assessment.application.PersonalWorkspaceService.class);
        org.mockito.Mockito.when(workspaces.owns("http://localhost:8081", "owner", id)).thenReturn(true);
        var service = org.mockito.Mockito.mock(io.authweave.core.evaluation.AssuranceCompliancePlanningService.class);
        String path = "/%61pi/v1/workspaces/" + id + "/assessments/" + assessment + "/assurance-compliance-planning-preflight";
        for (String configured : java.util.List.of("", TOKEN)) for (String subject : java.util.List.of("", "other-owner", "owner")) {
            var controller = new io.authweave.core.evaluation.AssuranceCompliancePlanningController(service, new InternalServiceCredentialFilter(configured, workspaces, "", ""));
            var request = new MockHttpServletRequest("GET", path); request.addHeader("Authorization", "Bearer " + TOKEN);
            request.addHeader("X-AuthWeave-Oidc-Issuer", "http://localhost:8081"); if (!subject.isEmpty()) request.addHeader("X-AuthWeave-Oidc-Subject", subject);
            request.setQueryString("");
            var response = controller.preview(id, assessment, request);
            assertEquals(configured.isEmpty() ? 503 : subject.isEmpty() ? 401 : subject.equals("owner") ? 400 : 403, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl());
        }
        var controller = new io.authweave.core.evaluation.AssuranceCompliancePlanningController(service, new InternalServiceCredentialFilter(TOKEN, workspaces, "", ""));
        for (String variant : java.util.List.of("body", "chunked", "duplicate")) {
            var request = new MockHttpServletRequest("GET", path); request.addHeader("Authorization", "Bearer " + TOKEN);
            request.addHeader("X-AuthWeave-Oidc-Issuer", "http://localhost:8081"); request.addHeader("X-AuthWeave-Oidc-Subject", "owner");
            if (variant.equals("body")) request.setContent(new byte[] { 1 });
            if (variant.equals("chunked")) request.addHeader("Transfer-Encoding", "chunked");
            if (variant.equals("duplicate")) request.addHeader("Authorization", "Bearer " + TOKEN);
            assertEquals(variant.equals("duplicate") ? 401 : 400, controller.preview(id, assessment, request).getStatusCode().value());
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void operationsControllerBindsRoutedWorkspaceEvenWhenUriEncodingBypassesTheFilter() throws Exception {
        var id = java.util.UUID.fromString("60000000-0000-4000-8000-000000000001");
        var assessment = java.util.UUID.fromString("80000000-0000-4000-8000-000000000001");
        var workspaces = org.mockito.Mockito.mock(io.authweave.core.assessment.application.PersonalWorkspaceService.class);
        org.mockito.Mockito.when(workspaces.owns("http://localhost:8081", "owner", id)).thenReturn(true);
        var service = org.mockito.Mockito.mock(io.authweave.core.evaluation.OperationsPlanningPreflightService.class);
        var filter = new InternalServiceCredentialFilter(TOKEN, workspaces, "", "");
        var controller = new io.authweave.core.evaluation.OperationsPlanningPreflightController(service, filter);
        String path = "/%61pi/v1/workspaces/" + id + "/assessments/" + assessment + "/operations-planning-preflight";
        for (String subject : java.util.List.of("", "other-owner", "owner")) {
            var request = new MockHttpServletRequest("GET", path); request.addHeader("Authorization", "Bearer " + TOKEN); request.addHeader("X-AuthWeave-Oidc-Issuer", "http://localhost:8081");
            if (!subject.isEmpty()) request.addHeader("X-AuthWeave-Oidc-Subject", subject);
            if (subject.equals("owner")) request.setQueryString("");
            assertEquals(subject.isEmpty() ? 401 : subject.equals("owner") ? 400 : 403, controller.preview(id, assessment, request).getStatusCode().value());
        }
        var request = new MockHttpServletRequest("GET", path);
        assertEquals(401, controller.preview(id, assessment, request).getStatusCode().value());
        assertEquals(503, new InternalServiceCredentialFilter("", workspaces, "", "").personalWorkspaceStatus(request, id));
        org.mockito.Mockito.verifyNoInteractions(service);
        request.addHeader("Authorization", "Bearer " + TOKEN); request.addHeader("X-AuthWeave-Oidc-Issuer", "http://localhost:8081"); request.addHeader("X-AuthWeave-Oidc-Subject", "owner");
        assertEquals(200, filter.personalWorkspaceStatus(request, id));
        assertEquals(403, filter.personalWorkspaceStatus(request, java.util.UUID.randomUUID()));
    }

    @Test
    void lifecycleRegressionRequiresOneCredentialAndRechecksEncodedRoutingWithoutCallerInputs() throws Exception {
        String path = "/internal/v1/catalog-provisioning-lifecycle/regression-preflight";
        for (var configured : java.util.List.of("", TOKEN)) for (var supplied : java.util.List.of("", "wrong", TOKEN, "duplicate")) {
            var request = new MockHttpServletRequest("GET", path);
            if (!supplied.isEmpty()) request.addHeader("Authorization", "Bearer " + (supplied.equals("duplicate") ? TOKEN : supplied));
            if (supplied.equals("duplicate")) request.addHeader("Authorization", "Bearer " + TOKEN);
            var response = new MockHttpServletResponse();
            new InternalServiceCredentialFilter(configured, null, "", "").doFilter(request, response, new MockFilterChain());
            assertEquals(configured.isEmpty() ? 503 : supplied.equals(TOKEN) ? 200 : 401, response.getStatus());
        }
        String encoded = "/%69nternal/v1/catalog-provisioning-lifecycle/regression-preflight";
        var controller = new io.authweave.core.catalog.impact.CatalogLifecycleRegressionController(null,
                new InternalServiceCredentialFilter(TOKEN, null, "", ""), java.time.Clock.systemUTC());
        var refusal = controller.inspect(new MockHttpServletRequest("GET", encoded));
        assertEquals(401, refusal.getStatusCode().value()); assertEquals("no-store", refusal.getHeaders().getCacheControl());
        for (String variant : java.util.List.of("query", "body", "chunked")) {
            var request = new MockHttpServletRequest("GET", encoded); request.addHeader("Authorization", "Bearer " + TOKEN);
            if (variant.equals("query")) request.setQueryString(""); if (variant.equals("body")) request.setContent(new byte[] { 1 });
            if (variant.equals("chunked")) request.addHeader("Transfer-Encoding", "chunked");
            assertEquals(400, controller.inspect(request).getStatusCode().value());
        }
        var unavailable = new io.authweave.core.catalog.impact.CatalogLifecycleRegressionController(null,
                new InternalServiceCredentialFilter("", null, "", ""), java.time.Clock.systemUTC());
        assertEquals(503, unavailable.inspect(new MockHttpServletRequest("GET", encoded)).getStatusCode().value());
    }

    @Test
    void operationsRegressionRequiresOneCredentialAndRejectsCallerInputsAfterEncodedRouting() throws Exception {
        String path = "/internal/v1/catalog-operations-planning/regression-preflight";
        for (var configured : java.util.List.of("", TOKEN)) for (var supplied : java.util.List.of("", "wrong", TOKEN, "duplicate")) {
            var request = new MockHttpServletRequest("GET", path);
            if (!supplied.isEmpty()) request.addHeader("Authorization", "Bearer " + (supplied.equals("duplicate") ? TOKEN : supplied));
            if (supplied.equals("duplicate")) request.addHeader("Authorization", "Bearer " + TOKEN);
            var response = new MockHttpServletResponse();
            new InternalServiceCredentialFilter(configured, null, "", "").doFilter(request, response, new MockFilterChain());
            assertEquals(configured.isEmpty() ? 503 : supplied.equals(TOKEN) ? 200 : 401, response.getStatus());
        }
        String encoded = "/%69nternal/v1/catalog-operations-planning/regression-preflight";
        var controller = new io.authweave.core.catalog.impact.CatalogOperationsPlanningRegressionController(null,
                new InternalServiceCredentialFilter(TOKEN, null, "", ""), java.time.Clock.systemUTC());
        var refusal = controller.inspect(new MockHttpServletRequest("GET", encoded));
        assertEquals(401, refusal.getStatusCode().value()); assertEquals("no-store", refusal.getHeaders().getCacheControl());
        for (String variant : java.util.List.of("query", "body", "chunked")) {
            var request = new MockHttpServletRequest("GET", encoded); request.addHeader("Authorization", "Bearer " + TOKEN);
            if (variant.equals("query")) request.setQueryString("");
            if (variant.equals("body")) request.setContent(new byte[] { 1 });
            if (variant.equals("chunked")) request.addHeader("Transfer-Encoding", "chunked");
            assertEquals(400, controller.inspect(request).getStatusCode().value());
        }
        var unavailable = new io.authweave.core.catalog.impact.CatalogOperationsPlanningRegressionController(null,
                new InternalServiceCredentialFilter("", null, "", ""), java.time.Clock.systemUTC());
        assertEquals(503, unavailable.inspect(new MockHttpServletRequest("GET", encoded)).getStatusCode().value());
    }

    @Test
    void configurationRegressionRequiresOneConfiguredServiceCredentialAndControllerRechecksEncodedRouting() throws Exception {
        String path = "/internal/v1/catalog-architecture-configuration/regression-preflight";
        for (var configured : java.util.List.of("", TOKEN)) for (var supplied : java.util.List.of("", "wrong", TOKEN, "duplicate")) {
            var request = new MockHttpServletRequest("GET", path);
            if (!supplied.isEmpty()) request.addHeader("Authorization", "Bearer " + (supplied.equals("duplicate") ? TOKEN : supplied));
            if (supplied.equals("duplicate")) request.addHeader("Authorization", "Bearer " + TOKEN);
            var response = new MockHttpServletResponse();
            new InternalServiceCredentialFilter(configured, null, "", "").doFilter(request, response, new MockFilterChain());
            assertEquals(configured.isEmpty() ? 503 : supplied.equals(TOKEN) ? 200 : 401, response.getStatus());
        }
        var controller = new io.authweave.core.catalog.impact.CatalogArchitectureConfigurationRegressionController(null,
                new InternalServiceCredentialFilter(TOKEN, null, "", ""), java.time.Clock.systemUTC());
        assertEquals(401, controller.inspect(new MockHttpServletRequest("GET", "/%69nternal/v1/catalog-architecture-configuration/regression-preflight")).getStatusCode().value());
        var query = new MockHttpServletRequest("GET", path); query.addHeader("Authorization", "Bearer " + TOKEN); query.setQueryString("");
        assertEquals(400, controller.inspect(query).getStatusCode().value());
    }

    @Test
    void v6RoutesRequireServiceCredentialAndExactPersonalWorkspaceOwnership() throws Exception {
        var id = java.util.UUID.fromString("60000000-0000-4000-8000-000000000001");
        var workspaces = org.mockito.Mockito.mock(io.authweave.core.assessment.application.PersonalWorkspaceService.class);
        org.mockito.Mockito.when(workspaces.owns("http://localhost:8081", "owner", id)).thenReturn(true);
        for (String suffix : java.util.List.of("", "/context-index", "/80000000-0000-4000-8000-000000000001",
                "/80000000-0000-4000-8000-000000000001/profile", "/80000000-0000-4000-8000-000000000001/revisions",
                "/80000000-0000-4000-8000-000000000001/auditability-capability-preflight",
                "/80000000-0000-4000-8000-000000000001/hard-constraint-preflight",
                "/80000000-0000-4000-8000-000000000001/comparison-preflight",
                "/80000000-0000-4000-8000-000000000001/weighted-comparison-preview",
                "/80000000-0000-4000-8000-000000000001/weight-sensitivity-preview")) {
            for (String subject : java.util.List.of("", "other-owner", "owner")) {
                String method = suffix.endsWith("-preview") ? "POST" : suffix.endsWith("/profile") ? "PUT" : "GET";
                var request = new MockHttpServletRequest(method, "/api/v6/workspaces/" + id + "/assessments" + suffix);
                request.addHeader("Authorization", "Bearer " + TOKEN);
                request.addHeader("X-AuthWeave-Oidc-Issuer", "http://localhost:8081");
                if (!subject.isEmpty()) request.addHeader("X-AuthWeave-Oidc-Subject", subject);
                var response = new MockHttpServletResponse();
                new InternalServiceCredentialFilter(TOKEN, workspaces, "", "").doFilter(request, response, new MockFilterChain());
                assertEquals(subject.isEmpty() ? 401 : subject.equals("owner") ? 200 : 403, response.getStatus());
            }
            for (String token : java.util.List.of("", "wrong-token")) {
                var request = new MockHttpServletRequest("GET", "/api/v6/workspaces/" + id + "/assessments" + suffix);
                if (!token.isEmpty()) request.addHeader("Authorization", "Bearer " + token);
                var response = new MockHttpServletResponse();
                new InternalServiceCredentialFilter(TOKEN, workspaces, "", "").doFilter(request, response, new MockFilterChain());
                assertEquals(401, response.getStatus());
            }
        }
    }

    @Test
    void prerequisitePreviewRequiresServiceIdentityAndPersonalWorkspaceOwnership() throws Exception {
        var workspaceId = java.util.UUID.fromString("60000000-0000-4000-8000-000000000001");
        var workspaces = org.mockito.Mockito.mock(io.authweave.core.assessment.application.PersonalWorkspaceService.class);
        org.mockito.Mockito.when(workspaces.owns("http://localhost:8081", "owner", workspaceId)).thenReturn(true);
        String path = "/api/v1/workspaces/" + workspaceId + "/assessments/80000000-0000-4000-8000-000000000001/architecture-prerequisite-preview";
        for (String subject : java.util.List.of("", "other-owner", "owner")) {
            var request = new MockHttpServletRequest("POST", path);
            request.addHeader("Authorization", "Bearer " + TOKEN);
            request.addHeader("X-AuthWeave-Oidc-Issuer", "http://localhost:8081");
            if (!subject.isEmpty()) request.addHeader("X-AuthWeave-Oidc-Subject", subject);
            var response = new MockHttpServletResponse();
            new InternalServiceCredentialFilter(TOKEN, workspaces, "", "").doFilter(request, response, new MockFilterChain());
            assertEquals(subject.isEmpty() ? 401 : subject.equals("owner") ? 200 : 403, response.getStatus());
        }
        var missingToken = new MockHttpServletRequest("POST", path);
        var response = new MockHttpServletResponse();
        new InternalServiceCredentialFilter(TOKEN, workspaces, "", "").doFilter(missingToken, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

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
    void bootstrapReviewRoutesFailClosedWhenCredentialOrCuratorScopeIsMissing() throws Exception {
        for (String path : java.util.List.of("/api/v1/catalog-bootstrap-reviews", "/api/v2/catalog-bootstrap-reviews/unknown")) {
            var request = curatorRequest(path, Instant.now());
            assertEquals(503, status(request, "", ORGANIZATION));
            assertEquals(503, status(request, PROJECT, ""));
            var response = new MockHttpServletResponse();
            new InternalServiceCredentialFilter("", null, PROJECT, ORGANIZATION).doFilter(request, response, new MockFilterChain());
            assertEquals(503, response.getStatus());
        }
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
