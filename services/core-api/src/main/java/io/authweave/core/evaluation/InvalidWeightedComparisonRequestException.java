package io.authweave.core.evaluation;

public class InvalidWeightedComparisonRequestException extends RuntimeException {
    private final String path;

    public InvalidWeightedComparisonRequestException(String message) {
        this("weights", message);
    }

    public InvalidWeightedComparisonRequestException(String path, String message) {
        super(message);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
