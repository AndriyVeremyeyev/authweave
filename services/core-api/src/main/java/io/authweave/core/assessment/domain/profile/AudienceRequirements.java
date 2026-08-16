package io.authweave.core.assessment.domain.profile;

import java.util.Objects;
import java.util.Set;

public record AudienceRequirements(
        Set<UserPopulation> populations,
        TenancyModel tenancy,
        MembershipModel membership) {

    public AudienceRequirements {
        populations = Set.copyOf(
                Objects.requireNonNull(populations, "populations must not be null"));
        Objects.requireNonNull(tenancy, "tenancy must not be null");
        Objects.requireNonNull(membership, "membership must not be null");
    }

    public static AudienceRequirements unknown() {
        return new AudienceRequirements(
                Set.of(),
                TenancyModel.UNKNOWN,
                MembershipModel.UNKNOWN);
    }

    public enum UserPopulation {
        EXTERNAL_CUSTOMERS,
        PARTNERS,
        CITIZENS,
        EMPLOYEES,
        CONTRACTORS,
        INTERNAL_OPERATORS
    }

    public enum TenancyModel {
        MULTI_TENANT_ORGANIZATIONS,
        SINGLE_ORGANIZATION,
        NO_ORGANIZATION_BOUNDARY,
        UNKNOWN
    }

    public enum MembershipModel {
        SINGLE_ORGANIZATION_PER_USER,
        MULTIPLE_ORGANIZATIONS_PER_USER,
        NOT_APPLICABLE,
        UNKNOWN
    }
}
