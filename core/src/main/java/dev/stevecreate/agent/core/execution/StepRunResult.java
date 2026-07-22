package dev.stevecreate.agent.core.execution;

import java.util.Objects;
import java.util.Optional;

/** Session plus objective bounded-work accounting from one runner tick. */
public record StepRunResult(
        GenericExecutionSession session,
        StepRunOutcome outcome,
        int actionInvocations,
        Optional<StepRunFailureCode> failureCode) {
    public StepRunResult {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(outcome, "outcome");
        if (actionInvocations < 0
                || actionInvocations > BoundedStepRunner.MAX_ACTION_INVOCATIONS_PER_TICK) {
            throw new IllegalArgumentException(
                    "actionInvocations exceeds the per-tick runner bound");
        }
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        boolean failureOutcome = outcome == StepRunOutcome.FAILED
                || outcome == StepRunOutcome.TIMED_OUT
                || outcome == StepRunOutcome.CANCELLATION_REJECTED;
        if (failureOutcome != failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "failureCode presence must match a failure-like runner outcome");
        }
    }
}
