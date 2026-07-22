package dev.stevecreate.agent.core.deployment;

/** Read-only estimate of how completely a previewed change can be reversed. */
public enum RollbackClassification {
    FULLY_REVERSIBLE,
    REVERSIBLE_WITH_RESOURCE_LOSS,
    PARTIAL,
    UNSUPPORTED
}
