package io.authweave.core.assessment.domain.profile;

import java.util.Objects;

public record OperationalConstraints(
        HostingPreference hosting,
        DeploymentTarget deploymentTarget,
        IdentityExpertise identityExpertise,
        BudgetSensitivity budgetSensitivity) {

    public OperationalConstraints {
        Objects.requireNonNull(hosting, "hosting must not be null");
        Objects.requireNonNull(deploymentTarget, "deploymentTarget must not be null");
        Objects.requireNonNull(identityExpertise, "identityExpertise must not be null");
        Objects.requireNonNull(budgetSensitivity, "budgetSensitivity must not be null");
    }

    public static OperationalConstraints unknown() {
        return new OperationalConstraints(
                HostingPreference.UNKNOWN,
                DeploymentTarget.UNDECIDED,
                IdentityExpertise.UNKNOWN,
                BudgetSensitivity.UNKNOWN);
    }

    public enum HostingPreference {
        MANAGED,
        SELF_HOSTED,
        NO_PREFERENCE,
        UNKNOWN
    }

    public enum DeploymentTarget {
        AZURE,
        AWS,
        GOOGLE_CLOUD,
        ON_PREMISES,
        MULTI_CLOUD,
        UNDECIDED
    }

    public enum IdentityExpertise {
        LIMITED,
        MODERATE,
        ADVANCED,
        UNKNOWN
    }

    public enum BudgetSensitivity {
        HIGH,
        MODERATE,
        LOW,
        UNKNOWN
    }
}
