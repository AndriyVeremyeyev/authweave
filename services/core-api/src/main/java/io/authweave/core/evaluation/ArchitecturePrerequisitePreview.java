package io.authweave.core.evaluation;

import java.util.Map;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Analysis;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Declaration;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.PrerequisiteId;

/** The existing two-criterion preflight remains separate from conditional prerequisite results. */
public record ArchitecturePrerequisitePreview(ArchitecturePatternPreflight preflight,
        Map<PrerequisiteId, Declaration> declarations, Analysis analysis) {
    public ArchitecturePrerequisitePreview { declarations = Map.copyOf(declarations); }
}
