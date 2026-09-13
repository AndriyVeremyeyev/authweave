package io.authweave.core.assessment.domain.profile;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonInclude;

public record OperationalConstraints(
        HostingPreference hosting,
        DeploymentTarget deploymentTarget,
        IdentityExpertise identityExpertise,
        BudgetSensitivity budgetSensitivity,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = UsagePlanning.UnrecordedFilter.class)
        UsagePlanning usagePlanning) {

    public OperationalConstraints {
        // Legacy profiles have no planning inputs. API v5 and the codec require the field.
        if (usagePlanning == null) usagePlanning = UsagePlanning.unknown();
        Objects.requireNonNull(hosting, "hosting must not be null");
        Objects.requireNonNull(deploymentTarget, "deploymentTarget must not be null");
        Objects.requireNonNull(identityExpertise, "identityExpertise must not be null");
        Objects.requireNonNull(budgetSensitivity, "budgetSensitivity must not be null");
    }

    public OperationalConstraints(HostingPreference hosting, DeploymentTarget deploymentTarget,
            IdentityExpertise identityExpertise, BudgetSensitivity budgetSensitivity) {
        this(hosting, deploymentTarget, identityExpertise, budgetSensitivity, UsagePlanning.unknown());
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
