package io.authweave.core.catalog.publication;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-catalog-bootstrap-impact-write")
public final class LocalCatalogBootstrapImpactCommand {
    private final LocalCatalogBootstrapImpactWriter writer;
    public LocalCatalogBootstrapImpactCommand(LocalCatalogBootstrapImpactWriter writer) { this.writer = writer; }
    public LocalCatalogBootstrapImpactWriter.SaveResult store(Map<String, String> env) {
        var report = uuid(env.get("AUTHWEAVE_CATALOG_BOOTSTRAP_IMPACT_REPORT_ID"), "AUTHWEAVE_CATALOG_BOOTSTRAP_IMPACT_REPORT_ID");
        var review = uuid(env.get("AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_ID"), "AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_ID");
        var hash = env.get("AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_SHA256");
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Set AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_SHA256 to an exact lowercase SHA-256");
        return writer.save(report, review, hash);
    }
    private static UUID uuid(String value, String key) {
        if (value == null || !value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("Set " + key + " to a canonical UUID");
        return UUID.fromString(value);
    }
}
