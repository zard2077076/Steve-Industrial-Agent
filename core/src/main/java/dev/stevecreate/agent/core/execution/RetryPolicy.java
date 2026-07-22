package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Set;

/** Strict retry bound and typed failure allowlist for one step. */
public record RetryPolicy(
        int maximumAttempts,
        int backoffTicks,
        Set<ResourceId> retryableFailures) {
    public static final int MAX_ATTEMPTS = 16;
    public static final int MAX_BACKOFF_TICKS = 1_200;
    public static final RetryPolicy NO_RETRY = new RetryPolicy(1, 0, Set.of());

    public RetryPolicy {
        if (maximumAttempts < 1 || maximumAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "maximumAttempts must be between 1 and " + MAX_ATTEMPTS);
        }
        if (backoffTicks < 0 || backoffTicks > MAX_BACKOFF_TICKS) {
            throw new IllegalArgumentException(
                    "backoffTicks must be between 0 and " + MAX_BACKOFF_TICKS);
        }
        retryableFailures = ExecutionModelValues.copyRequirements(
                retryableFailures, "retryableFailures", maximumAttempts > 1);
        if (maximumAttempts == 1 && backoffTicks != 0) {
            throw new IllegalArgumentException("A no-retry policy cannot have backoff ticks");
        }
    }

    public boolean allowsRetry() {
        return maximumAttempts > 1;
    }
}
