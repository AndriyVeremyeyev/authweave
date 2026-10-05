package io.authweave.core.evaluation;

import java.util.Map;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.*;

/** Temporary design declarations; the stored assessment owns all criticalities. */
public record ProvisioningLifecycleRequest(
        @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion,
        @NotNull PatternId patternId,
        @NotNull @Size(max = 8) Map<ConditionId, @NotNull Declaration> declarations) {
    public ProvisioningLifecycleRequest {
        declarations = Map.copyOf(declarations);
        if (!ProvisioningLifecycleEvaluator.definition(patternId).conditions().containsAll(declarations.keySet()))
            throw new IllegalArgumentException("Use only conditions for the selected provisioning pattern");
    }
}
