package dev.stevecreate.agent.core.execution.construction;

/** Evidence vocabulary used for task gates and objective completion. */
public enum ExecutionEvidenceKind {
    PRECONDITION,
    MATERIAL_RESERVED,
    MATERIAL_WITHDRAWN,
    NAVIGATION_REACHED,
    MATERIAL_DELIVERED,
    BLOCK_STATE_VERIFIED,
    CONNECTION_VERIFIED,
    MACHINE_INTERACTION_VERIFIED,
    OUTPUT_VERIFIED,
    OWNERSHIP_VERIFIED,
    CLEANUP_VERIFIED,
    RECOVERY_RECONCILED,
    CANCELLATION_CONFIRMED,
    WORKER_HEALTH_VERIFIED
}
