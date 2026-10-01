package io.authweave.core.catalog.impact;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-catalog-regression-write")
public final class LocalCatalogFactPathReportCommand {
    private final LocalCatalogFactPathReportWriter writer;
    public LocalCatalogFactPathReportCommand(LocalCatalogFactPathReportWriter writer) { this.writer = writer; }
    public LocalCatalogFactPathReportWriter.SaveResult store(Map<String, String> environment) {
        var options = LocalCatalogImpactCommand.options(environment, "AUTHWEAVE_CATALOG_REGRESSION_REPORT_ID");
        return writer.save(options.reportId(), options.proposalId(), options.version());
    }
}
