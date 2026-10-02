package io.authweave.core.catalog.auditability;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator;
import static io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.SOURCE_SUPPORTS_CLAIM;

final class CatalogAuditabilityReviewFixtures {
    private CatalogAuditabilityReviewFixtures() { }
    static CatalogAuditabilityReviewRequest request(ObjectMapper mapper, CatalogAuditabilityDraftValidator drafts) throws Exception {
        var root = mapper.createObjectNode(); var directory = Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures");
        root.set("baseDraft", mapper.readTree(directory.resolve("provider-catalog-draft.valid.json").toFile()));
        root.set("auditabilityDraft", mapper.readTree(directory.resolve("catalog-auditability-draft.valid.json").toFile()));
        return bind(mapper.treeToValue(root, CatalogAuditabilityDraftValidator.Request.class), drafts);
    }
    static CatalogAuditabilityReviewRequest bind(CatalogAuditabilityDraftValidator.Request candidate, CatalogAuditabilityDraftValidator drafts) {
        var report = drafts.validate(candidate);
        return new CatalogAuditabilityReviewRequest(1, UUID.randomUUID(), report.baseValidation().contentSha256(), report.contentSha256(),
                report.reviewTargetSetSha256(), candidate, report.targets().stream().map(t -> new CatalogAuditabilityReviewRequest.Observation(
                        t.scope().optionId(), t.fact().criterion(), t.targetSha256(), SOURCE_SUPPORTS_CLAIM)).toList(),
                CatalogAuditabilityReviewRequest.Confirmation.MANUAL_AUDITABILITY_SOURCE_REVIEW);
    }
    static <T> T with(T record, String name, Object value) {
        try {
            var components = record.getClass().getRecordComponents(); var values = new Object[components.length];
            for (int i = 0; i < values.length; i++) values[i] = components[i].getName().equals(name) ? value : components[i].getAccessor().invoke(record);
            @SuppressWarnings("unchecked") var changed = (T) record.getClass().getDeclaredConstructor(Arrays.stream(components).map(c -> c.getType()).toArray(Class<?>[]::new)).newInstance(values);
            return changed;
        } catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); }
    }
}
