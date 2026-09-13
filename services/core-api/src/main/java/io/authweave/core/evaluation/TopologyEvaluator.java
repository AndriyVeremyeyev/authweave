package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.TenancyModel;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.MembershipModel;
import io.authweave.core.catalog.ProviderCatalog.Compatibility;
import io.authweave.core.catalog.ProviderCatalog.CompatibilityFact;

import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.EligibilityPreflight.*;
import static io.authweave.core.evaluation.EligibilityPreflight.Dimension.*;
import static io.authweave.core.evaluation.EligibilityPreflight.Reason.*;

public final class TopologyEvaluator {
    private TopologyEvaluator() { }

    public static List<ContextCheck> evaluate(ApplicationIdentityProfile profile, Compatibility compatibility, Instant at) {
        List<ContextCheck> checks = new ArrayList<>();
        var application = profile.application();
        var audience = profile.audience();
        checks.add(application.type() == ApplicationType.UNKNOWN || application.type() == ApplicationType.OTHER
                ? unknown(APPLICATION_TYPE, "application.type", application.type().name())
                : check(APPLICATION_TYPE, "application.type", application.type().name(),
                        compatibility.applications().get(application.type()), at));
        checks.addAll(selected(CLIENT_TYPE, "application.clients", application.clients(), compatibility.clients(), at));

        boolean machineOnly = application.clients().equals(Set.of(ClientType.MACHINE_TO_MACHINE));
        if (machineOnly && audience.populations().isEmpty()) {
            checks.add(new ContextCheck(USER_POPULATION, "audience.populations", null, NOT_APPLIED,
                    HUMAN_POPULATION_NOT_APPLICABLE,
                    "No human population is selected for this machine-only client; workload authorization is not evaluated.", null));
        } else {
            checks.addAll(selected(USER_POPULATION, "audience.populations", audience.populations(), compatibility.populations(), at));
        }
        checks.add(audience.tenancy() == TenancyModel.UNKNOWN
                ? unknown(TENANCY, "audience.tenancy", audience.tenancy().name())
                : check(TENANCY, "audience.tenancy", audience.tenancy().name(),
                        compatibility.tenancy().get(audience.tenancy()), at));
        checks.add(audience.membership() == MembershipModel.UNKNOWN
                ? unknown(MEMBERSHIP, "audience.membership", audience.membership().name())
                : check(MEMBERSHIP, "audience.membership", audience.membership().name(),
                        compatibility.membership().get(audience.membership()), at));
        return List.copyOf(checks);
    }

    private static <E extends Enum<E>> List<ContextCheck> selected(Dimension dimension, String path,
            Set<E> values, Map<E, CompatibilityFact> facts, Instant at) {
        if (values.isEmpty()) return List.of(unknown(dimension, path, null));
        return values.stream().sorted(Comparator.comparing(Enum::name))
                .map(value -> check(dimension, path, value.name(), facts.get(value), at)).toList();
    }

    private static ContextCheck unknown(Dimension dimension, String path, String value) {
        return new ContextCheck(dimension, path, value, UNKNOWN, PROFILE_CONTEXT_UNKNOWN,
                "Specify this context before relying on a compatibility result; OTHER also needs classification.", null);
    }

    private static ContextCheck check(Dimension dimension, String path, String value, CompatibilityFact fact, Instant at) {
        var problem = EvidencePolicy.problem(fact, at);
        if (problem != null) return new ContextCheck(dimension, path, value, UNKNOWN,
                Reason.valueOf(problem.name()), problem.explanation(), fact);
        return switch (fact.support()) {
            case SUPPORTED -> new ContextCheck(dimension, path, value, PASS, CONTEXT_SUPPORTED,
                    "The recorded plan and region support this selected context value.", fact);
            case UNSUPPORTED -> new ContextCheck(dimension, path, value, FAIL, CONTEXT_UNSUPPORTED,
                    "The recorded plan and region do not support this selected context value.", fact);
            case UNKNOWN -> new ContextCheck(dimension, path, value, UNKNOWN, CONTEXT_SUPPORT_UNKNOWN,
                    "Support for this context value has not been established for the plan and region.", fact);
        };
    }
}
