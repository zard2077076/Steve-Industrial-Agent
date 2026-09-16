package dev.stevecreate.agent.core.execution.construction;

/** Cleanup never grants arbitrary block or inventory authority. */
public enum CleanupScope {
    NONE,
    SESSION_OWNED_REVERSIBLE_ONLY,
    REFERENCE_COUNTED_SHARED_INFRASTRUCTURE
}
