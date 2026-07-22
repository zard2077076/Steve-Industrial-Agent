package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.util.Objects;

public final class FormalReadAccessException extends IOException {
    private final FormalSurveyFailure failure;

    public FormalReadAccessException(FormalSurveyFailure failure) {
        super(Objects.requireNonNull(failure, "failure").reason());
        this.failure = failure;
    }

    public FormalSurveyFailure failure() {
        return failure;
    }
}
