package dev.stevecreate.agent.core.execution.readiness;

/** Loader-neutral classification supplied by the host after canonical path checks. */
public enum ExecutionWorldClassification {
    ISOLATED_REPOSITORY_TEST,
    FORMAL_EXTERNAL_INSTANCE,
    FORMAL_PLAYER_SAVE
}
