package io.authweave.core.assessment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Assessment {

    private static final Map<AssessmentStatus, Set<AssessmentStatus>> ALLOWED_TRANSITIONS =
            allowedTransitions();

    private final AssessmentId id;
    private final WorkspaceId workspaceId;
    private AssessmentStatus status;

    private Assessment(AssessmentId id, WorkspaceId workspaceId, AssessmentStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static Assessment createDraft(AssessmentId id, WorkspaceId workspaceId) {
        return new Assessment(id, workspaceId, AssessmentStatus.DRAFT);
    }

    public AssessmentId id() {
        return id;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public AssessmentStatus status() {
        return status;
    }

    public void markReadyForEvaluation() {
        transitionTo(AssessmentStatus.READY_FOR_EVALUATION);
    }

    public void recordEvaluation() {
        transitionTo(AssessmentStatus.EVALUATED);
    }

    public void recordDecision() {
        transitionTo(AssessmentStatus.DECIDED);
    }

    public void revise() {
        transitionTo(AssessmentStatus.DRAFT);
    }

    public void archive() {
        transitionTo(AssessmentStatus.ARCHIVED);
    }

    private void transitionTo(AssessmentStatus targetStatus) {
        if (!ALLOWED_TRANSITIONS.get(status).contains(targetStatus)) {
            throw new InvalidAssessmentTransitionException(status, targetStatus);
        }
        status = targetStatus;
    }

    private static Map<AssessmentStatus, Set<AssessmentStatus>> allowedTransitions() {
        EnumMap<AssessmentStatus, Set<AssessmentStatus>> transitions =
                new EnumMap<>(AssessmentStatus.class);
        transitions.put(
                AssessmentStatus.DRAFT,
                EnumSet.of(
                        AssessmentStatus.READY_FOR_EVALUATION,
                        AssessmentStatus.ARCHIVED));
        transitions.put(
                AssessmentStatus.READY_FOR_EVALUATION,
                EnumSet.of(
                        AssessmentStatus.DRAFT,
                        AssessmentStatus.EVALUATED,
                        AssessmentStatus.ARCHIVED));
        transitions.put(
                AssessmentStatus.EVALUATED,
                EnumSet.of(
                        AssessmentStatus.DRAFT,
                        AssessmentStatus.DECIDED,
                        AssessmentStatus.ARCHIVED));
        transitions.put(
                AssessmentStatus.DECIDED,
                EnumSet.of(
                        AssessmentStatus.DRAFT,
                        AssessmentStatus.ARCHIVED));
        transitions.put(AssessmentStatus.ARCHIVED, EnumSet.noneOf(AssessmentStatus.class));
        return Map.copyOf(transitions);
    }
}
