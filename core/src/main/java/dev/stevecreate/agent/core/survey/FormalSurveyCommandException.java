package dev.stevecreate.agent.core.survey;

public final class FormalSurveyCommandException extends RuntimeException {
    private final FormalSurveyFailureCode code;

    public FormalSurveyCommandException(FormalSurveyFailureCode code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public FormalSurveyFailureCode code() {
        return code;
    }
}
