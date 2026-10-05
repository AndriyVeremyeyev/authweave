package io.authweave.core.evaluation;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.Analysis;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.Definition;

/** Saved requirements and proposed settings remain separate, with no writes or recommendation authority. */
public record ArchitectureConfigurationPreview(ArchitecturePatternPreflight preflight, Analysis analysis) {
    public ArchitectureConfigurationPreview {
        var pattern = preflight.patterns().stream().filter(p -> p.patternId() == analysis.patternId()).findFirst().orElseThrow();
        if (analysis.clientScope() != ArchitecturePrerequisiteEvaluator.scope(pattern))
            throw new IllegalArgumentException("Unbound architecture configuration client scope");
    }
    @JsonProperty public List<Definition> settingDefinitions() { return ArchitectureConfigurationEvaluator.definitions(analysis.patternId()); }
    @JsonProperty public List<String> deferredBoundaries() { return ArchitectureConfigurationEvaluator.DEFERRED_BOUNDARIES; }
}
