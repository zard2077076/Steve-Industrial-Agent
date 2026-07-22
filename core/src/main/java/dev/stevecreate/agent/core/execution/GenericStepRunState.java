package dev.stevecreate.agent.core.execution;

/** Serializable-shaped cursor preventing a completed action from being invoked again. */
public enum GenericStepRunState {
    READY,
    ACTION,
    VERIFY
}
