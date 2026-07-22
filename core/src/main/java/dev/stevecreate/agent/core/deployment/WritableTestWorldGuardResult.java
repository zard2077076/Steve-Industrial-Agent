package dev.stevecreate.agent.core.deployment;

import java.util.Objects;
import java.util.Optional;

/** Evidence-only guard result; success is not a mutation permit. */
public record WritableTestWorldGuardResult(
        boolean allowed,
        Optional<WritableTestWorldFailure> failure) {
    public WritableTestWorldGuardResult {
        failure = Objects.requireNonNull(failure, "failure");
        if (allowed == failure.isPresent()) {
            throw new IllegalArgumentException("allowed and failure must be opposites");
        }
    }

    public static WritableTestWorldGuardResult success() {
        return new WritableTestWorldGuardResult(true, Optional.empty());
    }

    public static WritableTestWorldGuardResult refused(WritableTestWorldFailure failure) {
        return new WritableTestWorldGuardResult(false, Optional.of(failure));
    }
}
