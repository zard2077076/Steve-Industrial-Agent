package dev.stevecreate.agent.core.execution;

/** Explicit in-memory lifecycle state for a generic execution session. */
public enum GenericExecutionSessionStatus {
    RUNNING,
    CANCELLED,
    TIMED_OUT,
    FAILED,
    COMPLETED;

    public boolean terminal() {
        return this != RUNNING;
    }

    public boolean failureTerminal() {
        return this == TIMED_OUT || this == FAILED;
    }
}
