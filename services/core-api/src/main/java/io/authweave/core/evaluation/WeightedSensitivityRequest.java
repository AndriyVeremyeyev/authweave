package io.authweave.core.evaluation;

import java.util.Map;

import io.authweave.core.catalog.ProviderCatalog;

/** Two complete, caller-selected weight sets for the same assessment snapshot. */
public record WeightedSensitivityRequest(Map<ProviderCatalog.Capability, Integer> baselineWeights,
        Map<ProviderCatalog.Capability, Integer> alternativeWeights) { }
