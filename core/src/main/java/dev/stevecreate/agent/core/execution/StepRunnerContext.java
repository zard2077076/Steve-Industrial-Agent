package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Immutable context passed to one bounded evaluator or action-handler invocation. */
public record StepRunnerContext(
        ResourceId sessionId,
        ResourceId stepId,
        int attempt,
        long gameTick) {
    public StepRunnerContext {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(stepId, "stepId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (gameTick < 0) {
            throw new IllegalArgumentException("gameTick must not be negative");
        }
    }
}
