package io.authweave.core.evaluation;

import java.util.Map;

import io.authweave.core.catalog.ProviderCatalog;

/** All weights are supplied by the caller; there are no defaults. */
public record WeightedComparisonRequest(Map<ProviderCatalog.Capability, Integer> weights) { }
