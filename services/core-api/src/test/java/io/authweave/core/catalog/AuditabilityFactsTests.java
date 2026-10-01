package io.authweave.core.catalog;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.authweave.core.catalog.AuditabilityFacts.*;
import io.authweave.core.catalog.ProviderCatalog.EvidenceStatus;
import io.authweave.core.catalog.ProviderCatalog.Support;
import io.authweave.core.evaluation.AuditabilityRequirements;
import static org.junit.jupiter.api.Assertions.*;

class AuditabilityFactsTests {
    private static final Scope SCOPE = new Scope("fictional-eu", "Plan", "EU", "Configuration");
    private static final URI SOURCE = URI.create("https://audit.example.invalid/docs");
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");

    @ParameterizedTest @ValueSource(ints = { 1, 30, 90, 36500 })
    void retentionThresholdIsExplicitBoundedAndOnlyAttachedToASelectedRetentionCriterion(int days) {
        assertEquals(days, new AuditabilityRequirements(Set.of(Criterion.AUDIT_LOG_RETENTION), days).minimumRetentionDays());
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityRequirements(Set.of(Criterion.AUDIT_LOG_EXPORT), days));
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityRequirements(Set.of(), days));
    }

    @ParameterizedTest @ValueSource(ints = { -1, 0, 36501, Integer.MAX_VALUE })
    void noInventedDefaultZeroOrUnboundedRetentionThreshold(int days) {
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityRequirements(Set.of(Criterion.AUDIT_LOG_RETENTION), days));
    }

    @Test void missingRetentionThresholdAndNullDimensionsAreRejectedButUnspecifiedScopeIsRepresentable() {
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityRequirements(Set.of(Criterion.AUDIT_LOG_RETENTION), null));
        assertThrows(NullPointerException.class, () -> new AuditabilityRequirements(null, null));
        assertTrue(AuditabilityRequirements.unspecified().selectedCriteria().isEmpty());
        assertNull(AuditabilityRequirements.unspecified().minimumRetentionDays());
    }

    @ParameterizedTest @EnumSource(Criterion.class)
    void durationCannotBeBorrowedByAnotherCriterionOrAttachedToUnknownUnsupportedCapabilities(Criterion criterion) {
        if (criterion == Criterion.AUDIT_LOG_RETENTION) {
            assertEquals(30, fact(criterion, Support.SUPPORTED, 30, SOURCE).documentedMinimumRetentionDays());
            assertNull(fact(criterion, Support.SUPPORTED, null, SOURCE).documentedMinimumRetentionDays());
        } else assertThrows(IllegalArgumentException.class, () -> fact(criterion, Support.SUPPORTED, 30, SOURCE));
        for (var support : Set.of(Support.UNKNOWN, Support.UNSUPPORTED)) {
            assertThrows(IllegalArgumentException.class, () -> fact(criterion, support, 30, SOURCE));
        }
    }

    @ParameterizedTest @ValueSource(ints = { -1, 36501, Integer.MAX_VALUE })
    void documentedDurationHasItsOwnFiniteBounds(int days) {
        assertThrows(IllegalArgumentException.class, () -> fact(Criterion.AUDIT_LOG_RETENTION, Support.SUPPORTED, days, SOURCE));
    }

    @ParameterizedTest @ValueSource(strings = { "https://vendor.example/docs", "http://audit.example.invalid/docs", "https://user:secret@audit.example.invalid/docs" })
    void syntheticFactsCannotClaimRealVendorSourcesOrCarryCredentials(String source) {
        assertThrows(IllegalArgumentException.class, () -> fact(Criterion.AUDIT_LOG_EXPORT, Support.SUPPORTED, null, URI.create(source)));
    }

    @ParameterizedTest @ValueSource(strings = { "", " ", " Plan", "Plan ", "Plan\nvalue" })
    void exactScopeLabelsCannotBeBlankTrimmedImplicitlyOrContainControlCharacters(String label) {
        assertThrows(IllegalArgumentException.class, () -> new Scope("fictional-eu", label, "EU", "Configuration"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("fictional-eu", "Plan", label, "Configuration"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("fictional-eu", "Plan", "EU", label));
    }

    @Test void nullAndOversizedEvidenceScopeOrLabelsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Scope("Bad-ID", "Plan", "EU", "Configuration"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("x".repeat(101), "Plan", "EU", "Configuration"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("fictional-eu", "x".repeat(121), "EU", "Configuration"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("fictional-eu", null, "EU", "Configuration"));
        assertThrows(NullPointerException.class, () -> new Fact(null, Emitter.IDENTITY_PROVIDER, Criterion.AUDIT_LOG_EXPORT, Support.SUPPORTED, null, EvidenceStatus.REVIEWED, SOURCE, AT));
        assertThrows(NullPointerException.class, () -> new Fact(SCOPE, null, Criterion.AUDIT_LOG_EXPORT, Support.SUPPORTED, null, EvidenceStatus.REVIEWED, SOURCE, AT));
        assertThrows(NullPointerException.class, () -> new Fact(SCOPE, Emitter.IDENTITY_PROVIDER, Criterion.AUDIT_LOG_EXPORT, null, null, EvidenceStatus.REVIEWED, SOURCE, AT));
        assertThrows(NullPointerException.class, () -> new Fact(SCOPE, Emitter.IDENTITY_PROVIDER, Criterion.AUDIT_LOG_EXPORT, Support.SUPPORTED, null, null, SOURCE, AT));
        assertThrows(NullPointerException.class, () -> new Fact(SCOPE, Emitter.IDENTITY_PROVIDER, Criterion.AUDIT_LOG_EXPORT, Support.SUPPORTED, null, EvidenceStatus.REVIEWED, null, AT));
        assertThrows(NullPointerException.class, () -> new Fact(SCOPE, Emitter.IDENTITY_PROVIDER, Criterion.AUDIT_LOG_EXPORT, Support.SUPPORTED, null, EvidenceStatus.REVIEWED, SOURCE, null));
    }

    private static Fact fact(Criterion criterion, Support support, Integer days, URI source) {
        return new Fact(SCOPE, Emitter.IDENTITY_PROVIDER, criterion, support, days, EvidenceStatus.REVIEWED, source, AT);
    }
}
