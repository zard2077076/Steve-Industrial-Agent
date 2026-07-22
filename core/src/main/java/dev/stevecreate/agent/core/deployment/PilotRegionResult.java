package dev.stevecreate.agent.core.deployment;

import java.util.Objects;
import java.util.Optional;

public record PilotRegionResult<T>(Optional<T> value, Optional<WritableTestWorldFailure> failure) {
    public PilotRegionResult {
        value = Objects.requireNonNull(value, "value");
        failure = Objects.requireNonNull(failure, "failure");
        if (value.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one region outcome is required");
        }
    }

    public static <T> PilotRegionResult<T> success(T value) {
        return new PilotRegionResult<>(Optional.of(value), Optional.empty());
    }

    public static <T> PilotRegionResult<T> refused(WritableTestWorldFailure failure) {
        return new PilotRegionResult<>(Optional.empty(), Optional.of(failure));
    }
}
