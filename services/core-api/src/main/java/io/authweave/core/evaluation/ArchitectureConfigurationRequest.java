package io.authweave.core.evaluation;

import java.util.Map;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.SettingId;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.SettingValue;

/** Sparse temporary settings; missing values stay unknown. No observed configuration or credentials. */
public record ArchitectureConfigurationRequest(
        @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion,
        @NotNull PatternId patternId,
        @NotNull @Size(max = 10) Map<SettingId, @NotNull SettingValue> settings) { }
