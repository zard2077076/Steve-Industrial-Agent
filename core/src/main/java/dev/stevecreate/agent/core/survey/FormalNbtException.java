package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.util.Objects;

public final class FormalNbtException extends IOException {
    private final FormalSurveyFailureCode code;

    public FormalNbtException(FormalSurveyFailureCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public FormalNbtException(FormalSurveyFailureCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public FormalSurveyFailureCode code() {
        return code;
    }
}
