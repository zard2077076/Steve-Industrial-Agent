package dev.stevecreate.agent.core.execution;

/** Observable result of one non-blocking bounded runner tick. */
public enum StepRunOutcome {
    WAITING,
    ACTION_IN_PROGRESS,
    RETRY_SCHEDULED,
    STEP_COMPLETED,
    SESSION_COMPLETED,
    CANCELLED,
    CANCELLATION_REJECTED,
    TIMED_OUT,
    FAILED
}
