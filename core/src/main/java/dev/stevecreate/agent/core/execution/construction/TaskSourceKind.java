package dev.stevecreate.agent.core.execution.construction;

/** Exact source inside the verified physical plan from which a task was derived. */
public enum TaskSourceKind {
    VERIFIED_PLACEMENT,
    VERIFIED_ROUTE,
    VERIFIED_PORT,
    VERIFIED_RESOURCE_REQUIREMENT,
    VERIFIED_SYSTEM_CHECK;

    public boolean permitsWorldMutation() {
        return this == VERIFIED_PLACEMENT || this == VERIFIED_ROUTE || this == VERIFIED_PORT;
    }
}
