package dev.stevecreate.agent.core.execution.construction;

public enum TaskExecutionOutcome {
    PENDING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    CANCELLED,
    RECOVERY_REQUIRED,
    UNSUPPORTED
}
