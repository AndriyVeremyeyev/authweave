package io.authweave.core.assessment.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.authweave.core.assessment.domain.AssessmentStatus;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;

/** Read-only navigation data, not an evaluation or a complete profile. */
public record AssessmentContextListItem(UUID id, AssessmentStatus status, long version,
        Instant createdAt, Instant updatedAt, Context context) {

    public record Context(ApplicationType applicationType, List<ClientType> clients,
            List<UserPopulation> userPopulations) {
        public Context {
            clients = List.copyOf(clients);
            userPopulations = List.copyOf(userPopulations);
        }

        static Context from(ApplicationIdentityProfile profile) {
            return new Context(profile.application().type(),
                    profile.application().clients().stream().sorted().toList(),
                    profile.audience().populations().stream().sorted().toList());
        }
    }
}
