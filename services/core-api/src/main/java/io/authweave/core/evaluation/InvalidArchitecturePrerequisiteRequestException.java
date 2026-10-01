package io.authweave.core.evaluation;

public final class InvalidArchitecturePrerequisiteRequestException extends RuntimeException {
    public InvalidArchitecturePrerequisiteRequestException() {
        super("Use only the typed prerequisites belonging to the selected pattern.");
    }
}
