package io.authweave.core.assessment.api;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.*;
import io.authweave.core.assessment.domain.profile.UsagePlanning.Metric;

public record ApplicationIdentityProfileV5Request(
        @NotNull @Valid ApplicationIdentityProfileRequest.ApplicationInput application,
        @NotNull @Valid ApplicationIdentityProfileRequest.AudienceInput audience,
        @NotNull @Valid ApplicationIdentityProfileRequest.ProtocolInput protocols,
        @NotNull @Valid ApplicationIdentityProfileRequest.ProvisioningInput provisioning,
        @NotNull @Valid ApplicationIdentityProfileV4Request.SecurityInput security,
        @NotNull @Valid OperationsInput operations) {

    ApplicationIdentityProfile toDomain() {
        var base = new ApplicationIdentityProfileV4Request(application, audience, protocols, provisioning, security,
                new ApplicationIdentityProfileRequest.OperationsInput(operations.hosting(), operations.deploymentTarget(),
                        operations.identityExpertise(), operations.budgetSensitivity())).toDomain();
        return new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(), base.provisioning(),
                base.security(), new OperationalConstraints(operations.hosting(), operations.deploymentTarget(),
                operations.identityExpertise(), operations.budgetSensitivity(), operations.usagePlanning().toDomain()));
    }

    public record OperationsInput(@NotNull HostingPreference hosting, @NotNull DeploymentTarget deploymentTarget,
            @NotNull IdentityExpertise identityExpertise, @NotNull BudgetSensitivity budgetSensitivity,
            @NotNull @Valid UsagePlanningInput usagePlanning) { }

    public record UsagePlanningInput(@NotNull @Size(max = 500) String scopeDescription,
            @NotNull @Size(max = 10) @UniqueElements List<@NotBlank @Size(max = 500) String> assumptions,
            @NotNull Map<@NotNull Metric, @NotNull @Valid QuantityInput> volumes) {
        UsagePlanning toDomain() {
            return new UsagePlanning(scopeDescription, assumptions, volumes.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toDomain())));
        }
    }

    public record QuantityInput(@NotNull UsagePlanning.Basis basis,
            @NotNull @Min(0) @Max(9007199254740991L) Long value) {
        UsagePlanning.Quantity toDomain() { return new UsagePlanning.Quantity(basis, value); }
    }
}
