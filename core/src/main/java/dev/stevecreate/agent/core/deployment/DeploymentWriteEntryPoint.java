package dev.stevecreate.agent.core.deployment;

/** Every path that can reach a world mutation must present the same guard evidence. */
public enum DeploymentWriteEntryPoint {
    COMMAND,
    SESSION_CONSTRUCTION,
    EXECUTOR,
    ADAPTER_HANDLER,
    RELOAD_RECOVERY,
    JOURNAL_REPLAY,
    ROLLBACK
}
