package io.authweave.core.evaluation;

import java.util.Map;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Declaration;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.ConditionId;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.GroupStrategy;

public record ProvisioningLifecycleV2Request(
        @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion,
        @NotNull PatternId patternId, @NotNull GroupStrategy groupStrategy,
        @NotNull @Size(max = 15) Map<ConditionId, @NotNull Declaration> declarations) {
    public ProvisioningLifecycleV2Request {
        declarations = Map.copyOf(declarations);
        if (!ProvisioningLifecycleV2Evaluator.conditions(patternId, groupStrategy).containsAll(declarations.keySet()))
            throw new IllegalArgumentException("Use only conditions for the selected provisioning and group design");
    }
}
