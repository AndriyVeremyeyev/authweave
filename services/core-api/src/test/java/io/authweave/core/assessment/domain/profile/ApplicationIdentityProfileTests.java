package io.authweave.core.assessment.domain.profile;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.BudgetSensitivity;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.DeploymentTarget;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.HostingPreference;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.IdentityExpertise;
import io.authweave.core.assessment.domain.profile.ProtocolRequirements.FederationProtocol;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationIdentityProfileTests {

    @Test
    void representsMissingDraftInformationExplicitly() {
        ApplicationIdentityProfile profile = ApplicationIdentityProfile.unknown();

        assertEquals(ApplicationType.UNKNOWN, profile.application().type());
        assertTrue(profile.application().clients().isEmpty());
        assertTrue(profile.audience().populations().isEmpty());
        assertEquals(TenancyModel.UNKNOWN, profile.audience().tenancy());
        assertEquals(
                RequirementCriticality.UNKNOWN,
                profile.protocols().criticalityOf(FederationProtocol.OIDC));
        assertEquals(RequirementCriticality.UNKNOWN, profile.provisioning().scim());
        assertEquals(AssuranceLevel.UNKNOWN, profile.security().assurance());
        assertEquals(DeploymentTarget.UNDECIDED, profile.operations().deploymentTarget());
    }

    @Test
    void representsThePrimaryB2bSaasProfile() {
        ApplicationIdentityProfile profile = primaryB2bProfile();

        assertEquals(ApplicationType.B2B_SAAS, profile.application().type());
        assertEquals(Set.of(ClientType.BROWSER), profile.application().clients());
        assertEquals(
                Set.of(UserPopulation.EXTERNAL_CUSTOMERS, UserPopulation.PARTNERS),
                profile.audience().populations());
        assertEquals(TenancyModel.MULTI_TENANT_ORGANIZATIONS, profile.audience().tenancy());
        assertEquals(
                MembershipModel.MULTIPLE_ORGANIZATIONS_PER_USER,
                profile.audience().membership());
        assertEquals(
                RequirementCriticality.REQUIRED,
                profile.protocols().criticalityOf(FederationProtocol.OIDC));
        assertEquals(
                RequirementCriticality.REQUIRED,
                profile.protocols().criticalityOf(FederationProtocol.SAML));
        assertEquals(RequirementCriticality.REQUIRED, profile.provisioning().scim());
        assertEquals(HostingPreference.MANAGED, profile.operations().hosting());
        assertEquals(DeploymentTarget.AZURE, profile.operations().deploymentTarget());
    }

    @Test
    void keepsEvaluationProfilesStructurallyDifferent() {
        ApplicationIdentityProfile b2b = primaryB2bProfile();
        ApplicationIdentityProfile publicSector = publicSectorProfile();
        ApplicationIdentityProfile workforce = workforceProfile();

        assertNotEquals(b2b.application().type(), publicSector.application().type());
        assertNotEquals(b2b.audience().populations(), workforce.audience().populations());
        assertNotEquals(
                b2b.protocols().socialLogin(),
                publicSector.protocols().socialLogin());
        assertNotEquals(b2b.audience().tenancy(), workforce.audience().tenancy());
    }

    @Test
    void defensivelyCopiesCollections() {
        EnumSet<ClientType> clients = EnumSet.of(ClientType.BROWSER);
        EnumMap<FederationProtocol, RequirementCriticality> federation =
                new EnumMap<>(FederationProtocol.class);
        federation.put(FederationProtocol.OIDC, RequirementCriticality.REQUIRED);

        ApplicationTopology topology =
                new ApplicationTopology(ApplicationType.B2B_SAAS, clients);
        ProtocolRequirements protocols = new ProtocolRequirements(
                federation,
                RequirementCriticality.REQUIRED,
                RequirementCriticality.REQUIRED,
                RequirementCriticality.REQUIRED);

        clients.add(ClientType.NATIVE_MOBILE);
        federation.put(FederationProtocol.SAML, RequirementCriticality.REQUIRED);

        assertEquals(Set.of(ClientType.BROWSER), topology.clients());
        assertEquals(
                RequirementCriticality.UNKNOWN,
                protocols.criticalityOf(FederationProtocol.SAML));
        assertThrows(
                UnsupportedOperationException.class,
                () -> topology.clients().add(ClientType.MACHINE_TO_MACHINE));
    }

    private static ApplicationIdentityProfile primaryB2bProfile() {
        return new ApplicationIdentityProfile(
                new ApplicationTopology(
                        ApplicationType.B2B_SAAS,
                        Set.of(ClientType.BROWSER)),
                new AudienceRequirements(
                        Set.of(UserPopulation.EXTERNAL_CUSTOMERS, UserPopulation.PARTNERS),
                        TenancyModel.MULTI_TENANT_ORGANIZATIONS,
                        MembershipModel.MULTIPLE_ORGANIZATIONS_PER_USER),
                new ProtocolRequirements(
                        Map.of(
                                FederationProtocol.OIDC,
                                RequirementCriticality.REQUIRED,
                                FederationProtocol.SAML,
                                RequirementCriticality.REQUIRED),
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.REQUIRED),
                new ProvisioningRequirements(
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.PREFERRED,
                        RequirementCriticality.REQUIRED),
                new SecurityRequirements(
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.PREFERRED,
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.UNKNOWN,
                        AssuranceLevel.BASELINE,
                        Set.of()),
                new OperationalConstraints(
                        HostingPreference.MANAGED,
                        DeploymentTarget.AZURE,
                        IdentityExpertise.LIMITED,
                        BudgetSensitivity.HIGH));
    }

    private static ApplicationIdentityProfile publicSectorProfile() {
        return new ApplicationIdentityProfile(
                new ApplicationTopology(
                        ApplicationType.PUBLIC_SECTOR_PORTAL,
                        Set.of(ClientType.BROWSER)),
                new AudienceRequirements(
                        Set.of(UserPopulation.CITIZENS),
                        TenancyModel.NO_ORGANIZATION_BOUNDARY,
                        MembershipModel.NOT_APPLICABLE),
                new ProtocolRequirements(
                        Map.of(FederationProtocol.OIDC, RequirementCriticality.REQUIRED),
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.NOT_REQUIRED,
                        RequirementCriticality.REQUIRED),
                new ProvisioningRequirements(
                        RequirementCriticality.NOT_REQUIRED,
                        RequirementCriticality.UNKNOWN,
                        RequirementCriticality.NOT_REQUIRED),
                new SecurityRequirements(
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.UNKNOWN,
                        AssuranceLevel.HIGH,
                        Set.of()),
                new OperationalConstraints(
                        HostingPreference.MANAGED,
                        DeploymentTarget.UNDECIDED,
                        IdentityExpertise.MODERATE,
                        BudgetSensitivity.MODERATE));
    }

    private static ApplicationIdentityProfile workforceProfile() {
        return new ApplicationIdentityProfile(
                new ApplicationTopology(
                        ApplicationType.INTERNAL_WORKFORCE,
                        Set.of(ClientType.BROWSER, ClientType.MACHINE_TO_MACHINE)),
                new AudienceRequirements(
                        Set.of(UserPopulation.EMPLOYEES, UserPopulation.CONTRACTORS),
                        TenancyModel.SINGLE_ORGANIZATION,
                        MembershipModel.SINGLE_ORGANIZATION_PER_USER),
                new ProtocolRequirements(
                        Map.of(FederationProtocol.OIDC, RequirementCriticality.REQUIRED),
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.NOT_REQUIRED,
                        RequirementCriticality.REQUIRED),
                new ProvisioningRequirements(
                        RequirementCriticality.PREFERRED,
                        RequirementCriticality.PREFERRED,
                        RequirementCriticality.REQUIRED),
                new SecurityRequirements(
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.PREFERRED,
                        RequirementCriticality.REQUIRED,
                        RequirementCriticality.UNKNOWN,
                        AssuranceLevel.ELEVATED,
                        Set.of()),
                new OperationalConstraints(
                        HostingPreference.NO_PREFERENCE,
                        DeploymentTarget.AZURE,
                        IdentityExpertise.ADVANCED,
                        BudgetSensitivity.MODERATE));
    }
}
