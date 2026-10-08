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
        resources.add("scoped/auth0-b2b-free-organization-context.v1.json");
        resources.add("scoped/workos-authkit-staging-organization-context.v1.json");
        resources.add("scoped/keycloak-26.8.0-organization-context.v1.json");
        resources.add("scoped/entra-external-id-basic-organization-context.v1.json");
        resources.add("scoped/keycloak-26.8.0-machine-clients.v1.json");
        resources.add("scoped/zitadel-cloud-free-machine-clients.v1.json");
        resources.add("scoped/auth0-b2b-free-machine-clients.v1.json");
        resources.add("scoped/workos-connect-staging-machine-clients.v1.json");
        resources.add("scoped/entra-external-id-m2m-addon-machine-clients.v1.json");
        resources.add("scoped/keycloak-26.8.0-browser-authentication-controls.v1.json");
        resources.add("scoped/zitadel-cloud-free-browser-authentication-controls.v1.json");
        resources.add("scoped/auth0-b2b-free-browser-authentication-controls.v1.json");
        resources.add("scoped/workos-authkit-staging-browser-authentication-controls.v1.json");
        resources.add("scoped/entra-external-id-basic-browser-authentication-controls.v1.json");
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
                    || address.equals("compatibility.clients.MACHINE_TO_MACHINE")
                    || address.equals("compatibility.applications.B2B_SAAS")
                    || address.equals("compatibility.applications.PARTNER_PORTAL")
                    || address.equals("compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS")
                    || address.equals("compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER")
                    || address.equals("authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE")
                    || address.equals("authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS")
                    || address.equals("authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION")), file);
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
        assertEquals(40, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertEquals(132, recordedCount);
        assertEquals(2588, omittedCount);
        assertEquals(Map.of(ProviderCatalog.Availability.OPTIONAL, 45,
                ProviderCatalog.Availability.UNAVAILABLE, 3, ProviderCatalog.Availability.UNKNOWN, 34), counts);
        assertEquals(Map.of(ProviderCatalog.Support.SUPPORTED, 31, ProviderCatalog.Support.UNKNOWN, 4), supportCounts);
        var draft = new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "baseline-address-inventory-test", options);
        var report = validator.validateAt(draft, Instant.parse("2026-10-08T21:34:08Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(40, report.optionCount());
        assertEquals(132, report.factCount());
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

    @ParameterizedTest
    @ValueSource(strings = {"compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
            "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER"})
    void auth0OrganizationContextUsesTypedMembershipWithoutBorrowingFreeRoleOrScimEntitlement(String path) throws Exception {
        var json = resource("catalog/baselines/scoped/auth0-b2b-free-organization-context.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("auth0-b2b-free-organization-context-draft-2026.10.08", draft.catalogVersion());
        assertEquals("auth0-b2b-free-organization-context", option.id());
        assertEquals("auth0", option.providerId());
        assertEquals("Auth0 Public Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("B2B Free; bounded Organizations offer, account entitlement unverified", option.plan());
        assertTrue(option.facts().isEmpty());
        var entries = CatalogDraftFacts.entries(option);
        assertEquals(4, entries.size());
        var fact = assertInstanceOf(ProviderCatalogDraft.CompatibilityFact.class, entries.get(path));
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertEquals("auth0.com", fact.evidence().sourceUrl().getHost());
        var sourcePaths = Map.of(
                "compatibility.applications.B2B_SAAS", "/docs/manage-users/organizations/organizations-overview",
                "compatibility.applications.PARTNER_PORTAL", "/docs/manage-users/organizations",
                "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "/docs/manage-users/organizations/using-tokens",
                "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "/docs/api/management/v2/organizations/get-organization-member-roles");
        assertEquals(sourcePaths.keySet(), entries.keySet());
        assertEquals(sourcePaths.get(path), fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T14:43:27Z"), fact.evidence().observedAt());
        var conditions = String.join(" ", fact.conditions());
        switch (path) {
            case "compatibility.applications.B2B_SAAS" -> {
                assertTrue(conditions.contains("Require Universal Login"));
                assertTrue(conditions.contains("five Organizations, not unlimited customer capacity"));
                assertTrue(conditions.contains("neither a trial nor paid RBAC per Organization is assumed"));
            }
            case "compatibility.applications.PARTNER_PORTAL" -> {
                assertTrue(conditions.contains("AuthWeave catalog-curator"));
                assertTrue(conditions.contains("never expose a Management API token to public clients"));
            }
            case "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS" -> {
                assertTrue(conditions.contains("org_id against the authorized resource tenant on every request"));
                assertTrue(conditions.contains("does not establish product entitlement or database isolation"));
            }
            case "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER" -> {
                assertTrue(conditions.contains("same stable tenant user ID"));
                assertTrue(conditions.contains("do not merge users by email"));
                assertTrue(conditions.contains("not evidence of Free-plan RBAC entitlement"));
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
    void auth0OrganizationFreshnessPreservesHashAndNeverGrantsApproval() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/auth0-b2b-free-organization-context.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T14:43:27Z");
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
    }

    @Test
    void auth0OrganizationContextCoexistsWithOriginalProtocolClientAndUpstreamScopes() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("auth0.v1.json", "scoped/auth0-b2b-free.v1.json",
                "scoped/auth0-b2b-free-upstream-okta.v1.json", "scoped/auth0-b2b-free-upstream-entra.v1.json",
                "scoped/auth0-b2b-free-public-oidc-clients.v1.json", "scoped/auth0-b2b-free-organization-context.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(6, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().applications().isEmpty()
                && option.compatibility().tenancy().isEmpty() && option.compatibility().membership().isEmpty()));
        assertTrue(options.getLast().facts().isEmpty());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(ProviderCatalog.Availability.UNKNOWN, options.get(1).facts().get(ProviderCatalog.Capability.GROUP_SYNC).availability());
        assertEquals(Instant.parse("2026-10-02T22:07:48Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.get(4).facts().keySet());
        assertEquals(2, options.get(4).compatibility().clients().size());
        assertTrue(options.get(4).compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "auth0-organization-context-coexistence-test", options), Instant.parse("2026-10-08T14:43:27Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(6, report.optionCount());
        assertEquals(20, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
            "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER"})
    void workosPrimaryOrganizationContextIsTypedWithoutConnectOrDirectorySyncInheritance(String path) throws Exception {
        var json = resource("catalog/baselines/scoped/workos-authkit-staging-organization-context.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("workos-authkit-staging-organization-context-draft-2026.10.08", draft.catalogVersion());
        assertEquals("workos-authkit-staging-organization-context", option.id());
        assertEquals("workos", option.providerId());
        assertEquals("WorkOS AuthKit", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Staging only; organization offer documented, production entitlement unverified", option.plan());
        assertTrue(option.facts().isEmpty());
        var entries = CatalogDraftFacts.entries(option);
        assertEquals(4, entries.size());
        var fact = assertInstanceOf(ProviderCatalogDraft.CompatibilityFact.class, entries.get(path));
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertEquals("workos.com", fact.evidence().sourceUrl().getHost());
        var sourcePaths = Map.of(
                "compatibility.applications.B2B_SAAS", "/docs/authkit/users-organizations",
                "compatibility.applications.PARTNER_PORTAL", "/docs/authkit/invitations",
                "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "/docs/authkit/sessions",
                "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "/docs/reference/authkit/organization-membership");
        assertEquals(sourcePaths.keySet(), entries.keySet());
        assertEquals(sourcePaths.get(path), fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T16:06:12Z"), fact.evidence().observedAt());
        var conditions = String.join(" ", fact.conditions());
        switch (path) {
            case "compatibility.applications.B2B_SAAS" -> {
                assertTrue(conditions.contains("testing-only, not customer-facing production"));
                assertTrue(conditions.contains("not free production SSO or Directory Sync"));
            }
            case "compatibility.applications.PARTNER_PORTAL" -> {
                assertTrue(conditions.contains("another address on the same domain"));
                assertTrue(conditions.contains("Do not assume exact-recipient approval"));
                assertTrue(conditions.contains("AuthWeave catalog-curator authority"));
            }
            case "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS" -> {
                assertTrue(conditions.contains("claims do not prove database isolation"));
                assertTrue(conditions.contains("HTTP JWKS example and issuer spelling"));
                assertTrue(conditions.contains("Do not borrow Connect keys or token semantics"));
            }
            case "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER" -> {
                assertTrue(conditions.contains("stable environment-scoped WorkOS user_id"));
                assertTrue(conditions.contains("pending invitations and inactive memberships are not active access"));
                assertTrue(conditions.contains("Create can reactivate an inactive membership"));
                assertTrue(conditions.contains("cached JWTs and application sessions need separate enforcement tests"));
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
    void workosOrganizationFreshnessRetainsHashWithoutApprovingMembershipOrSessionClaims() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/workos-authkit-staging-organization-context.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T16:06:12Z");
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
    }

    @Test
    void workosOrganizationContextPreservesResearchDirectorySyncAndConnectScopes() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("workos.v1.json", "scoped/workos-directory-sync-staging.v1.json",
                "scoped/workos-directory-sync-staging-upstream-okta.v1.json", "scoped/workos-directory-sync-staging-upstream-entra.v1.json",
                "scoped/workos-connect-staging-public-oidc-clients.v1.json", "scoped/workos-authkit-staging-organization-context.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(6, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().applications().isEmpty()
                && option.compatibility().tenancy().isEmpty() && option.compatibility().membership().isEmpty()));
        assertTrue(options.getLast().facts().isEmpty());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, options.get(1).facts().get(ProviderCatalog.Capability.GROUP_SYNC).availability());
        assertFalse(options.get(1).facts().containsKey(ProviderCatalog.Capability.OIDC));
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.get(4).facts().keySet());
        assertEquals(ProviderCatalog.Support.UNKNOWN, options.get(4).compatibility().clients().get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER).support());
        assertTrue(options.get(4).compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "workos-organization-context-coexistence-test", options), Instant.parse("2026-10-08T16:06:12Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(6, report.optionCount());
        assertEquals(16, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
            "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER"})
    void keycloakOrganizationContextIsReleasePinnedWithoutScimOrResourceAuthorizationInheritance(String path) throws Exception {
        var json = resource("catalog/baselines/scoped/keycloak-26.8.0-organization-context.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("keycloak-26.8.0-organization-context-draft-2026.10.08", draft.catalogVersion());
        assertEquals("keycloak-26.8.0-organization-context", option.id());
        assertEquals("keycloak", option.providerId());
        assertEquals("Keycloak upstream 26.8.0", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.SELF_HOSTED, option.deployment());
        assertEquals("Upstream release 26.8.0; commercial support not assessed", option.plan());
        assertTrue(option.facts().isEmpty());
        var entries = CatalogDraftFacts.entries(option);
        assertEquals(4, entries.size());
        var fact = assertInstanceOf(ProviderCatalogDraft.CompatibilityFact.class, entries.get(path));
        assertEquals(ProviderCatalog.Support.SUPPORTED, fact.support());
        assertEquals("github.com", fact.evidence().sourceUrl().getHost());
        var sourcePaths = Map.of(
                "compatibility.applications.B2B_SAAS", "intro.adoc",
                "compatibility.applications.PARTNER_PORTAL", "managing-members.adoc",
                "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "mapping-organization-claims.adoc",
                "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "managing-members.adoc");
        assertEquals(sourcePaths.keySet(), entries.keySet());
        assertEquals("/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/organizations/"
                + sourcePaths.get(path), fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T16:26:23Z"), fact.evidence().observedAt());
        var conditions = String.join(" ", fact.conditions());
        switch (path) {
            case "compatibility.applications.B2B_SAAS" -> {
                assertTrue(conditions.contains("one realm with Organizations enabled"));
                assertTrue(conditions.contains("not a realm-per-customer architecture, independent issuers"));
                assertTrue(conditions.contains("email domain is not authorization"));
                assertTrue(conditions.contains("operating cost or commercial support"));
            }
            case "compatibility.applications.PARTNER_PORTAL" -> {
                assertTrue(conditions.contains("new account must use the invited email address"));
                assertTrue(conditions.contains("not a durable acceptance audit"));
                assertTrue(conditions.contains("API examples use /orgs"));
                assertTrue(conditions.contains("AuthWeave catalog-curator authority"));
            }
            case "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS" -> {
                assertTrue(conditions.contains("Organization id and attributes are omitted by default"));
                assertTrue(conditions.contains("mixed scope formats are rejected"));
                assertTrue(conditions.contains("multi-organization claim is not a single active tenant"));
                assertTrue(conditions.contains("claims do not prove database isolation"));
            }
            case "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER" -> {
                assertTrue(conditions.contains("Select unmanaged memberships"));
                assertTrue(conditions.contains("removing a managed membership or organization deletes the managed realm account"));
                assertTrue(conditions.contains("Disabled organizations do not necessarily disable unmanaged realm users"));
                assertTrue(conditions.contains("LDAP users with import mode disabled cannot join organizations"));
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
    void keycloakOrganizationFreshnessKeepsContentHashWithoutGrantingReview() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/keycloak-26.8.0-organization-context.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T16:26:23Z");
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
    }

    @Test
    void keycloakOrganizationScopeKeepsResearchNativeScimPublicClientAndWorkforceOptionsIndependent() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("keycloak.v1.json", "scoped/keycloak-26.8.0.v1.json",
                "scoped/keycloak-26.8.0-upstream-okta.v1.json", "scoped/keycloak-26.8.0-upstream-entra.v1.json",
                "scoped/keycloak-26.8.0-public-oidc-clients.v1.json", "scoped/keycloak-26.8.0-organization-context.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(6, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().applications().isEmpty()
                && option.compatibility().tenancy().isEmpty() && option.compatibility().membership().isEmpty()));
        assertTrue(options.getLast().facts().isEmpty());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(Instant.parse("2026-10-02T21:20:39Z"), options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        for (var option : options.subList(2, 4)) {
            assertEquals(ProviderCatalog.Availability.UNKNOWN, option.facts().get(ProviderCatalog.Capability.SCIM).availability());
        }
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.get(4).facts().keySet());
        assertTrue(options.get(4).compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "keycloak-organization-context-coexistence-test", options), Instant.parse("2026-10-08T16:26:23Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(6, report.optionCount());
        assertEquals(22, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
            "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER"})
    void entraOrganizationContextKeepsCustomerLoginDistinctFromUnknownAdmissionAndMembership(String path) throws Exception {
        var json = resource("catalog/baselines/scoped/entra-external-id-basic-organization-context.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("entra-external-id-basic-organization-context-draft-2026.10.08", draft.catalogVersion());
        assertEquals("entra-external-id-basic-organization-context", option.id());
        assertEquals("entra-external-id", option.providerId());
        assertEquals("Microsoft Entra External ID - external tenant", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Basic MAU; customer-organization entitlement and billing unverified", option.plan());
        assertTrue(option.facts().isEmpty());
        var entries = CatalogDraftFacts.entries(option);
        assertEquals(4, entries.size());
        var fact = assertInstanceOf(ProviderCatalogDraft.CompatibilityFact.class, entries.get(path));
        assertEquals(path.equals("compatibility.applications.B2B_SAAS")
                ? ProviderCatalog.Support.SUPPORTED : ProviderCatalog.Support.UNKNOWN, fact.support());
        assertEquals("learn.microsoft.com", fact.evidence().sourceUrl().getHost());
        var sourcePaths = Map.of(
                "compatibility.applications.B2B_SAAS", "/en-us/entra/external-id/customers/overview-customers-ciam",
                "compatibility.applications.PARTNER_PORTAL", "/en-us/entra/external-id/customers/how-to-manage-admin-accounts",
                "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "/en-us/entra/external-id/tenant-configurations",
                "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "/en-us/entra/external-id/customers/reference-group-app-roles-support");
        assertEquals(sourcePaths.keySet(), entries.keySet());
        assertEquals(sourcePaths.get(path), fact.evidence().sourceUrl().getPath());
        assertEquals(Instant.parse("2026-10-08T16:45:49Z"), fact.evidence().observedAt());
        var conditions = String.join(" ", fact.conditions());
        switch (path) {
            case "compatibility.applications.B2B_SAAS" -> {
                assertTrue(conditions.contains("SUPPORTED is application-type context"));
                assertTrue(conditions.contains("not native customer-organization membership"));
                assertTrue(conditions.contains("separate from a workforce tenant"));
                assertTrue(conditions.contains("not verified account entitlement"));
            }
            case "compatibility.applications.PARTNER_PORTAL" -> {
                assertTrue(conditions.contains("for administration, not customer sign-in"));
                assertTrue(conditions.contains("incompatible with customer user flows"));
                assertTrue(conditions.contains("UNKNOWN remains until an application-owned partner admission"));
                assertTrue(conditions.contains("AuthWeave catalog-curator authority"));
            }
            case "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS" -> {
                assertTrue(conditions.contains("directory is not one SaaS customer organization"));
                assertTrue(conditions.contains("not evidence that application multi-tenancy is unsupported"));
                assertTrue(conditions.contains("tid identifies the sign-in directory"));
                assertTrue(conditions.contains("failed switches without retaining old tenant permissions"));
            }
            case "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER" -> {
                assertTrue(conditions.contains("UNKNOWN is not absence of all group or membership APIs"));
                assertTrue(conditions.contains("roles are application-specific and groups are directory-scoped"));
                assertTrue(conditions.contains("Microsoft Graph, while the RBAC guide shows admin-center procedures"));
                assertTrue(conditions.contains("app-specific sub values are not interchangeable"));
            }
            default -> fail("Unexpected organization-context path");
        }
        assertEquals(3, entries.values().stream().map(ProviderCatalogDraft.CompatibilityFact.class::cast)
                .filter(entry -> entry.support() == ProviderCatalog.Support.UNKNOWN).count());
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
                "\"support\": \"UNKNOWN\"", "\"support\": \"UNKNOWN\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void entraOrganizationFreshnessPreservesHashAndUnknownCompatibilityWithoutGrantingReview() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/entra-external-id-basic-organization-context.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T16:45:49Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var expected = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == expected));
            assertEquals(3, CatalogDraftFacts.entries(draft.options().getFirst()).values().stream()
                    .map(ProviderCatalogDraft.CompatibilityFact.class::cast)
                    .filter(fact -> fact.support() == ProviderCatalog.Support.UNKNOWN).count());
            assertUntrusted(report);
        }
    }

    @Test
    void entraOrganizationScopeKeepsResearchNativeProtocolsPublicClientsAndWorkforceBrokersIndependent() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("entra-external-id.v1.json", "scoped/entra-external-id-basic.v1.json",
                "scoped/entra-external-id-basic-upstream-okta.v1.json", "scoped/entra-external-id-basic-upstream-entra.v1.json",
                "scoped/entra-external-id-basic-public-oidc-clients.v1.json", "scoped/entra-external-id-basic-organization-context.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(6, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        assertTrue(options.subList(0, 4).stream().allMatch(option -> option.compatibility().applications().isEmpty()
                && option.compatibility().tenancy().isEmpty() && option.compatibility().membership().isEmpty()));
        assertTrue(options.getLast().facts().isEmpty());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, options.get(1).facts().get(ProviderCatalog.Capability.OIDC).availability());
        assertEquals(Instant.parse("2026-10-02T23:12:01Z"), options.get(1).facts().get(ProviderCatalog.Capability.OIDC).evidence().observedAt());
        for (var option : options.subList(1, 4)) {
            assertEquals(ProviderCatalog.Availability.UNKNOWN, option.facts().get(ProviderCatalog.Capability.SCIM).availability());
            assertEquals(ProviderCatalog.Availability.UNKNOWN, option.facts().get(ProviderCatalog.Capability.GROUP_SYNC).availability());
        }
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OIDC), options.get(4).facts().keySet());
        assertTrue(options.get(4).compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "entra-organization-context-coexistence-test", options), Instant.parse("2026-10-08T16:45:49Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(6, report.optionCount());
        assertEquals(22, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void keycloakMachineClientsKeepApiAvailabilitySeparateFromHumanLoginAndResourceAuthorization() throws Exception {
        var json = resource("catalog/baselines/scoped/keycloak-26.8.0-machine-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("keycloak-26.8.0-machine-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("keycloak-26.8.0-machine-clients", option.id());
        assertEquals("keycloak", option.providerId());
        assertEquals("Keycloak upstream 26.8.0", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.SELF_HOSTED, option.deployment());
        assertEquals("Upstream release 26.8.0; commercial support not assessed", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), option.facts().keySet());
        var api = option.facts().get(ProviderCatalog.Capability.OAUTH2_APIS);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, api.availability());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        assertEquals(java.util.Set.of(machineType), option.compatibility().clients().keySet());
        var machine = option.compatibility().clients().get(machineType);
        assertEquals(ProviderCatalog.Support.SUPPORTED, machine.support());
        var root = "/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/"
                + "docs/documentation/server_admin/topics/clients/oidc/";
        assertEquals(root + "con-audience.adoc", api.evidence().sourceUrl().getPath());
        assertEquals(root + "proc-using-a-service-account.adoc", machine.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T17:11:31Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("github.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        var clientConditions = String.join(" ", machine.conditions());
        assertTrue(clientConditions.contains("Client authentication On"));
        assertTrue(clientConditions.contains("grant_type=client_credentials"));
        assertTrue(clientConditions.contains("Basic encoding is not encryption"));
        assertTrue(clientConditions.contains("intersection of assigned service-account roles"));
        assertTrue(clientConditions.contains("Do not enable deprecated Full Scope Allowed"));
        assertTrue(clientConditions.contains("without a refresh token or Keycloak user session"));
        assertTrue(clientConditions.contains("No client, credentials, role assignments"));
        var apiConditions = String.join(" ", api.conditions());
        assertTrue(apiConditions.contains("not OIDC login"));
        assertTrue(apiConditions.contains("client ID is not automatically the API audience"));
        assertTrue(apiConditions.contains("Reject a missing or foreign audience"));
        assertTrue(apiConditions.contains("existing access token can remain valid until expiry"));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(2, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void keycloakMachineObservationFreshnessDoesNotChangeProposalOrTrust() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/keycloak-26.8.0-machine-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T17:11:31Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
        assertEquals(ProviderCatalog.Availability.OPTIONAL,
                draft.options().getFirst().facts().get(ProviderCatalog.Capability.OAUTH2_APIS).availability());
    }

    @Test
    void keycloakMachineContextDoesNotPopulateSixEarlierScopesOrInheritScimOrOrganizations() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("keycloak.v1.json", "scoped/keycloak-26.8.0.v1.json",
                "scoped/keycloak-26.8.0-upstream-okta.v1.json", "scoped/keycloak-26.8.0-upstream-entra.v1.json",
                "scoped/keycloak-26.8.0-public-oidc-clients.v1.json", "scoped/keycloak-26.8.0-organization-context.v1.json",
                "scoped/keycloak-26.8.0-machine-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(7, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        for (var option : options.subList(0, 6)) {
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.OAUTH2_APIS));
            assertFalse(option.compatibility().clients().containsKey(machineType));
        }
        assertEquals(Instant.parse("2026-10-02T21:20:39Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), options.getLast().facts().keySet());
        assertTrue(options.getLast().compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "keycloak-machine-context-coexistence-test", options), Instant.parse("2026-10-08T17:11:31Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(7, report.optionCount());
        assertEquals(24, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void zitadelMachineClientsSeparateGrantAssertionsFromApiCredentialsAndResourcePermissions() throws Exception {
        var json = resource("catalog/baselines/scoped/zitadel-cloud-free-machine-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("zitadel-cloud-free-machine-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("zitadel-cloud-free-machine-clients", option.id());
        assertEquals("zitadel", option.providerId());
        assertEquals("ZITADEL Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Free; service-user offer documented, account entitlement unverified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), option.facts().keySet());
        var api = option.facts().get(ProviderCatalog.Capability.OAUTH2_APIS);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, api.availability());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        assertEquals(java.util.Set.of(machineType), option.compatibility().clients().keySet());
        var machine = option.compatibility().clients().get(machineType);
        assertEquals(ProviderCatalog.Support.SUPPORTED, machine.support());
        assertEquals("/docs/guides/integrate/token-introspection", api.evidence().sourceUrl().getPath());
        assertEquals("/docs/guides/integrate/service-accounts/private-key-jwt", machine.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T17:31:13Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("zitadel.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        var clientConditions = String.join(" ", machine.conditions());
        assertTrue(clientConditions.contains("registered RSA public key"));
        assertTrue(clientConditions.contains("Sign RS256 with kid"));
        assertTrue(clientConditions.contains("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer with assertion"));
        assertTrue(clientConditions.contains("not client_credentials plus private_key_jwt"));
        assertTrue(clientConditions.contains("separate application credentials/client_assertion"));
        assertTrue(clientConditions.contains("documentation discrepancies"));
        assertTrue(clientConditions.contains("returned access_token as Bearer"));
        assertTrue(clientConditions.contains("not verified account entitlement"));
        var apiConditions = String.join(" ", api.conditions());
        assertTrue(apiConditions.contains("not the API bearer token, OIDC user login"));
        assertTrue(apiConditions.contains("urn:zitadel:iam:org:project:id:{projectId}:aud"));
        assertTrue(apiConditions.contains("requested scope does not grant a role"));
        assertTrue(apiConditions.contains("separate API application registration"));
        assertTrue(apiConditions.contains("fail closed on inactive tokens, errors, missing or foreign context"));
        assertTrue(apiConditions.contains("active alone are not application authorization"));
        assertTrue(apiConditions.contains("opaque or JWT"));
        assertTrue(apiConditions.contains("No immediate revocation"));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(2, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void zitadelMachineObservationFreshnessDoesNotChangeProposalOrTrust() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/zitadel-cloud-free-machine-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T17:31:13Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
        assertEquals(ProviderCatalog.Availability.OPTIONAL,
                draft.options().getFirst().facts().get(ProviderCatalog.Capability.OAUTH2_APIS).availability());
    }

    @Test
    void zitadelMachineContextDoesNotPopulateSixEarlierScopesOrInheritScimOrOrganizations() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("zitadel.v1.json", "scoped/zitadel-cloud-free.v1.json",
                "scoped/zitadel-cloud-free-upstream-okta.v1.json", "scoped/zitadel-cloud-free-upstream-entra.v1.json",
                "scoped/zitadel-cloud-free-public-oidc-clients.v1.json", "scoped/zitadel-cloud-free-organization-context.v1.json",
                "scoped/zitadel-cloud-free-machine-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(7, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        for (var option : options.subList(0, 6)) {
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.OAUTH2_APIS));
            assertFalse(option.compatibility().clients().containsKey(machineType));
        }
        assertEquals(Instant.parse("2026-10-02T21:44:10Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), options.getLast().facts().keySet());
        assertTrue(options.getLast().compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "zitadel-machine-context-coexistence-test", options), Instant.parse("2026-10-08T17:31:13Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(7, report.optionCount());
        assertEquals(24, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void auth0MachineClientsSeparateSecretPostFromRs256ValidationAndCustomApiPermissions() throws Exception {
        var json = resource("catalog/baselines/scoped/auth0-b2b-free-machine-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("auth0-b2b-free-machine-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("auth0-b2b-free-machine-clients", option.id());
        assertEquals("auth0", option.providerId());
        assertEquals("Auth0 Public Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("B2B Free; bounded M2M offer, account quota and entitlement unverified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), option.facts().keySet());
        var api = option.facts().get(ProviderCatalog.Capability.OAUTH2_APIS);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, api.availability());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        assertEquals(java.util.Set.of(machineType), option.compatibility().clients().keySet());
        var machine = option.compatibility().clients().get(machineType);
        assertEquals(ProviderCatalog.Support.SUPPORTED, machine.support());
        assertEquals("/docs/secure/tokens/access-tokens/validate-access-tokens", api.evidence().sourceUrl().getPath());
        assertEquals("/docs/get-started/authentication-and-authorization-flow/client-credentials-flow/"
                + "call-your-api-using-the-client-credentials-flow", machine.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T17:49:06Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("auth0.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        var clientConditions = String.join(" ", machine.conditions());
        assertTrue(clientConditions.contains("first-party confidential M2M"));
        assertTrue(clientConditions.contains("grant_type=client_credentials"));
        assertTrue(clientConditions.contains("token_endpoint_auth_method client_secret_post"));
        assertTrue(clientConditions.contains("Per-app authorization for Client Access"));
        assertTrue(clientConditions.contains("not Always grant all permissions"));
        assertTrue(clientConditions.contains("User-Delegated Access to No apps allowed"));
        assertTrue(clientConditions.contains("Organization Support None"));
        assertTrue(clientConditions.contains("preserve this discrepancy"));
        assertTrue(clientConditions.contains("1,000 M2M authentications"));
        assertTrue(clientConditions.contains("Custom-audience tokens consume quota"));
        assertTrue(clientConditions.contains("Refresh tokens are not part of this selected flow"));
        var apiConditions = String.join(" ", api.conditions());
        assertTrue(apiConditions.contains("Auth0 JWT profile and RS256"));
        assertTrue(apiConditions.contains("not the requesting client ID or an OIDC ID Token"));
        assertTrue(apiConditions.contains("token decoding or successful issuance alone is not authorization"));
        assertTrue(apiConditions.contains("trusted JWKS, not a token-supplied URL"));
        assertTrue(apiConditions.contains("client secret is not the RS256 signing key"));
        assertTrue(apiConditions.contains("application-owned service-principal/resource permissions"));
        assertTrue(apiConditions.contains("Fail closed on missing or foreign resource context"));
        assertTrue(apiConditions.contains("Do not promise immediate rejection of already-issued JWTs"));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(2, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void auth0MachineObservationFreshnessDoesNotChangeProposalOrTrust() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/auth0-b2b-free-machine-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T17:49:06Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
        assertEquals(ProviderCatalog.Availability.OPTIONAL,
                draft.options().getFirst().facts().get(ProviderCatalog.Capability.OAUTH2_APIS).availability());
    }

    @Test
    void auth0MachineContextDoesNotPopulateSixEarlierScopesOrInheritScimOrOrganizations() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("auth0.v1.json", "scoped/auth0-b2b-free.v1.json",
                "scoped/auth0-b2b-free-upstream-okta.v1.json", "scoped/auth0-b2b-free-upstream-entra.v1.json",
                "scoped/auth0-b2b-free-public-oidc-clients.v1.json", "scoped/auth0-b2b-free-organization-context.v1.json",
                "scoped/auth0-b2b-free-machine-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(7, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        for (var option : options.subList(0, 6)) {
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.OAUTH2_APIS));
            assertFalse(option.compatibility().clients().containsKey(machineType));
        }
        assertEquals(Instant.parse("2026-10-02T22:07:48Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), options.getLast().facts().keySet());
        assertTrue(options.getLast().compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "auth0-machine-context-coexistence-test", options), Instant.parse("2026-10-08T17:49:06Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(7, report.optionCount());
        assertEquals(22, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void workosMachineClientsSeparateThirdPartyOrgCredentialsFromEnvironmentAudienceAndResourceAccess() throws Exception {
        var json = resource("catalog/baselines/scoped/workos-connect-staging-machine-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("workos-connect-staging-machine-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("workos-connect-staging-machine-clients", option.id());
        assertEquals("workos", option.providerId());
        assertEquals("WorkOS AuthKit Connect", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Staging only; M2M offer documented, production entitlement and billing unverified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), option.facts().keySet());
        var api = option.facts().get(ProviderCatalog.Capability.OAUTH2_APIS);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, api.availability());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        assertEquals(java.util.Set.of(machineType), option.compatibility().clients().keySet());
        var machine = option.compatibility().clients().get(machineType);
        assertEquals(ProviderCatalog.Support.SUPPORTED, machine.support());
        assertEquals("/docs/authkit/connect/token-claims", api.evidence().sourceUrl().getPath());
        assertEquals("/docs/authkit/connect/m2m", machine.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T18:39:35Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("workos.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        var clientConditions = String.join(" ", machine.conditions());
        assertTrue(clientConditions.contains("only third-party applications"));
        assertTrue(clientConditions.contains("not a first-party background-service"));
        assertTrue(clientConditions.contains("explicit customer/partner organization"));
        assertTrue(clientConditions.contains("grant_type=client_credentials"));
        assertTrue(clientConditions.contains("client_secret_post only"));
        assertTrue(clientConditions.contains("management API key is not this application secret"));
        assertTrue(clientConditions.contains("least-privilege application scopes"));
        assertTrue(clientConditions.contains("exact narrowing/default behavior is untested"));
        assertTrue(clientConditions.contains("OpenID discovery example includes client_credentials"));
        assertTrue(clientConditions.contains("OAuth authorization-server example omits it"));
        assertTrue(clientConditions.contains("Free staging does not verify production M2M entitlement"));
        var apiConditions = String.join(" ", api.conditions());
        assertTrue(apiConditions.contains("environment client ID audience"));
        assertTrue(apiConditions.contains("not the requesting M2M client ID"));
        assertTrue(apiConditions.contains("machine sub and client_id"));
        assertTrue(apiConditions.contains("expected org_id and granted scopes"));
        assertTrue(apiConditions.contains("application-owned resource permissions"));
        assertTrue(apiConditions.contains("no sid and no JWT templates/custom claims"));
        assertTrue(apiConditions.contains("typed endpoint heading says /oauth2/token"));
        assertTrue(apiConditions.contains("org_id optional"));
        assertTrue(apiConditions.contains("Do not promise immediate rejection of already-issued JWTs"));
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(2, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void workosMachineObservationFreshnessDoesNotChangeProposalOrTrust() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/workos-connect-staging-machine-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T18:39:35Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
        assertEquals(ProviderCatalog.Availability.OPTIONAL,
                draft.options().getFirst().facts().get(ProviderCatalog.Capability.OAUTH2_APIS).availability());
    }

    @Test
    void workosMachineContextDoesNotPopulateSixEarlierScopesOrInheritPrimaryAuthkitOrScim() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("workos.v1.json", "scoped/workos-directory-sync-staging.v1.json",
                "scoped/workos-directory-sync-staging-upstream-okta.v1.json", "scoped/workos-directory-sync-staging-upstream-entra.v1.json",
                "scoped/workos-connect-staging-public-oidc-clients.v1.json", "scoped/workos-authkit-staging-organization-context.v1.json",
                "scoped/workos-connect-staging-machine-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(7, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        for (var option : options.subList(0, 6)) {
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.OAUTH2_APIS));
            assertFalse(option.compatibility().clients().containsKey(machineType));
        }
        assertEquals(Instant.parse("2026-10-02T22:46:13Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(ProviderCatalog.Support.UNKNOWN, options.get(4).compatibility().clients()
                .get(io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER).support());
        assertEquals("WorkOS AuthKit", options.get(5).product());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), options.getLast().facts().keySet());
        assertTrue(options.getLast().compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "workos-machine-context-coexistence-test", options), Instant.parse("2026-10-08T18:39:35Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(7, report.optionCount());
        assertEquals(18, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void entraMachineClientsRequirePaidAddonAndSeparateAppRolesFromCustomerResourceAuthorization() throws Exception {
        var json = resource("catalog/baselines/scoped/entra-external-id-m2m-addon-machine-clients.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("entra-external-id-m2m-addon-machine-clients-draft-2026.10.08", draft.catalogVersion());
        assertEquals("entra-external-id-m2m-addon-machine-clients", option.id());
        assertEquals("entra-external-id", option.providerId());
        assertEquals("Microsoft Entra External ID - external tenant", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals("Basic MAU + M2M Premium add-on; transaction billing, entitlement unverified", option.plan());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), option.facts().keySet());
        var api = option.facts().get(ProviderCatalog.Capability.OAUTH2_APIS);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, api.availability());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        assertEquals(java.util.Set.of(machineType), option.compatibility().clients().keySet());
        var machine = option.compatibility().clients().get(machineType);
        assertEquals(ProviderCatalog.Support.SUPPORTED, machine.support());
        assertEquals("/en-us/entra/identity-platform/claims-validation", api.evidence().sourceUrl().getPath());
        assertEquals("/en-us/entra/external-id/customers/overview-customers-ciam", machine.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T19:00:07Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("learn.microsoft.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        var clientConditions = String.join(" ", machine.conditions());
        for (var phrase : List.of("M2M Premium add-on", "not Basic-only free MAU", "client_secret_post only",
                "Delegated user permissions do not apply", "not a dynamic per-request subset",
                "no refresh token", "transaction-based and separate from Basic MAU",
                "does not include free machine authentication", "separate owner decision",
                "Do not copy generic workforce/common endpoints", "payment or live call")) {
            assertTrue(clientConditions.contains(phrase), phrase);
        }
        var apiConditions = String.join(" ", api.conditions());
        for (var phrase : List.of("requestedAccessTokenVersion=2", "endpoint version alone does not determine",
                "API client ID audience", "Reject Graph, ID, foreign-resource and v1 tokens",
                "registered machine azp", "optional idtyp=app", "reject missing or user values",
                "not infer app-only identity solely", "tid is not a customer organization ID",
                "must reject them", "assignment requirements", "independently authorize",
                "Do not promise immediate rejection", "synthetic evaluator remain unchanged")) {
            assertTrue(apiConditions.contains(phrase), phrase);
        }
        assertTrue(option.compatibility().applications().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        assertTrue(option.compatibility().tenancy().isEmpty());
        assertTrue(option.compatibility().membership().isEmpty());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.authenticationControls().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(2, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue(json.replace(
                "\"support\": \"SUPPORTED\"", "\"support\": \"SUPPORTED\", \"evidenceStatus\": \"REVIEWED\""), ProviderCatalogDraft.class));
    }

    @Test
    void entraMachineObservationFreshnessDoesNotPromotePaidEntitlementOrApproval() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/entra-external-id-m2m-addon-machine-clients.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T19:00:07Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
        assertEquals(ProviderCatalog.Availability.OPTIONAL,
                draft.options().getFirst().facts().get(ProviderCatalog.Capability.OAUTH2_APIS).availability());
    }

    @Test
    void entraPaidMachineScopeDoesNotPopulateSixEarlierBasicAndResearchScopes() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("entra-external-id.v1.json", "scoped/entra-external-id-basic.v1.json",
                "scoped/entra-external-id-basic-upstream-okta.v1.json", "scoped/entra-external-id-basic-upstream-entra.v1.json",
                "scoped/entra-external-id-basic-public-oidc-clients.v1.json", "scoped/entra-external-id-basic-organization-context.v1.json",
                "scoped/entra-external-id-m2m-addon-machine-clients.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(7, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        var machineType = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.MACHINE_TO_MACHINE;
        for (var option : options.subList(0, 6)) {
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.OAUTH2_APIS));
            assertFalse(option.compatibility().clients().containsKey(machineType));
            assertFalse(option.plan().contains("M2M Premium add-on"));
        }
        assertEquals(Instant.parse("2026-10-02T23:12:01Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(ProviderCatalog.Availability.UNKNOWN, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.OAUTH2_APIS), options.getLast().facts().keySet());
        assertTrue(options.getLast().compatibility().membership().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "entra-machine-context-coexistence-test", options), Instant.parse("2026-10-08T19:00:07Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(7, report.optionCount());
        assertEquals(24, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void keycloakAuthenticationControlsSeparateMfaAvailabilityFromScopedEnforcementAndDeviceAssurance() throws Exception {
        var json = resource("catalog/baselines/scoped/keycloak-26.8.0-browser-authentication-controls.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("keycloak-26.8.0-browser-authentication-controls-draft-2026.10.08", draft.catalogVersion());
        assertEquals("keycloak-26.8.0-browser-authentication-controls", option.id());
        assertEquals("Keycloak upstream 26.8.0", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.SELF_HOSTED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.MFA), option.facts().keySet());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, option.facts().get(ProviderCatalog.Capability.MFA).availability());
        var browser = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER;
        var population = io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.EXTERNAL_CUSTOMERS;
        assertEquals(java.util.Set.of(browser), option.authenticationControls().keySet());
        assertEquals(java.util.Set.of(population), option.authenticationControls().get(browser).keySet());
        var controls = option.authenticationControls().get(browser).get(population);
        assertEquals(java.util.Set.of(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE,
                ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS,
                ProviderCatalog.AuthenticationControl.STEP_UP_AUTHENTICATION), controls.keySet());
        var phishing = controls.get(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE);
        assertEquals(ProviderCatalog.Support.SUPPORTED, phishing.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, phishing.enforcement());
        var keys = controls.get(ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS);
        assertEquals(ProviderCatalog.Support.UNKNOWN, keys.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, keys.enforcement());
        var step = controls.get(ProviderCatalog.AuthenticationControl.STEP_UP_AUTHENTICATION);
        assertEquals(ProviderCatalog.Support.SUPPORTED, step.availability());
        assertEquals(ProviderCatalog.Support.SUPPORTED, step.enforcement());
        assertTrue(String.join(" ", phishing.conditions()).contains("initial enrollment, password reset, recovery"));
        assertTrue(String.join(" ", keys.conditions()).contains("both synced and device-bound passkeys"));
        var stepConditions = String.join(" ", step.conditions());
        for (var phrase : List.of("essential acr", "acr_values is non-essential", "unachievable essential level returns an error",
                "before the sensitive operation", "expired implicit level can yield acr=0",
                "acr client scope/mapper", "not NIST AAL certifications", "does not gate application business operations automatically")) {
            assertTrue(stepConditions.contains(phrase), phrase);
        }
        var sourceRoot = "/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/authentication/";
        assertEquals(sourceRoot + "webauthn.adoc", phishing.evidence().sourceUrl().getPath());
        assertEquals(sourceRoot + "passkeys.adoc", keys.evidence().sourceUrl().getPath());
        assertEquals(sourceRoot + "flows.adoc", step.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T19:33:03Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("github.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        assertEquals(4, CatalogDraftFacts.entries(option).size());
        assertTrue(option.residency().isEmpty());
        assertTrue(option.compatibility().clients().isEmpty());
        assertTrue(option.compatibility().populations().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        var impossible = mapper.readTree(json);
        ((tools.jackson.databind.node.ObjectNode) impossible.at(
                "/options/0/authenticationControls/BROWSER/EXTERNAL_CUSTOMERS/NON_EXPORTABLE_KEYS")).put("enforcement", "SUPPORTED");
        var invalid = validator.validateAt(mapper.treeToValue(impossible, ProviderCatalogDraft.class), observed);
        assertEquals(CatalogDraftValidation.Status.INVALID_DRAFT, invalid.status());
        assertTrue(invalid.issues().stream().anyMatch(issue ->
                issue.code() == CatalogDraftValidation.IssueCode.AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY));
        assertUntrusted(invalid);
    }

    @Test
    void keycloakAuthenticationFreshnessKeepsTypedUnknownEnforcementAndStableEvidence() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/keycloak-26.8.0-browser-authentication-controls.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T19:33:03Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertEquals(4, report.factCount());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
    }

    @Test
    void keycloakBrowserCustomerControlsDoNotPopulateSevenOlderScopesOrOtherClientPopulations() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("keycloak.v1.json", "scoped/keycloak-26.8.0.v1.json",
                "scoped/keycloak-26.8.0-upstream-okta.v1.json", "scoped/keycloak-26.8.0-upstream-entra.v1.json",
                "scoped/keycloak-26.8.0-public-oidc-clients.v1.json", "scoped/keycloak-26.8.0-organization-context.v1.json",
                "scoped/keycloak-26.8.0-machine-clients.v1.json", "scoped/keycloak-26.8.0-browser-authentication-controls.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(8, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        for (var option : options.subList(0, 7)) {
            assertTrue(option.authenticationControls().isEmpty());
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.MFA));
        }
        assertEquals(Instant.parse("2026-10-02T21:20:39Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertTrue(options.getLast().residency().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "keycloak-authentication-context-coexistence-test", options), Instant.parse("2026-10-08T19:33:03Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(8, report.optionCount());
        assertEquals(28, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void zitadelCloudAuthenticationKeepsFreeMechanismsSeparateFromJourneyAndHardwareEnforcement() throws Exception {
        var json = resource("catalog/baselines/scoped/zitadel-cloud-free-browser-authentication-controls.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("zitadel-cloud-free-browser-authentication-controls-draft-2026.10.08", draft.catalogVersion());
        assertEquals("ZITADEL Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.MFA), option.facts().keySet());
        assertEquals(ProviderCatalog.Availability.OPTIONAL, option.facts().get(ProviderCatalog.Capability.MFA).availability());
        var browser = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER;
        var population = io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.EXTERNAL_CUSTOMERS;
        assertEquals(java.util.Set.of(browser), option.authenticationControls().keySet());
        assertEquals(java.util.Set.of(population), option.authenticationControls().get(browser).keySet());
        var controls = option.authenticationControls().get(browser).get(population);
        assertEquals(3, controls.size());
        assertEquals(ProviderCatalog.Support.SUPPORTED, controls.get(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE).availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, controls.get(ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS).availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, controls.get(ProviderCatalog.AuthenticationControl.STEP_UP_AUTHENTICATION).availability());
        assertTrue(controls.values().stream().allMatch(fact -> fact.enforcement() == ProviderCatalog.Support.UNKNOWN));
        assertTrue(String.join(" ", controls.get(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE).conditions())
                .contains("Record this source discrepancy"));
        assertTrue(String.join(" ", controls.get(ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS).conditions())
                .contains("attestation none and required user verification"));
        var step = controls.get(ProviderCatalog.AuthenticationControl.STEP_UP_AUTHENTICATION);
        for (var phrase : List.of("max_age and prompt=login", "stronger authentication, not repeating the same login",
                "absent or insufficient evidence must deny", "does not establish an essential-ACR",
                "Do not inherit Keycloak LoA semantics")) {
            assertTrue(String.join(" ", step.conditions()).contains(phrase), phrase);
        }
        assertEquals("/docs/guides/integrate/login/hosted-login",
                controls.get(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE).evidence().sourceUrl().getPath());
        assertEquals("/docs/guides/integrate/login-ui/passkey",
                controls.get(ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS).evidence().sourceUrl().getPath());
        assertEquals("/docs/apis/openidoauth/endpoints", step.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T20:18:19Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("zitadel.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        assertTrue(option.residency().isEmpty());
        assertTrue(option.compatibility().clients().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        var impossible = mapper.readTree(json);
        ((tools.jackson.databind.node.ObjectNode) impossible.at(
                "/options/0/authenticationControls/BROWSER/EXTERNAL_CUSTOMERS/NON_EXPORTABLE_KEYS")).put("enforcement", "SUPPORTED");
        var invalid = validator.validateAt(mapper.treeToValue(impossible, ProviderCatalogDraft.class), observed);
        assertEquals(CatalogDraftValidation.Status.INVALID_DRAFT, invalid.status());
        assertTrue(invalid.issues().stream().anyMatch(issue ->
                issue.code() == CatalogDraftValidation.IssueCode.AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY));
        assertUntrusted(invalid);
    }

    @Test
    void zitadelCloudAuthenticationFreshnessDoesNotPromoteEnforcementOrRewriteEvidence() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/zitadel-cloud-free-browser-authentication-controls.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T20:18:19Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertEquals(4, report.factCount());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
    }

    @Test
    void zitadelCloudCustomerAuthenticationDoesNotPopulateSevenOlderScopes() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("zitadel.v1.json", "scoped/zitadel-cloud-free.v1.json",
                "scoped/zitadel-cloud-free-upstream-okta.v1.json", "scoped/zitadel-cloud-free-upstream-entra.v1.json",
                "scoped/zitadel-cloud-free-public-oidc-clients.v1.json", "scoped/zitadel-cloud-free-organization-context.v1.json",
                "scoped/zitadel-cloud-free-machine-clients.v1.json", "scoped/zitadel-cloud-free-browser-authentication-controls.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(8, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        for (var option : options.subList(0, 7)) {
            assertTrue(option.authenticationControls().isEmpty());
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.MFA));
        }
        assertEquals(Instant.parse("2026-10-02T21:44:10Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertTrue(options.getLast().residency().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "zitadel-authentication-context-coexistence-test", options), Instant.parse("2026-10-08T20:18:19Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(8, report.optionCount());
        assertEquals(28, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void auth0FreeAuthenticationDoesNotBorrowPaidMfaOrClaimMandatoryPasskeyJourneys() throws Exception {
        var json = resource("catalog/baselines/scoped/auth0-b2b-free-browser-authentication-controls.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("auth0-b2b-free-browser-authentication-controls-draft-2026.10.08", draft.catalogVersion());
        assertEquals("Auth0 Public Cloud", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.MFA), option.facts().keySet());
        var mfa = option.facts().get(ProviderCatalog.Capability.MFA);
        assertEquals(ProviderCatalog.Availability.UNKNOWN, mfa.availability());
        assertTrue(String.join(" ", mfa.conditions()).contains("Free pricing includes passkeys but excludes Pro MFA factors"));
        assertEquals("/pricing", mfa.evidence().sourceUrl().getPath());
        var browser = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER;
        var population = io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.EXTERNAL_CUSTOMERS;
        assertEquals(java.util.Set.of(browser), option.authenticationControls().keySet());
        assertEquals(java.util.Set.of(population), option.authenticationControls().get(browser).keySet());
        var controls = option.authenticationControls().get(browser).get(population);
        assertEquals(3, controls.size());
        var phishing = controls.get(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE);
        var keys = controls.get(ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS);
        var step = controls.get(ProviderCatalog.AuthenticationControl.STEP_UP_AUTHENTICATION);
        assertEquals(ProviderCatalog.Support.SUPPORTED, phishing.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, keys.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, step.availability());
        assertTrue(controls.values().stream().allMatch(fact -> fact.enforcement() == ProviderCatalog.Support.UNKNOWN));
        for (var phrase : List.of("must still have passwords enabled", "Organization invitation signup initially uses a password",
                "progressive enrollment can be delayed", "Journey enforcement remains UNKNOWN")) {
            assertTrue(String.join(" ", phishing.conditions()).contains(phrase), phrase);
        }
        assertTrue(String.join(" ", keys.conditions()).contains("syncing credentials across devices"));
        for (var phrase : List.of("generic guide does not verify Free-plan factor entitlement", "allowRememberBrowser",
                "Absent or insufficient evidence must deny", "stronger authentication, not repeating the same login",
                "amr can be absent after silent authentication or refresh")) {
            assertTrue(String.join(" ", step.conditions()).contains(phrase), phrase);
        }
        assertEquals("/docs/authenticate/database-connections/passkeys/configure-passkey-policy",
                phishing.evidence().sourceUrl().getPath());
        assertEquals("/docs/authenticate/database-connections/passkeys", keys.evidence().sourceUrl().getPath());
        assertEquals("/docs/secure/multi-factor-authentication/step-up-authentication/configure-step-up-authentication-for-web-apps",
                step.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T20:50:27Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("auth0.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        assertTrue(option.residency().isEmpty());
        assertTrue(option.compatibility().clients().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        var impossible = mapper.readTree(json);
        ((tools.jackson.databind.node.ObjectNode) impossible.at(
                "/options/0/authenticationControls/BROWSER/EXTERNAL_CUSTOMERS/NON_EXPORTABLE_KEYS")).put("enforcement", "SUPPORTED");
        var invalid = validator.validateAt(mapper.treeToValue(impossible, ProviderCatalogDraft.class), observed);
        assertEquals(CatalogDraftValidation.Status.INVALID_DRAFT, invalid.status());
        assertTrue(invalid.issues().stream().anyMatch(issue ->
                issue.code() == CatalogDraftValidation.IssueCode.AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY));
        assertUntrusted(invalid);
    }

    @Test
    void auth0FreeAuthenticationFreshnessDoesNotPromoteMfaOrRewriteEvidence() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/auth0-b2b-free-browser-authentication-controls.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T20:50:27Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertEquals(4, report.factCount());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
    }

    @Test
    void auth0FreeCustomerAuthenticationDoesNotPopulateSevenOlderScopes() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("auth0.v1.json", "scoped/auth0-b2b-free.v1.json",
                "scoped/auth0-b2b-free-upstream-okta.v1.json", "scoped/auth0-b2b-free-upstream-entra.v1.json",
                "scoped/auth0-b2b-free-public-oidc-clients.v1.json", "scoped/auth0-b2b-free-organization-context.v1.json",
                "scoped/auth0-b2b-free-machine-clients.v1.json", "scoped/auth0-b2b-free-browser-authentication-controls.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(8, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        for (var option : options.subList(0, 7)) {
            assertTrue(option.authenticationControls().isEmpty());
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.MFA));
        }
        assertEquals(Instant.parse("2026-10-02T22:07:48Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertTrue(options.getLast().residency().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "auth0-authentication-context-coexistence-test", options), Instant.parse("2026-10-08T20:50:27Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(8, report.optionCount());
        assertEquals(26, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void workosPrimaryStagingAuthenticationKeepsMfaSeparateFromJourneyHardwareAndStrongerStepUp() throws Exception {
        var json = resource("catalog/baselines/scoped/workos-authkit-staging-browser-authentication-controls.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("workos-authkit-staging-browser-authentication-controls-draft-2026.10.08", draft.catalogVersion());
        assertEquals("WorkOS AuthKit", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.MFA), option.facts().keySet());
        var mfa = option.facts().get(ProviderCatalog.Capability.MFA);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, mfa.availability());
        assertTrue(String.join(" ", mfa.conditions()).contains("SSO users are exempt"));
        assertTrue(String.join(" ", mfa.conditions()).contains("standalone SMS MFA API"));
        assertEquals("/docs/authkit/mfa", mfa.evidence().sourceUrl().getPath());
        var browser = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER;
        var population = io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.EXTERNAL_CUSTOMERS;
        assertEquals(java.util.Set.of(browser), option.authenticationControls().keySet());
        assertEquals(java.util.Set.of(population), option.authenticationControls().get(browser).keySet());
        var controls = option.authenticationControls().get(browser).get(population);
        assertEquals(3, controls.size());
        var phishing = controls.get(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE);
        var keys = controls.get(ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS);
        var step = controls.get(ProviderCatalog.AuthenticationControl.STEP_UP_AUTHENTICATION);
        assertEquals(ProviderCatalog.Support.SUPPORTED, phishing.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, keys.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, step.availability());
        assertTrue(controls.values().stream().allMatch(fact -> fact.enforcement() == ProviderCatalog.Support.UNKNOWN));
        for (var phrase : List.of("off by default and can be skipped", "not a passkey-only policy",
                "custom domains are production-only", "enforcement remains UNKNOWN")) {
            assertTrue(String.join(" ", phishing.conditions()).contains(phrase), phrase);
        }
        assertTrue(String.join(" ", keys.conditions()).contains("elevatedAccessToken"));
        assertTrue(String.join(" ", keys.conditions()).contains("distinct surfaces"));
        for (var phrase : List.of("max_age", "auth_time advanced by active authentication, not refresh",
                "chooses a password, MFA factor or upstream SSO method", "stronger authentication, not repeating the same login",
                "Missing, malformed, future or insufficient evidence must deny", "Stronger-factor enforcement stays UNKNOWN")) {
            assertTrue(String.join(" ", step.conditions()).contains(phrase), phrase);
        }
        assertEquals("/docs/authkit/passkeys", phishing.evidence().sourceUrl().getPath());
        assertEquals("/docs/widgets-api/authentication", keys.evidence().sourceUrl().getPath());
        assertEquals("/docs/authkit/reauthentication", step.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T21:09:22Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("workos.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        assertTrue(option.residency().isEmpty());
        assertTrue(option.compatibility().clients().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        var impossible = mapper.readTree(json);
        ((tools.jackson.databind.node.ObjectNode) impossible.at(
                "/options/0/authenticationControls/BROWSER/EXTERNAL_CUSTOMERS/NON_EXPORTABLE_KEYS")).put("enforcement", "SUPPORTED");
        var invalid = validator.validateAt(mapper.treeToValue(impossible, ProviderCatalogDraft.class), observed);
        assertEquals(CatalogDraftValidation.Status.INVALID_DRAFT, invalid.status());
        assertTrue(invalid.issues().stream().anyMatch(issue ->
                issue.code() == CatalogDraftValidation.IssueCode.AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY));
        assertUntrusted(invalid);
    }

    @Test
    void workosStagingAuthenticationFreshnessDoesNotPromoteControlsOrRewriteEvidence() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/workos-authkit-staging-browser-authentication-controls.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T21:09:22Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertEquals(4, report.factCount());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
    }

    @Test
    void workosStagingHostedAuthenticationDoesNotPopulateSevenOlderProductScopes() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("workos.v1.json", "scoped/workos-directory-sync-staging.v1.json",
                "scoped/workos-directory-sync-staging-upstream-okta.v1.json", "scoped/workos-directory-sync-staging-upstream-entra.v1.json",
                "scoped/workos-connect-staging-public-oidc-clients.v1.json", "scoped/workos-authkit-staging-organization-context.v1.json",
                "scoped/workos-connect-staging-machine-clients.v1.json", "scoped/workos-authkit-staging-browser-authentication-controls.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(8, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        for (var option : options.subList(0, 7)) {
            assertTrue(option.authenticationControls().isEmpty());
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.MFA));
        }
        assertEquals(Instant.parse("2026-10-02T22:46:13Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals("WorkOS Directory Sync", options.get(1).product());
        assertEquals("WorkOS AuthKit Connect", options.get(4).product());
        assertEquals(ProviderCatalog.Support.UNKNOWN, options.get(4).compatibility().clients().get(
                io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER).support());
        assertEquals("WorkOS AuthKit", options.getLast().product());
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertTrue(options.getLast().residency().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "workos-authentication-context-coexistence-test", options), Instant.parse("2026-10-08T21:09:22Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(8, report.optionCount());
        assertEquals(22, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
    }

    @Test
    void entraCustomerAuthenticationSeparatesPasskeyAvailabilityFromExternalTenantPolicyLimits() throws Exception {
        var json = resource("catalog/baselines/scoped/entra-external-id-basic-browser-authentication-controls.v1.json");
        var draft = mapper.readValue(json, ProviderCatalogDraft.class);
        var option = draft.options().getFirst();
        assertEquals("entra-external-id-basic-browser-authentication-controls-draft-2026.10.08", draft.catalogVersion());
        assertEquals("Microsoft Entra External ID - external tenant", option.product());
        assertEquals(ProviderCatalogDraft.Deployment.MANAGED, option.deployment());
        assertEquals(java.util.Set.of(ProviderCatalog.Capability.MFA), option.facts().keySet());
        var mfa = option.facts().get(ProviderCatalog.Capability.MFA);
        assertEquals(ProviderCatalog.Availability.OPTIONAL, mfa.availability());
        assertTrue(String.join(" ", mfa.conditions()).contains("email OTP used as first factor cannot also be the second factor"));
        assertTrue(String.join(" ", mfa.conditions()).contains("SMS has separate charges and is excluded"));
        assertEquals("/en-us/entra/external-id/customers/concept-multifactor-authentication-customers",
                mfa.evidence().sourceUrl().getPath());
        var browser = io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER;
        var population = io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation.EXTERNAL_CUSTOMERS;
        assertEquals(java.util.Set.of(browser), option.authenticationControls().keySet());
        assertEquals(java.util.Set.of(population), option.authenticationControls().get(browser).keySet());
        var controls = option.authenticationControls().get(browser).get(population);
        assertEquals(3, controls.size());
        var phishing = controls.get(ProviderCatalog.AuthenticationControl.PHISHING_RESISTANCE);
        var keys = controls.get(ProviderCatalog.AuthenticationControl.NON_EXPORTABLE_KEYS);
        var step = controls.get(ProviderCatalog.AuthenticationControl.STEP_UP_AUTHENTICATION);
        assertEquals(ProviderCatalog.Support.SUPPORTED, phishing.availability());
        assertEquals(ProviderCatalog.Support.UNSUPPORTED, phishing.enforcement());
        assertEquals(ProviderCatalog.Support.UNKNOWN, keys.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, keys.enforcement());
        assertEquals(ProviderCatalog.Support.SUPPORTED, step.availability());
        assertEquals(ProviderCatalog.Support.UNKNOWN, step.enforcement());
        for (var phrase : List.of("external-tenant Conditional Access authentication strengths",
                "Requiring generic MFA does not require passkeys", "Azure Front Door route incurs separate charges",
                "not a zero-total-cost promise", "not a provider-wide rejection")) {
            assertTrue(String.join(" ", phishing.conditions()).contains(phrase), phrase);
        }
        for (var phrase : List.of("hardware-backed non-exportability", "Attestation validates make/model",
                "registration-time attestation changes do not block previously registered unattested credentials")) {
            assertTrue(String.join(" ", keys.conditions()).contains(phrase), phrase);
        }
        for (var phrase : List.of("password-to-MFA elevation", "insufficient_claims challenge with essential acrs",
                "acrs value can be issued without an attached policy", "required factor freshness at the server operation gate",
                "Missing, malformed, future or insufficient evidence must deny", "P1 licensing and a Free-edition limitation",
                "Basic MAU entitlement and effective policy remain unresolved")) {
            assertTrue(String.join(" ", step.conditions()).contains(phrase), phrase);
        }
        assertEquals("/en-us/entra/external-id/customers/how-to-sign-in-with-passkey", phishing.evidence().sourceUrl().getPath());
        assertEquals("/en-us/entra/identity/authentication/how-to-enable-passkey-fido2", keys.evidence().sourceUrl().getPath());
        assertEquals("/en-us/entra/identity-platform/developer-guide-conditional-access-authentication-context",
                step.evidence().sourceUrl().getPath());
        var observed = Instant.parse("2026-10-08T21:34:08Z");
        for (var fact : CatalogDraftFacts.entries(option).values()) {
            assertEquals("learn.microsoft.com", fact.evidence().sourceUrl().getHost());
            assertEquals(observed, fact.evidence().observedAt());
            assertFalse(fact.conditions().isEmpty());
        }
        assertTrue(option.residency().isEmpty());
        assertTrue(option.compatibility().clients().isEmpty());
        var report = validator.validateAt(draft, observed);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(4, report.factCount());
        assertTrue(report.issues().isEmpty());
        assertUntrusted(report);
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, ProviderCatalog.class));
        var impossible = mapper.readTree(json);
        ((tools.jackson.databind.node.ObjectNode) impossible.at(
                "/options/0/authenticationControls/BROWSER/EXTERNAL_CUSTOMERS/NON_EXPORTABLE_KEYS")).put("enforcement", "SUPPORTED");
        var invalid = validator.validateAt(mapper.treeToValue(impossible, ProviderCatalogDraft.class), observed);
        assertEquals(CatalogDraftValidation.Status.INVALID_DRAFT, invalid.status());
        assertTrue(invalid.issues().stream().anyMatch(issue ->
                issue.code() == CatalogDraftValidation.IssueCode.AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY));
        assertUntrusted(invalid);
    }

    @Test
    void entraAuthenticationFreshnessDoesNotPromoteUnknownControlsOrEraseScopedNegatives() throws Exception {
        var draft = mapper.readValue(resource("catalog/baselines/scoped/entra-external-id-basic-browser-authentication-controls.v1.json"),
                ProviderCatalogDraft.class);
        var observed = Instant.parse("2026-10-08T21:34:08Z");
        var current = validator.validateAt(draft, observed);
        for (var at : List.of(observed.minusNanos(1), observed.plusSeconds(90L * 86400),
                observed.plusSeconds(90L * 86400).plusNanos(1))) {
            var report = validator.validateAt(draft, at);
            var freshness = at.isBefore(observed) ? CatalogDraftValidation.Freshness.FUTURE
                    : at.isAfter(observed.plusSeconds(90L * 86400)) ? CatalogDraftValidation.Freshness.STALE
                    : CatalogDraftValidation.Freshness.CURRENT;
            assertEquals(current.contentSha256(), report.contentSha256());
            assertEquals(4, report.factCount());
            assertTrue(report.facts().stream().allMatch(fact -> fact.freshness() == freshness));
            assertUntrusted(report);
        }
    }

    @Test
    void entraCustomerAuthenticationDoesNotPopulateSevenEarlierResearchFederationOrPaidMachineScopes() throws Exception {
        var options = new java.util.ArrayList<ProviderCatalogDraft.Option>();
        for (var file : List.of("entra-external-id.v1.json", "scoped/entra-external-id-basic.v1.json",
                "scoped/entra-external-id-basic-upstream-okta.v1.json", "scoped/entra-external-id-basic-upstream-entra.v1.json",
                "scoped/entra-external-id-basic-public-oidc-clients.v1.json", "scoped/entra-external-id-basic-organization-context.v1.json",
                "scoped/entra-external-id-m2m-addon-machine-clients.v1.json",
                "scoped/entra-external-id-basic-browser-authentication-controls.v1.json")) {
            options.add(mapper.readValue(resource("catalog/baselines/" + file), ProviderCatalogDraft.class).options().getFirst());
        }
        assertEquals(8, options.stream().map(ProviderCatalogDraft.Option::id).distinct().count());
        for (var option : options.subList(0, 7)) {
            assertTrue(option.authenticationControls().isEmpty());
            assertFalse(option.facts().containsKey(ProviderCatalog.Capability.MFA));
        }
        assertEquals(Instant.parse("2026-10-02T23:12:01Z"),
                options.get(1).facts().get(ProviderCatalog.Capability.SCIM).evidence().observedAt());
        assertEquals(ProviderCatalog.Availability.UNKNOWN, options.get(1).facts().get(ProviderCatalog.Capability.SCIM).availability());
        assertTrue(options.get(6).plan().contains("M2M Premium add-on"));
        assertTrue(options.getLast().plan().contains("custom-domain/Front Door costs"));
        assertTrue(options.getLast().compatibility().clients().isEmpty());
        assertTrue(options.getLast().residency().isEmpty());
        var report = validator.validateAt(new ProviderCatalogDraft(1, ProviderCatalogDraft.Kind.PROVIDER_CATALOG_DRAFT,
                "entra-authentication-context-coexistence-test", options), Instant.parse("2026-10-08T21:34:08Z"));
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(8, report.optionCount());
        assertEquals(28, report.factCount());
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
