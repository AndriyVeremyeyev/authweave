package io.authweave.core.catalog.impact;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-catalog-impact-write")
public final class LocalCatalogImpactCommand {
    private final LocalCatalogImpactWriter writer;
    public LocalCatalogImpactCommand(LocalCatalogImpactWriter writer) { this.writer = writer; }
    public LocalCatalogImpactWriter.SaveResult store(Map<String, String> environment) {
        var o = options(environment); return writer.save(o.reportId(), o.proposalId(), o.version());
    }
    static Options options(Map<String, String> environment) {
        var reportId = uuid(environment, "AUTHWEAVE_CATALOG_IMPACT_REPORT_ID");
        var proposalId = uuid(environment, "AUTHWEAVE_CATALOG_PROPOSAL_ID");
        var text = environment.get("AUTHWEAVE_CATALOG_PROPOSAL_VERSION");
        if (text == null || !text.matches("0|[1-9][0-9]{0,15}")) throw new IllegalArgumentException("Set AUTHWEAVE_CATALOG_PROPOSAL_VERSION to an explicit non-negative safe integer.");
        long version = Long.parseLong(text); CatalogImpactReportRepository.version(version);
        return new Options(reportId, proposalId, version);
    }
    private static UUID uuid(Map<String, String> environment, String key) {
        var value = environment.get(key);
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Set " + key + " to a canonical UUID.");
        }
        return UUID.fromString(value);
    }
    record Options(UUID reportId, UUID proposalId, long version) { }
}
