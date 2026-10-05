package io.authweave.core.evaluation;

public class InvalidArchitectureConfigurationRequestException extends RuntimeException {
    public InvalidArchitectureConfigurationRequestException() {
        super("Use only typed settings and values belonging to the selected architecture pattern.");
    }
}
