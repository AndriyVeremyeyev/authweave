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
    @ValueSource(strings = {"keycloak", "zitadel", "auth0", "workos", "entra-external-id"})
    void scopedAndResearchOptionsCanCoexistWithoutMergingOrCompletingCoverage(String provider) throws Exception {
        var research = mapper.readValue(resource("catalog/baselines/" + provider + ".v1.json"), ProviderCatalogDraft.class);
        var scope = switch (provider) {
            case "keycloak" -> "keycloak-26.8.0";
            case "zitadel" -> "zitadel-cloud-free";
            case "auth0" -> "auth0-b2b-free";
            case "workos" -> "workos-directory-sync-staging";
            case "entra-external-id" -> "entra-external-id-basic";
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

    @Test
    void entraBasicExternalTenantLoginDoesNotPromotePaidInboundScimOrGraphGroupManagement() throws Exception {
        var json = resource("catalog/baselines/scoped/entra-external-id-basic.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("entra-external-id-basic-standard-native", option.id());
        assertEquals("Microsoft Entra External ID - external tenant", option.product());
        assertEquals("Basic MAU; documented free allowance, no tenant entitlement verified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        var expected = Map.of(ProviderCatalog.Capability.OIDC, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.SAML, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.SCIM, ProviderCatalog.Availability.UNKNOWN,
                ProviderCatalog.Capability.GROUP_SYNC, ProviderCatalog.Availability.UNKNOWN);
        assertEquals(expected.keySet(), option.facts().keySet());
        var observed = Instant.parse("2026-10-02T23:12:01Z");
        option.facts().forEach((capability, fact) -> {
            assertEquals(expected.get(capability), fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("learn.microsoft.com", fact.evidence().sourceUrl().getHost());
            assertFalse(fact.conditions().isEmpty());
        });
        assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.OIDC).conditions())
                .contains("ciamlogin.com authority"));
        assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.SAML).conditions())
                .contains("current administrator"));
        var scim = option.facts().get(ProviderCatalog.Capability.SCIM);
        assertEquals("/en-us/entra/identity/app-provisioning/enable-scim-api", scim.evidence().sourceUrl().getPath());
        var scimConditions = String.join(" ", scim.conditions());
        assertTrue(scimConditions.contains("P1 and an Azure-linked paid add-on"));
        assertTrue(scimConditions.contains("not outbound provisioning"));
        assertTrue(scimConditions.contains("HSC mode, not all external tenants"));
        assertTrue(scimConditions.contains("UNKNOWN is not UNAVAILABLE"));
        assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).conditions())
                .contains("not native inbound SCIM Group lifecycle evidence"));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
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

    @ParameterizedTest
    @ValueSource(strings = {"okta", "entra"})
    void auth0WorkforcePairsPreserveConnectorIdentityAndDoNotInheritGenericProvisioning(String upstream) throws Exception {
        var json = resource("catalog/baselines/scoped/auth0-b2b-free-upstream-" + upstream + ".v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("auth0-b2b-free-upstream-" + upstream + "-workforce", option.id());
        assertEquals("auth0", option.providerId());
        assertEquals("Auth0 Public Cloud", option.product());
        assertEquals("B2B Free; upstream workforce entitlement unverified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        var expected = Map.of(ProviderCatalog.Capability.ENTERPRISE_SSO, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.SCIM, ProviderCatalog.Availability.UNKNOWN,
                ProviderCatalog.Capability.GROUP_SYNC, ProviderCatalog.Availability.UNKNOWN);
        assertEquals(expected.keySet(), option.facts().keySet());
        var observed = Instant.parse("2026-10-02T23:25:54Z");
        option.facts().forEach((capability, fact) -> {
            assertEquals(expected.get(capability), fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("auth0.com", fact.evidence().sourceUrl().getHost());
            assertFalse(fact.conditions().isEmpty());
        });
        var scim = option.facts().get(ProviderCatalog.Capability.SCIM);
        var conditions = String.join(" ", scim.conditions());
        assertTrue(conditions.contains("upstream"));
        assertTrue(conditions.contains("entitlement"));
        if (upstream.equals("okta")) {
            assertEquals("/docs/authenticate/protocols/scim/inbound-scim-for-okta-workforce-connections",
                    scim.evidence().sourceUrl().getPath());
            assertTrue(conditions.contains("separate OIDC and SCIM app instances"));
            assertTrue(conditions.contains("Federation Broker Mode"));
            assertTrue(conditions.contains("without password provisioning"));
            assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).conditions())
                    .contains("assignment is not Group Push"));
        } else {
            assertEquals("/docs/authenticate/protocols/scim/inbound-scim-for-new-azure-ad-connections",
                    scim.evidence().sourceUrl().getPath());
            assertTrue(conditions.contains("oid"));
            assertTrue(conditions.contains("Common Endpoint disabled"));
            assertTrue(conditions.contains("externalId mapped from Entra objectId"));
            assertTrue(conditions.contains("legacy pairwise sub mapping"));
            assertTrue(conditions.contains("Assignment Required"));
            assertTrue(conditions.contains("separate non-gallery provisioning app"));
        }
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var current = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, current.status());
        assertEquals(3, current.factCount());
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
        var generic = mapper.readValue(resource("catalog/baselines/scoped/auth0-b2b-free.v1.json"), ProviderCatalogDraft.class);
        var combined = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "auth0-generic-and-" + upstream + "-test", List.of(generic.options().getFirst(), option));
        var combinedReport = validator.validateAt(combined, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, combinedReport.status());
        assertEquals(2, combinedReport.optionCount());
        assertEquals(7, combinedReport.factCount());
        assertUntrusted(combinedReport);
        assertEquals(ProviderCatalog.Availability.OPTIONAL,
                generic.options().getFirst().facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(ProviderCatalog.Availability.UNKNOWN, scim.availability());
        assertNotEquals(validator.validate(generic).contentSha256(), combinedReport.contentSha256());
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
