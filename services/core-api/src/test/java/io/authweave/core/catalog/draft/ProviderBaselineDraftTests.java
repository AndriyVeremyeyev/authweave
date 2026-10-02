package io.authweave.core.catalog.draft;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.catalog.ProviderCatalog;

import static org.junit.jupiter.api.Assertions.*;

class ProviderBaselineDraftTests {
    private static final Instant OBSERVED = Instant.parse("2026-10-02T19:58:06Z");
    private static final Map<String, String> HOSTS = Map.of(
            "entra-external-id", "learn.microsoft.com", "auth0", "auth0.com", "workos", "workos.com",
            "zitadel", "zitadel.com", "keycloak", "www.keycloak.org");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogDraftValidator validator = new CatalogDraftValidator(Clock.fixed(OBSERVED, ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = {"entra-external-id", "auth0", "workos", "zitadel", "keycloak"})
    void realProviderResearchUsesTheExistingTypedDraftBoundaryWithoutActivation(String provider) throws Exception {
        var json = resource("catalog/baselines/" + provider + ".v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        assertEquals(provider + "-research-2026.10.02", draft.catalogVersion());
        assertEquals(1, draft.options().size());
        var option = draft.options().getFirst();
        assertEquals(provider, option.providerId());
        assertEquals(provider.equals("keycloak") ? ProviderCatalogDraft.Deployment.SELF_HOSTED
                : ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(3, CatalogDraftFacts.entries(option).size());
        option.facts().forEach((capability, fact) -> {
            assertEquals(ProviderCatalog.Availability.UNKNOWN, fact.availability());
            assertFalse(fact.conditions().isEmpty());
            assertEquals(HOSTS.get(provider), fact.evidence().sourceUrl().getHost());
            assertEquals(OBSERVED, fact.evidence().observedAt());
        });
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validate(draft);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(3, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        var stale = validator.validateAt(draft, OBSERVED.plusSeconds(90L * 86400 + 1));
        assertUntrusted(stale);
        assertEquals(report.contentSha256(), stale.contentSha256());
        assertTrue(stale.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.STALE));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
    }

    @Test
    void syntheticCatalogRemainsSeparateFromAllRealProviderResearch() throws Exception {
        var active = mapper.readValue(resource("catalog/synthetic.v4.json"), ProviderCatalog.class);
        assertEquals(ProviderCatalog.Kind.SYNTHETIC, active.kind());
        assertFalse(active.options().isEmpty());
        assertEquals(List.of("fictional-complete", "fictional-no-scim", "fictional-unreviewed"),
                active.options().stream().map(ProviderCatalog.Option::id).sorted().toList());
    }

    @Test
    void releaseScopedOptionalAssertionsDoNotBecomeReviewedOrAnActiveCatalog() throws Exception {
        var json = resource("catalog/baselines/scoped/keycloak-26.8.0.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("keycloak-26.8.0-native-self-hosted", option.id());
        assertEquals(ProviderCatalogDraft.Deployment.SELF_HOSTED, option.deployment());
        assertEquals("Keycloak upstream 26.8.0", option.product());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC, ProviderCatalog.Capability.SAML,
                ProviderCatalog.Capability.SCIM, ProviderCatalog.Capability.GROUP_SYNC), option.facts().keySet());
        var observed = Instant.parse("2026-10-02T21:20:39Z");
        option.facts().values().forEach(fact -> {
            assertEquals(ProviderCatalog.Availability.OPTIONAL, fact.availability());
            assertFalse(fact.conditions().isEmpty());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("github.com", fact.evidence().sourceUrl().getHost());
            assertTrue(fact.evidence().sourceUrl().getPath().startsWith(
                    "/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/"));
        });
        var current = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, current.status());
        assertEquals(4, current.factCount());
        assertUntrusted(current);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            assertUntrusted(report);
            assertEquals(current.contentSha256(), report.contentSha256());
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
        }
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
    }

    @Test
    void cloudFreeDraftPreservesMixedAvailabilityWithoutVerifyingEntitlementOrDeployment() throws Exception {
        var json = resource("catalog/baselines/scoped/zitadel-cloud-free.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("zitadel-cloud-free-native", option.id());
        assertEquals("ZITADEL Cloud", option.product());
        assertEquals("Free; documented offer, no account entitlement verified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, option.facts().get(ProviderCatalog.Capability.OIDC).availability());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, option.facts().get(ProviderCatalog.Capability.SAML).availability());
        var scim = option.facts().get(ProviderCatalog.Capability.SCIM);
        var groups = option.facts().get(ProviderCatalog.Capability.GROUP_SYNC);
        assertEquals(ProviderCatalog.Availability.UNKNOWN, scim.availability());
        assertEquals(ProviderCatalog.Availability.UNAVAILABLE, groups.availability());
        assertTrue(String.join(" ", scim.conditions()).contains("Preview"));
        assertTrue(String.join(" ", groups.conditions()).contains("no external bridge"));
        var observed = Instant.parse("2026-10-02T21:44:10Z");
        option.facts().values().forEach(fact -> {
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("zitadel.com", fact.evidence().sourceUrl().getHost());
            assertFalse(fact.conditions().isEmpty());
        });
        var current = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, current.status());
        assertEquals(4, current.factCount());
        assertTrue(current.issues().isEmpty());
        assertUntrusted(current);
        assertTrue(current.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            assertUntrusted(report);
            assertEquals(current.contentSha256(), report.contentSha256());
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
        }
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"keycloak", "zitadel", "auth0", "workos"})
    void scopedAndResearchOptionsCanCoexistWithoutMergingOrCompletingCoverage(String provider) throws Exception {
        var research = mapper.readValue(resource("catalog/baselines/" + provider + ".v1.json"), ProviderCatalogDraft.class);
        var scope = switch (provider) {
            case "keycloak" -> "keycloak-26.8.0";
            case "zitadel" -> "zitadel-cloud-free";
            case "auth0" -> "auth0-b2b-free";
            case "workos" -> "workos-directory-sync-staging";
            default -> throw new AssertionError("Unexpected provider: " + provider);
        };
        var scoped = mapper.readValue(resource("catalog/baselines/scoped/" + scope + ".v1.json"), ProviderCatalogDraft.class);
        var combined = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                provider + "-research-and-scoped-test", List.of(research.options().getFirst(), scoped.options().getFirst()));
        var report = validator.validateAt(combined, Instant.parse("2026-10-02T22:07:48Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(2, report.optionCount());
        assertEquals(provider.equals("workos") ? 5 : 7, report.factCount());
        assertUntrusted(report);
        assertNotEquals(validator.validate(research).contentSha256(), report.contentSha256());
        assertNotEquals(validator.validate(scoped).contentSha256(), report.contentSha256());
        assertTrue(combined.options().stream().allMatch(option -> option.residency().isEmpty()
                && option.authenticationControls().isEmpty() && option.compatibility().clients().isEmpty()));
    }

    @Test
    void auth0FreeAssertionsSeparateProtocolDirectionsAndDoNotPromoteGroupsOrTenantEntitlement() throws Exception {
        var json = resource("catalog/baselines/scoped/auth0-b2b-free.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("auth0-b2b-free-oidc-scim", option.id());
        assertEquals("Auth0 Public Cloud", option.product());
        assertEquals("B2B Free; one Enterprise Connection, no account entitlement verified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        var expected = Map.of(ProviderCatalog.Capability.OIDC, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.ENTERPRISE_SSO, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.SCIM, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.GROUP_SYNC, ProviderCatalog.Availability.UNKNOWN);
        assertEquals(expected.keySet(), option.facts().keySet());
        var observed = Instant.parse("2026-10-02T22:07:48Z");
        option.facts().forEach((capability, fact) -> {
            assertEquals(expected.get(capability), fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("auth0.com", fact.evidence().sourceUrl().getHost());
            assertFalse(fact.conditions().isEmpty());
        });
        assertNotEquals(option.facts().get(ProviderCatalog.Capability.OIDC).evidence().sourceUrl(),
                option.facts().get(ProviderCatalog.Capability.ENTERPRISE_SSO).evidence().sourceUrl());
        assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.SCIM).conditions())
                .contains("ID token sub to SCIM externalId"));
        assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).conditions())
                .contains("group-specific entitlement"));
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var current = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, current.status());
        assertEquals(4, current.factCount());
        assertTrue(current.issues().isEmpty());
        assertUntrusted(current);
        assertTrue(current.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            assertUntrusted(report);
            assertEquals(current.contentSha256(), report.contentSha256());
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
        }
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
    }

    @Test
    void workosStagingDirectoryBridgeDoesNotAssertLoginProductionEntitlementOrAccessEnforcement() throws Exception {
        var json = resource("catalog/baselines/scoped/workos-directory-sync-staging.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("workos-directory-sync-staging-scim-events", option.id());
        assertEquals("WorkOS Directory Sync", option.product());
        assertEquals("Staging; testing only, no account or production entitlement verified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.SCIM, ProviderCatalog.Capability.GROUP_SYNC),
                option.facts().keySet());
        var observed = Instant.parse("2026-10-02T22:46:13Z");
        option.facts().values().forEach(fact -> {
            assertEquals(ProviderCatalog.Availability.OPTIONAL, fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("workos.com", fact.evidence().sourceUrl().getHost());
            assertFalse(fact.conditions().isEmpty());
        });
        var scim = option.facts().get(ProviderCatalog.Capability.SCIM);
        var groups = option.facts().get(ProviderCatalog.Capability.GROUP_SYNC);
        assertEquals("/docs/integrations/scim", scim.evidence().sourceUrl().getPath());
        assertEquals("/docs/directory-sync/understanding-events", groups.evidence().sourceUrl().getPath());
        var scimConditions = String.join(" ", scim.conditions());
        assertTrue(scimConditions.contains("not free production Directory Sync"));
        assertTrue(scimConditions.contains("read-only"));
        assertTrue(scimConditions.contains("not a native SCIM endpoint in the SaaS or write-back"));
        assertTrue(scimConditions.contains("persist an Events API cursor"));
        assertTrue(scimConditions.contains("not necessarily deleted from the SaaS"));
        var groupConditions = String.join(" ", groups.conditions());
        assertTrue(groupConditions.contains("does not emit individual dsync.group.user_removed"));
        assertTrue(groupConditions.contains("user updated_at does not change"));
        assertTrue(groupConditions.contains("deprecated Directory User groups field"));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var current = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, current.status());
        assertEquals(2, current.factCount());
        assertTrue(current.issues().isEmpty());
        assertUntrusted(current);
        assertTrue(current.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            assertUntrusted(report);
            assertEquals(current.contentSha256(), report.contentSha256());
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
        }
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
    }

    private void assertUntrusted(CatalogDraftValidation report) {
        assertFalse(report.sourceVerificationPerformed());
        assertFalse(report.approvalGranted());
        assertFalse(report.writesPerformed());
        assertFalse(report.evaluationReady());
        assertTrue(report.facts().stream().allMatch(fact -> fact.evidenceStatus() == CatalogDraftValidation.ReviewStatus.UNREVIEWED));
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
