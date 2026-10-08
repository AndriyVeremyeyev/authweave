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
import io.authweave.core.catalog.impact.CatalogFactPathRegressionCases;

import static org.junit.jupiter.api.Assertions.*;

class ProviderBaselineDraftTests {
    private static final Instant OBSERVED = Instant.parse("2026-10-02T19:58:06Z");
    private static final Map<String, String> HOSTS = Map.of(
            "entra-external-id", "learn.microsoft.com", "auth0", "auth0.com", "workos", "workos.com",
            "zitadel", "zitadel.com", "keycloak", "www.keycloak.org");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
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

    @ParameterizedTest
    @ValueSource(strings = {"okta", "entra"})
    void zitadelWorkforcePairsKeepLoginTimeJitSeparateFromScimAndGenericCloudScope(String upstream) throws Exception {
        var json = resource("catalog/baselines/scoped/zitadel-cloud-free-upstream-" + upstream + ".v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("zitadel-cloud-free-upstream-" + upstream + "-workforce", option.id());
        assertEquals("zitadel", option.providerId());
        assertEquals("ZITADEL Cloud", option.product());
        assertEquals("Free; upstream workforce entitlement unverified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        var expected = Map.of(ProviderCatalog.Capability.ENTERPRISE_SSO, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.JIT, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.SCIM, ProviderCatalog.Availability.UNKNOWN,
                ProviderCatalog.Capability.GROUP_SYNC, ProviderCatalog.Availability.UNAVAILABLE);
        assertEquals(expected.keySet(), option.facts().keySet());
        var observed = Instant.parse("2026-10-03T00:27:18Z");
        option.facts().forEach((capability, fact) -> {
            assertEquals(expected.get(capability), fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("zitadel.com", fact.evidence().sourceUrl().getHost());
            assertFalse(fact.conditions().isEmpty());
        });
        var jit = option.facts().get(ProviderCatalog.Capability.JIT);
        assertEquals("/docs/guides/integrate/identity-providers/introduction", jit.evidence().sourceUrl().getPath());
        assertTrue(String.join(" ", jit.conditions()).contains("at login, not through background provisioning"));
        assertTrue(String.join(" ", jit.conditions()).contains("Account linking and email trust"));
        var scim = option.facts().get(ProviderCatalog.Capability.SCIM);
        var conditions = String.join(" ", scim.conditions());
        assertTrue(conditions.contains("Preview"));
        assertTrue(conditions.contains("Free-plan access"));
        assertTrue(conditions.contains("UNKNOWN is not UNAVAILABLE"));
        if (upstream.equals("okta")) {
            assertEquals("/docs/guides/integrate/scim-okta-guide", scim.evidence().sourceUrl().getPath());
            assertTrue(conditions.contains("existing SAML integration"));
            assertTrue(conditions.contains("does not validate this selected generic OIDC pairing"));
        } else {
            assertEquals("/docs/apis/scim2", scim.evidence().sourceUrl().getPath());
            assertTrue(conditions.contains("do not inherit Auth0-specific oid/objectId/externalId mapping"));
            assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.ENTERPRISE_SSO).conditions())
                    .contains("fixed workforce Tenant ID"));
        }
        var groups = option.facts().get(ProviderCatalog.Capability.GROUP_SYNC);
        assertTrue(String.join(" ", groups.conditions()).contains("native inbound SCIM Group"));
        assertTrue(String.join(" ", groups.conditions()).contains("Do not generalize"));
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
        var generic = mapper.readValue(resource("catalog/baselines/scoped/zitadel-cloud-free.v1.json"), ProviderCatalogDraft.class);
        assertFalse(generic.options().getFirst().facts().containsKey(ProviderCatalog.Capability.JIT));
        var combined = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "zitadel-generic-and-" + upstream + "-test", List.of(generic.options().getFirst(), option));
        var combinedReport = validator.validateAt(combined, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, combinedReport.status());
        assertEquals(2, combinedReport.optionCount());
        assertEquals(8, combinedReport.factCount());
        assertUntrusted(combinedReport);
        assertNotEquals(validator.validate(generic).contentSha256(), combinedReport.contentSha256());
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"okta", "entra"})
    void keycloakWorkforcePairsRemainUnreviewedWithoutInheritingNativeScim(String upstream) throws Exception {
        var json = resource("catalog/baselines/scoped/keycloak-26.8.0-upstream-" + upstream + ".v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("keycloak-26.8.0-upstream-" + upstream + "-workforce", option.id());
        assertEquals("keycloak", option.providerId());
        assertEquals("Keycloak upstream 26.8.0", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.SELF_HOSTED, option.deployment());
        assertTrue(option.plan().contains("upstream workforce entitlement and commercial support unverified"));
        var expected = Map.of(ProviderCatalog.Capability.ENTERPRISE_SSO, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.JIT, ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.Capability.SCIM, ProviderCatalog.Availability.UNKNOWN,
                ProviderCatalog.Capability.GROUP_SYNC, ProviderCatalog.Availability.UNKNOWN);
        var paths = Map.of(ProviderCatalog.Capability.ENTERPRISE_SSO, "identity-broker/oidc.adoc",
                ProviderCatalog.Capability.JIT, "identity-broker/first-login-flow.adoc",
                ProviderCatalog.Capability.SCIM, "scim/intro.adoc",
                ProviderCatalog.Capability.GROUP_SYNC, "identity-broker/mappers.adoc");
        assertEquals(expected.keySet(), option.facts().keySet());
        var observed = Instant.parse("2026-10-08T04:57:09Z");
        option.facts().forEach((capability, fact) -> {
            assertEquals(expected.get(capability), fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("github.com", fact.evidence().sourceUrl().getHost());
            assertEquals("/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/"
                    + "server_admin/topics/" + paths.get(capability), fact.evidence().sourceUrl().getPath());
            assertFalse(fact.conditions().isEmpty());
        });
        var login = String.join(" ", option.facts().get(ProviderCatalog.Capability.ENTERPRISE_SSO).conditions());
        assertTrue(login.contains("not vendor-certified or runtime-tested interoperability"));
        assertTrue(login.contains("Only the Keycloak source is release-pinned"));
        assertTrue(login.contains(upstream.equals("okta") ? "fixed org authorization server" : "fixed workforce Tenant ID"));
        var jit = String.join(" ", option.facts().get(ProviderCatalog.Capability.JIT).conditions());
        assertTrue(jit.contains("at login, not through background provisioning"));
        assertTrue(jit.contains("proof of control"));
        assertTrue(jit.contains("do not enable unverified automatic email-based linking"));
        var scim = String.join(" ", option.facts().get(ProviderCatalog.Capability.SCIM).conditions());
        assertTrue(scim.contains("identity correlation"));
        assertTrue(scim.contains("UNKNOWN is not UNAVAILABLE"));
        assertTrue(scim.contains("Do not inherit native SCIM OPTIONAL"));
        assertTrue(String.join(" ", option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).conditions())
                .contains("No external bridge is selected"));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().clients().isEmpty());
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
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"schemaVersion\": 1", "\"schemaVersion\": 1, \"approvalGranted\": true"), ProviderCatalogDraft.class));
    }

    @Test
    void keycloakResearchNativeAndBothUpstreamScopesCoexistWithoutFactTransfer() throws Exception {
        var resources = List.of("keycloak.v1.json", "scoped/keycloak-26.8.0.v1.json",
                "scoped/keycloak-26.8.0-upstream-okta.v1.json", "scoped/keycloak-26.8.0-upstream-entra.v1.json");
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : resources) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class)
                    .options().getFirst());
        }
        assertEquals(4, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.getFirst().facts().values().stream()
                .allMatch(fact -> fact.availability() == ProviderCatalog.Availability.UNKNOWN));
        assertTrue(options.get(1).facts().values().stream()
                .allMatch(fact -> fact.availability() == ProviderCatalog.Availability.OPTIONAL));
        assertFalse(options.get(1).facts().containsKey(ProviderCatalog.Capability.JIT));
        assertTrue(options.subList(2, 4).stream().allMatch(option ->
                option.facts().get(ProviderCatalog.Capability.SCIM).availability() == ProviderCatalog.Availability.UNKNOWN
                && option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).availability() == ProviderCatalog.Availability.UNKNOWN));
        var combined = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "keycloak-research-native-and-upstream-test", options);
        var report = validator.validateAt(combined, Instant.parse("2026-10-08T04:57:09Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.optionCount());
        assertEquals(15, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"okta", "entra"})
    void workosWorkforceDirectoriesDoNotVerifyLoginEntitlementOrLifecycleEnforcement(String upstream) throws Exception {
        var json = resource("catalog/baselines/scoped/workos-directory-sync-staging-upstream-" + upstream + ".v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("workos-directory-sync-staging-upstream-" + upstream + "-workforce", option.id());
        assertEquals("workos", option.providerId());
        assertEquals("WorkOS Directory Sync", option.product());
        assertEquals("Staging; upstream workforce provisioning entitlement unverified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.SCIM, ProviderCatalog.Capability.GROUP_SYNC),
                option.facts().keySet());
        var observed = Instant.parse("2026-10-08T05:14:26Z");
        option.facts().values().forEach(fact -> {
            assertEquals(ProviderCatalog.Availability.OPTIONAL, fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("workos.com", fact.evidence().sourceUrl().getHost());
            assertEquals("/docs/integrations/" + (upstream.equals("okta") ? "okta-scim" : "entra-id-scim"),
                    fact.evidence().sourceUrl().getPath());
            assertFalse(fact.conditions().isEmpty());
        });
        var scim = String.join(" ", option.facts().get(ProviderCatalog.Capability.SCIM).conditions());
        var groups = String.join(" ", option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).conditions());
        assertTrue(scim.contains("OAuth client credentials are outside"));
        assertTrue(scim.contains("Upstream provisioning entitlement remains unverified"));
        assertTrue(scim.contains("not a native SaaS SCIM endpoint or write-back path"));
        assertTrue(scim.contains("persisted cursor"));
        assertTrue(groups.contains("without expecting per-member dsync.group.user_removed"));
        assertTrue(groups.contains("not nested groups"));
        assertTrue(groups.contains("measured revocation latency"));
        if (upstream.equals("okta")) {
            assertTrue(scim.contains("suspension alone does not deactivate"));
            assertTrue(groups.contains("assignment alone is not Group Push"));
            assertTrue(groups.contains("display names populate WorkOS idp_id"));
        } else {
            assertTrue(scim.contains("map objectId to externalId"));
            assertTrue(scim.contains("only assigned users/groups"));
            assertTrue(scim.contains("not Entra External ID, B2C or a Graph pull connector"));
            assertTrue(groups.contains("Restart Provisioning"));
            assertTrue(groups.contains("unlike Okta's display-name identifier"));
        }
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
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"schemaVersion\": 1", "\"schemaVersion\": 1, \"approvalGranted\": true"), ProviderCatalogDraft.class));
    }

    @Test
    void workosResearchGenericAndBothWorkforceDirectoriesCoexistWithoutClaimInheritance() throws Exception {
        var resources = List.of("workos.v1.json", "scoped/workos-directory-sync-staging.v1.json",
                "scoped/workos-directory-sync-staging-upstream-okta.v1.json",
                "scoped/workos-directory-sync-staging-upstream-entra.v1.json");
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : resources) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class)
                    .options().getFirst());
        }
        assertEquals(4, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.getFirst().facts().values().stream()
                .allMatch(fact -> fact.availability() == ProviderCatalog.Availability.UNKNOWN));
        assertEquals(OBSERVED, options.getFirst().facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(Instant.parse("2026-10-02T22:46:13Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertTrue(options.subList(1, 4).stream().allMatch(option -> option.facts().size() == 2
                && !option.facts().containsKey(ProviderCatalog.Capability.ENTERPRISE_SSO)
                && !option.facts().containsKey(ProviderCatalog.Capability.JIT)));
        var combined = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "workos-research-generic-and-upstream-test", options);
        var report = validator.validateAt(combined, Instant.parse("2026-10-08T05:14:26Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.optionCount());
        assertEquals(9, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"okta", "entra"})
    void externalIdWorkforceFederationDoesNotEstablishProvisioningOrTransferredAssurance(String upstream) throws Exception {
        var json = resource("catalog/baselines/scoped/entra-external-id-basic-upstream-" + upstream + ".v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("entra-external-id-basic-upstream-" + upstream + "-workforce", option.id());
        assertEquals("entra-external-id", option.providerId());
        assertEquals("Microsoft Entra External ID - external tenant", option.product());
        assertEquals("Basic MAU; upstream workforce entitlement unverified", option.plan());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.ENTERPRISE_SSO, ProviderCatalog.Capability.JIT,
                ProviderCatalog.Capability.SCIM, ProviderCatalog.Capability.GROUP_SYNC), option.facts().keySet());
        var observed = Instant.parse("2026-10-08T05:32:34Z");
        option.facts().forEach((capability, fact) -> {
            assertEquals(capability == ProviderCatalog.Capability.ENTERPRISE_SSO || capability == ProviderCatalog.Capability.JIT
                    ? ProviderCatalog.Availability.OPTIONAL : ProviderCatalog.Availability.UNKNOWN, fact.availability());
            assertEquals(observed, fact.evidence().observedAt());
            assertEquals("learn.microsoft.com", fact.evidence().sourceUrl().getHost());
            assertFalse(fact.conditions().isEmpty());
        });
        assertEquals("/en-us/entra/external-id/customers/how-to-"
                + (upstream.equals("okta") ? "custom-oidc" : "entra-id") + "-federation-customers",
                option.facts().get(ProviderCatalog.Capability.ENTERPRISE_SSO).evidence().sourceUrl().getPath());
        var sso = String.join(" ", option.facts().get(ProviderCatalog.Capability.ENTERPRISE_SSO).conditions());
        var jit = String.join(" ", option.facts().get(ProviderCatalog.Capability.JIT).conditions());
        var scim = String.join(" ", option.facts().get(ProviderCatalog.Capability.SCIM).conditions());
        var groups = String.join(" ", option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).conditions());
        assertTrue(sso.contains("browser-delegated"));
        assertTrue(sso.contains("client_secret_post"));
        assertTrue(sso.contains("client_secret_basic"));
        assertTrue(sso.contains("private_key_jwt"));
        assertTrue(sso.contains("zero-cost guarantee"));
        assertTrue(jit.contains("issuer-bound sub"));
        assertTrue(jit.contains("truthful email_verified"));
        assertTrue(jit.contains("email-only identity merge"));
        assertTrue(scim.contains("P1 and an Azure-linked paid add-on"));
        assertTrue(scim.contains("UNKNOWN is not UNAVAILABLE"));
        assertTrue(groups.contains("OIDC group claims and JIT are not a SCIM Group lifecycle"));
        if (upstream.equals("okta")) {
            assertTrue(sso.contains("not the /oauth2/default custom server"));
            assertTrue(sso.contains("not a vendor-certified or tested Okta connector"));
            assertTrue(jit.contains("repeat-login updates have not been tested"));
            assertTrue(groups.contains("Okta Group Push"));
        } else {
            assertTrue(sso.contains("organizations/v2.0 discovery"));
            assertTrue(sso.contains("not common, consumers, a multi-tenant issuer or domain_hint"));
            assertTrue(sso.contains("MFA is not automatically trusted"));
            assertTrue(jit.contains("Graph user-creation alternative is not selected"));
            assertTrue(jit.contains("Do not borrow Auth0's oid choice, WorkOS objectId/externalId"));
            assertTrue(jit.contains("workforce guide requires email while the generic guide"));
        }
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
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"schemaVersion\": 1", "\"schemaVersion\": 1, \"approvalGranted\": true"), ProviderCatalogDraft.class));
    }

    @Test
    void externalIdResearchNativeAndBothWorkforcePairsCoexistWithoutDownstreamFactTransfer() throws Exception {
        var resources = List.of("entra-external-id.v1.json", "scoped/entra-external-id-basic.v1.json",
                "scoped/entra-external-id-basic-upstream-okta.v1.json",
                "scoped/entra-external-id-basic-upstream-entra.v1.json");
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : resources) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class)
                    .options().getFirst());
        }
        assertEquals(4, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.getFirst().facts().values().stream()
                .allMatch(fact -> fact.availability() == ProviderCatalog.Availability.UNKNOWN));
        assertEquals(OBSERVED, options.getFirst().facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(Instant.parse("2026-10-02T23:12:01Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        assertFalse(options.get(1).facts().containsKey(ProviderCatalog.Capability.JIT));
        assertTrue(options.subList(2, 4).stream().allMatch(option ->
                !option.facts().containsKey(ProviderCatalog.Capability.OIDC)
                && !option.facts().containsKey(ProviderCatalog.Capability.SAML)
                && option.facts().get(ProviderCatalog.Capability.JIT).availability() == ProviderCatalog.Availability.OPTIONAL
                && option.facts().get(ProviderCatalog.Capability.SCIM).availability() == ProviderCatalog.Availability.UNKNOWN
                && option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).availability() == ProviderCatalog.Availability.UNKNOWN));
        var combined = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "external-id-research-native-and-upstream-test", options);
        var report = validator.validateAt(combined, Instant.parse("2026-10-08T05:32:34Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.optionCount());
        assertEquals(15, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
    }

    @Test
    void allBaselineScopesPartitionTheTypedAddressVocabularyWithoutClaimingRequirementCoverage() throws Exception {
        var resources = new java.util.ArrayList<String>();
        HOSTS.keySet().stream().sorted().forEach(provider -> resources.add(provider + ".v1.json"));
        for (var stem : List.of("keycloak-26.8.0", "zitadel-cloud-free", "auth0-b2b-free",
                "workos-directory-sync-staging", "entra-external-id-basic")) {
            resources.add("scoped/" + stem + ".v1.json");
            for (var upstream : List.of("okta", "entra")) {
                resources.add("scoped/" + stem + "-upstream-" + upstream + ".v1.json");
            }
        }
        resources.add("scoped/keycloak-26.8.0-public-oidc-clients.v1.json");
        resources.add("scoped/zitadel-cloud-free-public-oidc-clients.v1.json");
        resources.add("scoped/auth0-b2b-free-public-oidc-clients.v1.json");
        resources.add("scoped/entra-external-id-basic-public-oidc-clients.v1.json");
        resources.add("scoped/workos-connect-staging-public-oidc-clients.v1.json");
        resources.add("scoped/zitadel-cloud-free-organization-context.v1.json");
        var vocabulary = CatalogFactPathRegressionCases.PROBES.stream()
                .map(probe -> probe.factPath()).collect(java.util.stream.Collectors.toSet());
        assertEquals(68, vocabulary.size());
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        var counts = new java.util.EnumMap<ProviderCatalog.Availability, Integer>(ProviderCatalog.Availability.class);
        var supportCounts = new java.util.EnumMap<ProviderCatalog.Support, Integer>(ProviderCatalog.Support.class);
        int recordedCount = 0;
        int omittedCount = 0;
        for (var file : resources) {
            var option = mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class)
                    .options().getFirst();
            options.add(option);
            var recorded = CatalogDraftFacts.entries(option).keySet();
            assertTrue(vocabulary.containsAll(recorded), file);
            assertTrue(recorded.stream().allMatch(address -> address.startsWith("facts.")
                    || address.equals("compatibility.clients.BROWSER")
                    || address.equals("compatibility.clients.NATIVE_MOBILE")
                    || address.equals("compatibility.applications.B2B_SAAS")
                    || address.equals("compatibility.applications.PARTNER_PORTAL")
                    || address.equals("compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS")
                    || address.equals("compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER")), file);
            var omitted = new java.util.HashSet<>(vocabulary);
            omitted.removeAll(recorded);
            assertTrue(java.util.Collections.disjoint(recorded, omitted), file);
            assertEquals(68, recorded.size() + omitted.size(), file);
            recordedCount += recorded.size();
            omittedCount += omitted.size();
            option.facts().values().forEach(fact -> counts.merge(fact.availability(), 1, Integer::sum));
            CatalogDraftFacts.entries(option).values().stream().filter(ProviderCatalogDraft.CompatibilityFact.class::isInstance)
                    .map(ProviderCatalogDraft.CompatibilityFact.class::cast)
                    .forEach(fact -> supportCounts.merge(fact.support(), 1, Integer::sum));
        }
        assertEquals(26, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertEquals(86, recordedCount);
        assertEquals(1682, omittedCount);
        assertEquals(Map.of(ProviderCatalog.Availability.OPTIONAL, 36,
                ProviderCatalog.Availability.UNAVAILABLE, 3, ProviderCatalog.Availability.UNKNOWN, 33), counts);
        assertEquals(Map.of(ProviderCatalog.Support.SUPPORTED, 13, ProviderCatalog.Support.UNKNOWN, 1), supportCounts);
        var draft = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "baseline-address-inventory-test", options);
        var report = validator.validateAt(draft, Instant.parse("2026-10-08T14:20:41Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(26, report.optionCount());
        assertEquals(86, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == CatalogDraftValidation.Freshness.CURRENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BROWSER", "NATIVE_MOBILE"})
    void publicOidcClientCompatibilityIsTypedScopedAndNeverReviewed(String client) throws Exception {
        var json = resource("catalog/baselines/scoped/keycloak-26.8.0-public-oidc-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("keycloak-26.8.0-public-oidc-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("keycloak-26.8.0-public-oidc-clients", option.id());
        assertEquals(ProviderCatalogDraft.Deployment.SELF_HOSTED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), option.facts().keySet());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, option.facts().get(ProviderCatalog.Capability.OIDC).availability());
        var fact = option.compatibility().clients().get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.valueOf(client));
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertFalse(fact.conditions().isEmpty());
        assertEquals("github.com", fact.evidence().sourceUrl().getHost());
        assertEquals("/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/"
                + "docs/guides/securing-apps/partials/oidc/supported-grant-types.adoc", fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T06:12:22Z"), fact.evidence().observedAt());
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, fact.evidence().observedAt());
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(3, report.factCount());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(entry -> entry.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void publicClientObservationsKeepFreshnessSeparateFromProposedSupportAndAuthority() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/keycloak-26.8.0-public-oidc-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T06:12:22Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var expected = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == expected));
            assertUntrusted(report);
        }
        assertTrue(draft.options().getFirst().compatibility().clients().values().stream()
                .allMatch(fact -> fact.support() == ProviderCatalog.Support.SUPPORTED
                        && fact.evidence().observedAt().equals(observed)));
    }

    @Test
    void publicClientsDoNotPopulateOtherKeycloakScopesOrBorrowScimFacts() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("keycloak.v1.json", "scoped/keycloak-26.8.0.v1.json",
                "scoped/keycloak-26.8.0-upstream-okta.v1.json", "scoped/keycloak-26.8.0-upstream-entra.v1.json",
                "scoped/keycloak-26.8.0-public-oidc-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(5, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().clients().isEmpty()));
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.getLast().facts().keySet());
        assertEquals(OBSERVED, options.getFirst().facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "keycloak-client-context-coexistence-test", options), Instant.parse("2026-10-08T06:12:22Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(5, report.optionCount());
        assertEquals(18, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BROWSER", "NATIVE_MOBILE"})
    void cloudPublicClientsAreTypedWithoutBorrowingSelfHostedOrNativeScimClaims(String client) throws Exception {
        var json = resource("catalog/baselines/scoped/zitadel-cloud-free-public-oidc-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("zitadel-cloud-free-public-oidc-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("zitadel-cloud-free-public-oidc-clients", option.id());
        assertEquals("zitadel", option.providerId());
        assertEquals("ZITADEL Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Free; documented offer, no account entitlement verified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), option.facts().keySet());
        var fact = option.compatibility().clients().get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.valueOf(client));
        assertNotNull(fact);
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertEquals("zitadel.com", fact.evidence().sourceUrl().getHost());
        assertEquals(client.equals("BROWSER") ? "/docs/guides/manage/console/applications-overview"
                : "/docs/guides/integrate/login/oidc/oauth-recommended-flows", fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T06:37:37Z"), fact.evidence().observedAt());
        assertTrue(option.facts().get(ProviderCatalog.Capability.OIDC).conditions().stream()
                .anyMatch(condition -> condition.contains("mutable and dated, not release-pinned")));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, fact.evidence().observedAt());
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(3, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(entry -> entry.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void cloudClientFreshnessDoesNotRefreshDatesOrConferAuthority() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/zitadel-cloud-free-public-oidc-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T06:37:37Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var expected = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == expected));
            assertUntrusted(report);
        }
        assertTrue(draft.options().getFirst().compatibility().clients().values().stream()
                .allMatch(fact -> fact.support() == ProviderCatalog.Support.SUPPORTED
                        && fact.evidence().observedAt().equals(observed)));
    }

    @Test
    void cloudClientsKeepResearchNativeAndWorkforceScopesSeparate() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("zitadel.v1.json", "scoped/zitadel-cloud-free.v1.json",
                "scoped/zitadel-cloud-free-upstream-okta.v1.json", "scoped/zitadel-cloud-free-upstream-entra.v1.json",
                "scoped/zitadel-cloud-free-public-oidc-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(5, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().clients().isEmpty()));
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.getLast().facts().keySet());
        assertEquals(ProviderCatalog.Availability.UNKNOWN, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(OBSERVED, options.getFirst().facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "zitadel-client-context-coexistence-test", options), Instant.parse("2026-10-08T06:37:37Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(5, report.optionCount());
        assertEquals(18, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BROWSER", "NATIVE_MOBILE"})
    void auth0PublicClientsKeepFirstPartyScopeAndUnverifiedCallbackControls(String client) throws Exception {
        var json = resource("catalog/baselines/scoped/auth0-b2b-free-public-oidc-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("auth0-b2b-free-public-oidc-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("auth0-b2b-free-public-oidc-clients", option.id());
        assertEquals("auth0", option.providerId());
        assertEquals("Auth0 Public Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("B2B Free; documented offer, no account entitlement verified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), option.facts().keySet());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, option.facts().get(ProviderCatalog.Capability.OIDC).availability());
        var fact = option.compatibility().clients().get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.valueOf(client));
        assertNotNull(fact);
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertEquals("auth0.com", fact.evidence().sourceUrl().getHost());
        assertEquals(client.equals("BROWSER") ? "/docs/get-started/auth0-overview/create-applications/single-page-web-apps"
                : "/docs/secure/security-guidance/measures-against-app-impersonation", fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T06:56:20Z"), fact.evidence().observedAt());
        assertTrue(option.facts().get(ProviderCatalog.Capability.OIDC).conditions().stream()
                .anyMatch(condition -> condition.contains("token_endpoint_auth_method none")));
        if (client.equals("NATIVE_MOBILE")) {
            assertTrue(fact.conditions().stream().anyMatch(condition -> condition.contains("PKCE alone does not prevent")));
            assertTrue(fact.conditions().stream().anyMatch(condition -> condition.contains("retain end-user confirmation")));
        }
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, fact.evidence().observedAt());
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(3, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(entry -> entry.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void auth0PublicClientFreshnessDoesNotRefreshEvidenceOrPromoteSupport() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/auth0-b2b-free-public-oidc-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T06:56:20Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var expected = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == expected));
            assertUntrusted(report);
        }
        assertTrue(draft.options().getFirst().compatibility().clients().values().stream()
                .allMatch(fact -> fact.support() == ProviderCatalog.Support.SUPPORTED
                        && fact.evidence().observedAt().equals(observed)));
    }

    @Test
    void auth0ClientsDoNotInheritNativeScimOrWorkforceBrokerClaims() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("auth0.v1.json", "scoped/auth0-b2b-free.v1.json",
                "scoped/auth0-b2b-free-upstream-okta.v1.json", "scoped/auth0-b2b-free-upstream-entra.v1.json",
                "scoped/auth0-b2b-free-public-oidc-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(5, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().clients().isEmpty()));
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.getLast().facts().keySet());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(OBSERVED, options.getFirst().facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "auth0-client-context-coexistence-test", options), Instant.parse("2026-10-08T06:56:20Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(5, report.optionCount());
        assertEquals(16, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BROWSER", "NATIVE_MOBILE"})
    void entraPublicClientsUseTypedExternalTenantContextWithoutNativeAuthOrProvisioningClaims(String client) throws Exception {
        var json = resource("catalog/baselines/scoped/entra-external-id-basic-public-oidc-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("entra-external-id-basic-public-oidc-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("entra-external-id-basic-public-oidc-clients", option.id());
        assertEquals("entra-external-id", option.providerId());
        assertEquals("Microsoft Entra External ID - external tenant", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Basic MAU; documented free allowance, no tenant entitlement verified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), option.facts().keySet());
        var oidc = option.facts().get(ProviderCatalog.Capability.OIDC);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, oidc.availability());
        assertTrue(String.join(" ", oidc.conditions()).contains("not a claim of S256-only server enforcement"));
        assertTrue(String.join(" ", oidc.conditions()).contains("one intended sign-up/sign-in user flow"));
        var fact = option.compatibility().clients().get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.valueOf(client));
        assertNotNull(fact);
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertEquals("learn.microsoft.com", fact.evidence().sourceUrl().getHost());
        assertEquals(client.equals("BROWSER")
                ? "/en-us/entra/identity-platform/tutorial-single-page-app-javascript-prepare-app"
                : "/en-us/entra/identity-platform/quickstart-mobile-app-call-api", fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T07:14:50Z"), fact.evidence().observedAt());
        var conditions = String.join(" ", fact.conditions());
        if (client.equals("BROWSER")) {
            assertTrue(conditions.contains("platform type spa"));
            assertTrue(conditions.contains("not BFF/session isolation"));
        } else {
            assertTrue(conditions.contains("Microsoft native authentication is a separate approach"));
            assertTrue(conditions.contains("Reconcile those prerequisites"));
            assertTrue(conditions.contains("not required for browser-delegated authentication"));
            assertTrue(conditions.contains("PKCE is not proof of callback ownership"));
        }
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, fact.evidence().observedAt());
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(3, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(entry -> entry.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void entraPublicClientFreshnessRetainsEvidenceHashesAndUnreviewedSupport() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/entra-external-id-basic-public-oidc-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T07:14:50Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var expected = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == expected));
            assertUntrusted(report);
        }
        assertTrue(draft.options().getFirst().compatibility().clients().values().stream()
                .allMatch(fact -> fact.support() == ProviderCatalog.Support.SUPPORTED
                        && fact.evidence().observedAt().equals(observed)));
    }

    @Test
    void entraPublicClientsKeepResearchNativeAndUpstreamScopesUnchangedAndSeparate() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("entra-external-id.v1.json", "scoped/entra-external-id-basic.v1.json",
                "scoped/entra-external-id-basic-upstream-okta.v1.json", "scoped/entra-external-id-basic-upstream-entra.v1.json",
                "scoped/entra-external-id-basic-public-oidc-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(5, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().clients().isEmpty()));
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.getLast().facts().keySet());
        assertEquals(ProviderCatalog.Availability.UNKNOWN, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, options.get(1).facts().get(ProviderCatalog.Capability.SAML).availability());
        assertEquals(OBSERVED, options.getFirst().facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        assertEquals(Instant.parse("2026-10-02T23:12:01Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "entra-public-client-context-coexistence-test", options), Instant.parse("2026-10-08T07:14:50Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(5, report.optionCount());
        assertEquals(18, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BROWSER", "NATIVE_MOBILE"})
    void workosConnectPreservesUnknownBrowserAndConditionalMobileWithoutDirectorySyncClaims(String client) throws Exception {
        var json = resource("catalog/baselines/scoped/workos-connect-staging-public-oidc-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("workos-connect-staging-public-oidc-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("workos-connect-staging-public-oidc-clients", option.id());
        assertEquals("workos", option.providerId());
        assertEquals("WorkOS AuthKit Connect", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Staging only; production entitlement and billing unverified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), option.facts().keySet());
        var oidc = option.facts().get(ProviderCatalog.Capability.OIDC);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, oidc.availability());
        assertEquals("/docs/reference/workos-connect/metadata", oidc.evidence().sourceUrl().getPath());
        assertTrue(String.join(" ", oidc.conditions()).contains("Reconcile the public-client exchange contract before source approval"));
        assertTrue(String.join(" ", oidc.conditions()).contains("free staging is not production Connect entitlement"));
        var fact = option.compatibility().clients().get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.valueOf(client));
        assertNotNull(fact);
        assertEquals(client.equals("BROWSER") ? ProviderCatalog.Support.UNKNOWN : ProviderCatalog.Support.SUPPORTED,
                fact.support());
        assertEquals("workos.com", fact.evidence().sourceUrl().getHost());
        assertEquals(client.equals("BROWSER") ? "/docs/reference/workos-connect/token" : "/docs/authkit/connect/oauth",
                fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T13:50:08Z"), fact.evidence().observedAt());
        var conditions = String.join(" ", fact.conditions());
        if (client.equals("BROWSER")) {
            assertTrue(conditions.contains("not established by the reviewed sources"));
            assertTrue(conditions.contains("not UNSUPPORTED or an omitted fact"));
            assertTrue(conditions.contains("primary AuthKit React/CORS"));
        } else {
            assertTrue(conditions.contains("external user-agent under RFC 8252"));
            assertTrue(conditions.contains("PKCE is not proof of callback ownership"));
            assertTrue(conditions.contains("primary AuthKit session tokens and Directory Sync"));
        }
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, fact.evidence().observedAt());
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(3, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(entry -> entry.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"UNKNOWN\"", "\"support\": \"UNKNOWN\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void workosConnectFreshnessRetainsEvidenceHashesAndDoesNotPromoteBrowserUncertainty() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/workos-connect-staging-public-oidc-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T13:50:08Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var expected = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == expected));
            assertUntrusted(report);
        }
        var clients = draft.options().getFirst().compatibility().clients();
        assertEquals(ProviderCatalog.Support.UNKNOWN, clients.get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER).support());
        assertEquals(ProviderCatalog.Support.SUPPORTED, clients.get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.NATIVE_MOBILE).support());
        assertTrue(clients.values().stream().allMatch(fact -> fact.evidence().observedAt().equals(observed)));
    }

    @Test
    void workosConnectKeepsResearchAndDirectorySyncScopesUnchangedWithoutProvisioningInheritance() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("workos.v1.json", "scoped/workos-directory-sync-staging.v1.json",
                "scoped/workos-directory-sync-staging-upstream-okta.v1.json",
                "scoped/workos-directory-sync-staging-upstream-entra.v1.json",
                "scoped/workos-connect-staging-public-oidc-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(5, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().clients().isEmpty()));
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.getLast().facts().keySet());
        for (var directory : options.subList(1, 4)) {
            assertEquals(java.util.Set.of(ProviderCatalog.Capability.SCIM, ProviderCatalog.Capability.GROUP_SYNC), directory.facts().keySet());
            assertTrue(directory.facts().values().stream().allMatch(fact -> fact.availability() == ProviderCatalog.Availability.OPTIONAL));
        }
        assertEquals(OBSERVED, options.getFirst().facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "workos-connect-client-context-coexistence-test", options), Instant.parse("2026-10-08T13:50:08Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(5, report.optionCount());
        assertEquals(12, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
            "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER"})
    void zitadelOrganizationContextUsesTypedCompatibilityWithoutProtocolOrControlInheritance(String path) throws Exception {
        var json = resource("catalog/baselines/scoped/zitadel-cloud-free-organization-context.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("zitadel-cloud-free-organization-context-draft-2026.10.08", draft.catalogVersion());
        assertEquals("zitadel-cloud-free-organization-context", option.id());
        assertEquals("zitadel", option.providerId());
        assertEquals("ZITADEL Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Free; organization offer documented, account entitlement unverified", option.plan());
        assertTrue(option.facts().isEmpty());
        var entries = CatalogDraftFacts.entries(option);
        assertEquals(4, entries.size());
        var fact = assertInstanceOf(ProviderCatalogDraft.CompatibilityFact.class, entries.get(path));
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertEquals("zitadel.com", fact.evidence().sourceUrl().getHost());
        var sourcePaths = Map.of(
                "compatibility.applications.B2B_SAAS", "/docs/guides/solution-scenarios/b2b",
                "compatibility.applications.PARTNER_PORTAL", "/docs/examples/login/nextjs-b2b",
                "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "/docs/guides/manage/console/organizations-overview",
                "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "/docs/concepts/features/external-user-grant");
        assertEquals(sourcePaths.keySet(), entries.keySet());
        assertEquals(sourcePaths.get(path), fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T14:20:41Z"), fact.evidence().observedAt());
        var conditions = String.join(" ", fact.conditions());
        switch (path) {
            case "compatibility.applications.B2B_SAAS" -> {
                assertTrue(conditions.contains("IAM manager roles are separate"));
                assertTrue(conditions.contains("not unlimited usage"));
            }
            case "compatibility.applications.PARTNER_PORTAL" -> {
                assertTrue(conditions.contains("not a public SPA/mobile compatibility proof"));
                assertTrue(conditions.contains("not secure production defaults"));
            }
            case "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS" -> {
                assertTrue(conditions.contains("routing alone does not authorize the selected application tenant"));
                assertTrue(conditions.contains("IAM data separation does not prove isolation"));
            }
            case "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER" -> {
                assertTrue(conditions.contains("one home organization"));
                assertTrue(conditions.contains("do not merge identities by email"));
                assertTrue(conditions.contains("existing local sessions need separate enforcement tests"));
            }
            default -> fail("Unexpected organization-context path");
        }
        assertTrue(option.compatibility().clients().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, fact.evidence().observedAt());
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertTrue(report.facts().stream().allMatch(entry -> entry.freshness() == CatalogDraftValidation.Freshness.CURRENT));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void zitadelOrganizationFreshnessRetainsHashAndDoesNotTurnContextIntoApproval() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/zitadel-cloud-free-organization-context.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T14:20:41Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var expected = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == expected));
            assertUntrusted(report);
        }
        assertTrue(CatalogDraftFacts.entries(draft.options().getFirst()).values().stream()
                .allMatch(fact -> fact.evidence().observedAt().equals(observed)));
    }

    @Test
    void zitadelOrganizationScopeKeepsProtocolClientAndWorkforceDraftsIndependent() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("zitadel.v1.json", "scoped/zitadel-cloud-free.v1.json",
                "scoped/zitadel-cloud-free-upstream-okta.v1.json", "scoped/zitadel-cloud-free-upstream-entra.v1.json",
                "scoped/zitadel-cloud-free-public-oidc-clients.v1.json", "scoped/zitadel-cloud-free-organization-context.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(6, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().applications().isEmpty()
                && option.compatibility().tenancy().isEmpty() && option.compatibility().membership().isEmpty()));
        assertTrue(options.getLast().facts().isEmpty());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertEquals(ProviderCatalog.Availability.UNKNOWN, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(ProviderCatalog.Availability.UNAVAILABLE, options.get(1).facts().get(ProviderCatalog.Capability.GROUP_SYNC).availability());
        assertEquals(Instant.parse("2026-10-02T21:44:10Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.get(4).facts().keySet());
        assertEquals(2, options.get(4).compatibility().clients().size());
        assertTrue(options.get(4).compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "zitadel-organization-context-coexistence-test", options), Instant.parse("2026-10-08T14:20:41Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(6, report.optionCount());
        assertEquals(22, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
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
