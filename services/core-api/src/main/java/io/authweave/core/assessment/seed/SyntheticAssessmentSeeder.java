package io.authweave.core.assessment.seed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.domain.Assessment;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.persistence.AssessmentRepository;
import io.authweave.core.assessment.persistence.WorkspaceRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static io.authweave.core.generated.jooq.tables.Workspaces.WORKSPACES;

/** Explicit development command only. No startup listener, HTTP endpoint or automatic migration seed. */
@Service
@Profile("local-seed")
public class SyntheticAssessmentSeeder {

    public static final WorkspaceId WORKSPACE_ID = new WorkspaceId(
            UUID.fromString("60000000-0000-4000-8000-000000000001"));

    private final WorkspaceRepository workspaces;
    private final AssessmentRepository assessments;
    private final DSLContext dsl;
    private final List<SeedDefinition> definitions;

    public SyntheticAssessmentSeeder(WorkspaceRepository workspaces, AssessmentRepository assessments,
            DSLContext dsl, ObjectMapper mapper) throws IOException {
        this.workspaces = workspaces;
        this.assessments = assessments;
        this.dsl = dsl;
        try (var input = new ClassPathResource("seed/assessments.v1.json").getInputStream()) {
            definitions = List.copyOf(mapper.readValue(input, new TypeReference<List<SeedDefinition>>() { }));
        }
        var ids = new HashSet<UUID>();
        var keys = new HashSet<String>();
        if (definitions.size() != 3) throw new IllegalStateException("Expected three synthetic scenarios");
        for (var definition : definitions) {
            if (!ids.add(definition.id()) || !keys.add(definition.key())) {
                throw new IllegalStateException("Synthetic scenario identifiers must be unique");
            }
            // Validate every fixture before writing any data, including fixtures already seeded.
            draft(definition);
        }
    }

    @Transactional
    public List<SeedResult> seed() {
        workspaces.insertIfAbsent(WORKSPACE_ID);
        // Serialize simultaneous seed commands in this namespace, including when the workspace already exists.
        dsl.select(WORKSPACES.ID).from(WORKSPACES).where(WORKSPACES.ID.eq(WORKSPACE_ID.value()))
                .forUpdate().fetchSingle();
        List<SeedResult> results = new ArrayList<>();
        for (var definition : definitions) {
            var id = new AssessmentId(definition.id());
            boolean created = !assessments.exists(WORKSPACE_ID, id);
            if (created) assessments.insert(draft(definition));
            results.add(new SeedResult(definition.key(), definition.id(), created));
        }
        return List.copyOf(results);
    }

    private static Assessment draft(SeedDefinition definition) {
        var assessment = Assessment.createDraft(new AssessmentId(definition.id()), WORKSPACE_ID);
        assessment.updateProfile(definition.profile());
        return assessment;
    }

    public record SeedDefinition(String key, UUID id, ApplicationIdentityProfile profile) { }
    public record SeedResult(String key, UUID id, boolean created) { }
}
