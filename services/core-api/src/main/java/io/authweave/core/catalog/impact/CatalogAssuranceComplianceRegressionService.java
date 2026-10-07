package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.AssuranceCompliancePlanningEvaluator;
import io.authweave.core.evaluation.AssuranceCompliancePlanningPreflight;
import static io.authweave.core.evaluation.AssuranceCompliancePlanningPreflight.*;

/** Synthetic investigation replay only, not source verification, candidate impact or publication coverage. */
@Service
public final class CatalogAssuranceComplianceRegressionService {
    public static final String POLICY_VERSION = "catalog-assurance-compliance-regression-1";
    public static final UUID WORKSPACE = UUID.fromString("60000000-0000-4000-8000-000000000001");
    public static UUID assessmentId(int index) { return new UUID(0x8000000000004000L, 0x8000000000000000L + index + 1); }
    public static final String DEFINITIONS_SHA256 = CatalogDraftCanonicalizer.sha256(AssuranceCompliancePlanningEvaluator.evaluate(WORKSPACE,
            assessmentId(0), 0, ApplicationIdentityProfile.unknown(), Instant.EPOCH));
    private final CatalogAssuranceComplianceCases cases;
    public CatalogAssuranceComplianceRegressionService(CatalogAssuranceComplianceCases cases) { this.cases = cases; }
    public record Row(CatalogAssuranceComplianceCases.Definition input, AssuranceCompliancePlanningPreflight analysis) { }
    public record Analysis(Instant evaluatedAt, String scenarioSetSha256, List<Row> rows) {
        public Analysis { Objects.requireNonNull(evaluatedAt); rows = List.copyOf(rows); }
    }
    public record ItemCounts(int inputClarificationNeeded, int evidenceNeeded, int notApplied) {
        public ItemCounts { if (inputClarificationNeeded < 0 || evidenceNeeded < 0 || notApplied < 0 || (long) inputClarificationNeeded + evidenceNeeded + notApplied > 252) throw new IllegalArgumentException("Invalid investigation counts"); }
        int total() { return inputClarificationNeeded + evidenceNeeded + notApplied; }
    }
    public record HumanCounts(int machineOnly, int humanScopeRecorded, int scopeUnresolved) {
        public HumanCounts { if (machineOnly < 0 || humanScopeRecorded < 0 || scopeUnresolved < 0 || (long) machineOnly + humanScopeRecorded + scopeUnresolved != CatalogAssuranceComplianceCases.COUNT) throw new IllegalArgumentException("Incomplete human scope counts"); }
    }
    public record Check(Instant evaluatedAt, String scenarioSetSha256, String auditabilityScenarioSetSha256, String analysisSha256,
            ItemCounts assurance, ItemCounts compliance, HumanCounts humanScopes, int checkedComplianceItems, int complianceScopeNotApplied) {
        public Check {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(assurance); Objects.requireNonNull(compliance); Objects.requireNonNull(humanScopes);
            if (!hash(scenarioSetSha256) || !hash(auditabilityScenarioSetSha256) || !hash(analysisSha256) || assurance.total() != 252 || compliance.notApplied() != 0
                    || checkedComplianceItems < 0 || checkedComplianceItems > 216 || compliance.total() != checkedComplianceItems
                    || complianceScopeNotApplied < 0 || complianceScopeNotApplied > CatalogAssuranceComplianceCases.COUNT) throw new IllegalArgumentException("Incomplete assurance/compliance regression summary");
        }
        @JsonProperty public String scope() { return "SYNTHETIC_ASSURANCE_COMPLIANCE_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String planningPolicyVersion() { return AssuranceCompliancePlanningEvaluator.POLICY_VERSION; }
        @JsonProperty public String definitionsSha256() { return DEFINITIONS_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogAssuranceComplianceCases.VERSION; }
        @JsonProperty public String baseScenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String baseScenarioSetSha256() { return CatalogAssuranceComplianceCases.BASE_SHA256; }
        @JsonProperty public String auditabilityScenarioSetVersion() { return CatalogAuditabilityRegressionCases.VERSION; }
        @JsonProperty public int profileSchemaVersion() { return 6; }
        @JsonProperty public String profileSchemaSha256() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String analysisBasis() { return "SYNTHETIC_INPUTS_AND_UNVERIFIED_INVESTIGATION_PROMPTS"; }
        @JsonProperty public int declaredProfiles() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public int checkedCases() { return CatalogAssuranceComplianceCases.COUNT; }
        @JsonProperty public int checkedAssuranceItems() { return 252; }
        @JsonProperty public int needsInformationCases() { return CatalogAssuranceComplianceCases.COUNT; }
        @JsonProperty public List<CatalogAssuranceComplianceCases.Variant> variants() { return List.of(CatalogAssuranceComplianceCases.Variant.values()); }
        @JsonProperty public List<ItemId> itemIds() { return List.of(ItemId.values()); }
        @JsonProperty public List<String> checkedPaths() { return AssuranceCompliancePlanningEvaluator.CHECKED_PATHS; }
        @JsonProperty public List<String> deferredBoundaries() { return AssuranceCompliancePlanningEvaluator.DEFERRED_BOUNDARIES; }
        @JsonProperty public boolean candidateChangesEvaluated() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean assuranceVerified() { return false; }
        @JsonProperty public boolean complianceVerified() { return false; }
        @JsonProperty public boolean legalApplicabilityDetermined() { return false; }
        @JsonProperty public boolean providerEligibilityEvaluated() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
    }
    public Analysis analyzeAt(Instant at) {
        Objects.requireNonNull(at);
        var rows = IntStream.range(0, cases.definitions().size()).mapToObj(index -> {
            var input = cases.definitions().get(index);
            var actual = AssuranceCompliancePlanningEvaluator.evaluate(WORKSPACE, assessmentId(index), 0, input.profile(), at);
            var wanted = CatalogAssuranceComplianceCases.expected(input);
            if (!actual.inputs().equals(input.inputs()) || actual.humanScope() != wanted.humanScope() || !actual.assuranceItems().equals(wanted.assuranceItems())
                    || !actual.complianceItems().equals(wanted.complianceItems()) || !actual.complianceScopeCheck().equals(wanted.complianceScopeCheck()))
                throw new IllegalStateException("Assurance/compliance policy drift: review explicit investigation expectations");
            return new Row(input, actual);
        }).toList();
        return new Analysis(at, cases.sha256(), rows);
    }
    public Check inspectAt(Instant at) { return summarize(analyzeAt(at)); }
    public Check summarize(Analysis report) {
        if (!report.equals(analyzeAt(report.evaluatedAt()))) throw new IllegalArgumentException("Use the complete exact-bound assurance/compliance regression");
        var analyses = report.rows().stream().map(Row::analysis).toList();
        var assurance = analyses.stream().flatMap(a -> a.assuranceItems().stream()).map(AssuranceItem::status).toList();
        var compliance = analyses.stream().flatMap(a -> a.complianceItems().stream()).map(ComplianceItem::status).toList();
        return new Check(report.evaluatedAt(), cases.sha256(), cases.auditabilityScenarioSetSha256(), CatalogDraftCanonicalizer.sha256(report), counts(assurance), counts(compliance),
                new HumanCounts((int) analyses.stream().filter(a -> a.humanScope() == HumanScope.MACHINE_ONLY).count(),
                        (int) analyses.stream().filter(a -> a.humanScope() == HumanScope.HUMAN_SCOPE_RECORDED).count(), (int) analyses.stream().filter(a -> a.humanScope() == HumanScope.SCOPE_UNRESOLVED).count()),
                compliance.size(), (int) analyses.stream().filter(a -> a.complianceScopeCheck().outcome() == io.authweave.core.evaluation.CapabilityPreflight.Outcome.NOT_APPLIED).count());
    }
    private static ItemCounts counts(List<ItemStatus> values) { return new ItemCounts(count(values, ItemStatus.INPUT_CLARIFICATION_NEEDED), count(values, ItemStatus.EVIDENCE_NEEDED), count(values, ItemStatus.NOT_APPLIED)); }
    private static int count(List<ItemStatus> values, ItemStatus value) { return (int) values.stream().filter(v -> v == value).count(); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
