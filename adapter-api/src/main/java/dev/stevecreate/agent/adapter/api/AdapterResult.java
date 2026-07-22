package dev.stevecreate.agent.adapter.api;

import java.util.Objects;

public sealed interface AdapterResult<T> permits AdapterResult.Success, AdapterResult.Failure {
    record Success<T>(T value) implements AdapterResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<T>(AdapterFailureCode code, String detail) implements AdapterResult<T> {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }
}

