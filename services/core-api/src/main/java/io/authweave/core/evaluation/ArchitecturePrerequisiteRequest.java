package io.authweave.core.evaluation;

import java.util.Map;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Declaration;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.PrerequisiteId;

/** Temporary design declarations, never observed configuration or an approval. */
public record ArchitecturePrerequisiteRequest(
        @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion,
        @NotNull PatternId patternId,
        @NotNull @Size(max = 3) Map<PrerequisiteId, @NotNull Declaration> declarations) { }
