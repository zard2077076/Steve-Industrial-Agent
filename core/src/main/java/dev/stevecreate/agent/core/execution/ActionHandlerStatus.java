package dev.stevecreate.agent.core.execution;

/** Typed outcome of one bounded action-handler invocation. */
public enum ActionHandlerStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
}
