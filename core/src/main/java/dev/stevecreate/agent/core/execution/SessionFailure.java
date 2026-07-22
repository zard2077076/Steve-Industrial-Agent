package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Typed terminal failure information without retaining an exception or game object. */
public record SessionFailure(
        ResourceId failureCode,
        ResourceId sourceStepId,
        long failedTick,
        String detail) {
    public static final int MAX_DETAIL_LENGTH = 512;

    public SessionFailure {
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        if (failedTick < 0) {
            throw new IllegalArgumentException("failedTick must not be negative");
        }
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "detail must contain 1 to " + MAX_DETAIL_LENGTH + " characters");
        }
    }
}
