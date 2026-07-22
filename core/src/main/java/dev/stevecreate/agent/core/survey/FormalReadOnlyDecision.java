package dev.stevecreate.agent.core.survey;

import java.util.Objects;
import java.util.Optional;

public record FormalReadOnlyDecision(boolean allowed, FormalSurveyFailure failure) {
    public FormalReadOnlyDecision {
        if (allowed == (failure != null)) {
            throw new IllegalArgumentException("exactly one of allowed or failure must be present");
        }
    }

    public static FormalReadOnlyDecision allow() {
        return new FormalReadOnlyDecision(true, null);
    }

    public static FormalReadOnlyDecision refuse(FormalSurveyFailure failure) {
        return new FormalReadOnlyDecision(false, Objects.requireNonNull(failure, "failure"));
    }

    public Optional<FormalSurveyFailure> typedFailure() {
        return Optional.ofNullable(failure);
    }
}
